package life.mosaic.fit.voicepoc

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.max

/** Acoustic synthesis on-device. The POC has a fixed host-phonemized reply, not free-text G2P. */
internal class BlueSynthesizer(private val root: File) {
    private val env = OrtEnvironment.getEnvironment()
    private data class Tensor(val data: FloatArray, val shape: LongArray)
    private fun jsonTensor(j: JSONObject): Tensor {
        val data = j.getJSONArray("data")
        val shape = j.getJSONArray("shape")
        return Tensor(FloatArray(data.length()) { data.getDouble(it).toFloat() },
            LongArray(shape.length()) { shape.getLong(it) })
    }
    private fun run(session: OrtSession, inputs: Map<String, Tensor>, ids: LongArray? = null): Tensor {
        val tensors = inputs.mapValues { (_, t) -> OnnxTensor.createTensor(env, FloatBuffer.wrap(t.data), t.shape) }.toMutableMap()
        try {
            if (ids != null) tensors["text_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
            session.run(tensors).use { result ->
                val out = result[0] as OnnxTensor
                val buffer = out.floatBuffer
                val floats = FloatArray(buffer.remaining()); buffer.get(floats)
                return Tensor(floats, (out.info as TensorInfo).shape)
            }
        } finally { tensors.values.forEach { it.close() } }
    }

    fun synthesize(cancelled: () -> Boolean): Pair<FloatArray, Int> {
        val j = JSONObject(File(root, "reply.json").readText())
        val rate = j.getInt("sample_rate")
        val steps = j.getInt("steps")
        require(steps in 1..16 && rate in 16000..48000)
        val cfg = j.getDouble("cfg").toFloat()
        fun fixture(name: String) = jsonTensor(j.getJSONObject(name))
        val idJson = j.getJSONObject("ids").getJSONArray("data")
        val ids = LongArray(idJson.length()) { idJson.getLong(it) }
        require(ids.size in 1..500)
        val mask = fixture("text_mask")
        val ttl = fixture("style_ttl"); val dp = fixture("style_dp")
        val sessions = mutableListOf<OrtSession>()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4); options.setInterOpNumThreads(1)
            fun session(name: String): OrtSession {
                check(!cancelled()) { "Cancelled" }
                return env.createSession(File(root, "blue/$name.onnx").path, options).also { sessions.add(it) }
            }
            try {
                val duration = session("duration_predictor_style")
                val encoder = session("text_encoder")
                val vector = session("vector_estimator")
                val vocoder = session("vocoder")
                val seconds = run(duration, mapOf("style_dp" to dp, "text_mask" to mask), ids).data[0]
                require(seconds.isFinite() && seconds > 0 && seconds <= 25) { "Invalid fixed-reply duration: $seconds" }
                val embedding = run(encoder, mapOf("style_ttl" to ttl, "text_mask" to mask), ids)
                var x = fixture("noise")
                val lm = fixture("latent_mask")
                val frame = j.getInt("base_chunk_size") * j.getInt("compress")
                val expectedFrames = ((seconds * rate).toInt() + frame - 1) / frame
                require(x.shape.contentEquals(longArrayOf(1, (j.getInt("ldim") * j.getInt("compress")).toLong(), expectedFrames.toLong()))) {
                    "Input fixture and model duration disagree; regenerate the pinned model pack"
                }
                for (step in 0 until steps) {
                    check(!cancelled()) { "Cancelled" }
                    val base = mapOf("noisy_latent" to x, "latent_mask" to lm,
                        "current_step" to Tensor(floatArrayOf(step.toFloat()), longArrayOf(1)),
                        "total_step" to Tensor(floatArrayOf(steps.toFloat()), longArrayOf(1)))
                    val condInputs = base + mapOf("text_emb" to embedding, "style_ttl" to ttl, "text_mask" to mask)
                    x = if ("cfg_scale" in vector.inputNames) {
                        run(vector, condInputs + ("cfg_scale" to Tensor(floatArrayOf(cfg), longArrayOf(1))))
                    } else {
                        val cond = run(vector, condInputs)
                        val uncond = run(vector, base + mapOf("text_emb" to fixture("u_text"),
                            "style_ttl" to fixture("u_ref"), "text_mask" to Tensor(floatArrayOf(1f), longArrayOf(1, 1, 1))))
                        Tensor(BlueMath.guidance(cond.data, uncond.data, cfg), cond.shape)
                    }
                }
                check(!cancelled()) { "Cancelled" }
                val info = vocoder.inputInfo.getValue("latent").info as TensorInfo
                val latent = if (info.shape[1] == j.getInt("ldim").toLong()) {
                    val mean = fixture("mean").data; val std = fixture("std").data
                    val scale = j.getDouble("normalizer_scale").toFloat()
                    val ldim = j.getInt("ldim"); val compress = j.getInt("compress")
                    val time = x.shape[2].toInt()
                    require(mean.size == ldim * compress && std.size == mean.size && scale > 0)
                    val z = BlueMath.vocoderLatent(x.data, mean, std, scale, ldim, compress, time)
                    Tensor(z, longArrayOf(1, ldim.toLong(), (time * compress).toLong()))
                } else x
                val audio = run(vocoder, mapOf("latent" to latent)).data
                require(audio.isNotEmpty() && audio.all { it.isFinite() }) { "Invalid synthesized audio" }
                val trim = if (audio.size > 2 * frame) frame else 0
                val reply = audio.copyOfRange(trim, audio.size - trim)
                val peak = reply.fold(0f) { a, b -> max(a, abs(b)) }
                if (peak > 0.95f) for (i in reply.indices) reply[i] *= 0.95f / peak
                return reply to rate
            } finally { sessions.asReversed().forEach { it.close() } }
        }
    }
}
