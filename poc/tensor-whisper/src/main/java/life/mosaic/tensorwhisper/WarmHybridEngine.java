package life.mosaic.tensorwhisper;

import android.os.SystemClock;
import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.Environment;
import com.google.ai.edge.litert.TensorBuffer;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import life.mosaic.voice.WhisperNative;

/** One session-owned engine, used by exactly one inference thread. */
final class WarmHybridEngine implements AutoCloseable {
    private Environment environment;
    private CompiledModel encoder;
    private CompiledModel cross;
    private List<TensorBuffer> encoderIn, encoderOut, crossIn, crossOut;
    private long handle;
    final long loadMs;

    static final class Result {
        String text;
        long melMs, encoderMs, crossMs, decodeMs, totalMs;
    }

    WarmHybridEngine(File dir, String nativeLibraryDir) throws Exception {
        long start = SystemClock.elapsedRealtime();
        try {
            File encoderFile = require(dir, "ivrit_whisper_encoder_Google_Tensor_G5.tflite", 1_327_807_744L);
            File decoderFile = require(dir, "ivrit-whisper-decoder-q5_0.bin", 574_041_195L);
            File crossFile = require(dir, "ivrit_whisper_cross_attention_Google_Tensor_G5.tflite", 26_846_992L);
            checkHash(crossFile, "fa2d5bb9200d8db59860c5b23dfbe899762f42d269885e2633fc56b18e6306a3");
            // All resources stay in this private process and close with the session.
            environment = Environment.create(Collections.singletonMap(
                    Environment.Option.DispatchLibraryDir, nativeLibraryDir));
            if (!environment.getAvailableAccelerators().contains(Accelerator.NPU)) {
                throw new IllegalStateException("NPU unavailable");
            }
            encoder = CompiledModel.create(encoderFile.getAbsolutePath(), new CompiledModel.Options(Accelerator.NPU), environment);
            cross = CompiledModel.create(crossFile.getAbsolutePath(), new CompiledModel.Options(Accelerator.NPU), environment);
            encoderIn = encoder.createInputBuffers(0); encoderOut = encoder.createOutputBuffers(0);
            crossIn = cross.createInputBuffers(0); crossOut = cross.createOutputBuffers(0);
            for (List<TensorBuffer> buffers : java.util.Arrays.asList(encoderIn, encoderOut, crossIn, crossOut)) {
                if (buffers.size() != 1) throw new IllegalStateException("Unexpected model signature");
            }
            handle = WhisperNative.INSTANCE.createHybrid(decoderFile.getAbsolutePath());
            if (handle == 0) throw new IllegalStateException("Decoder load failed");
        } catch (Exception | Error failure) {
            close(); throw failure;
        }
        loadMs = SystemClock.elapsedRealtime() - start;
    }

    Result transcribe(float[] pcm) throws Exception {
        Result result = new Result();
        long start = SystemClock.elapsedRealtime(), phase = start;
        float[] mel = WhisperNative.INSTANCE.prepareEncoderInput(handle, pcm);
        result.melMs = SystemClock.elapsedRealtime() - phase;
        phase = SystemClock.elapsedRealtime();
        encoderIn.get(0).writeFloat(mel);
        encoder.run(encoderIn, encoderOut, 0);
        float[] encoded = encoderOut.get(0).readFloat();
        if (encoded.length != 1500 * 1280) throw new IllegalStateException("Encoder output shape");
        result.encoderMs = SystemClock.elapsedRealtime() - phase;
        phase = SystemClock.elapsedRealtime();
        crossIn.get(0).writeFloat(encoded);
        cross.run(crossIn, crossOut, 0);
        float[] cache = crossOut.get(0).readFloat();
        if (cache.length != 4 * 2 * 1500 * 1280) throw new IllegalStateException("Cross output shape");
        result.crossMs = SystemClock.elapsedRealtime() - phase;
        phase = SystemClock.elapsedRealtime();
        result.text = WhisperNative.INSTANCE.transcribeEncodedCross(handle, encoded, cache, true, 60).trim();
        result.decodeMs = SystemClock.elapsedRealtime() - phase;
        result.totalMs = SystemClock.elapsedRealtime() - start;
        return result;
    }

    private static File require(File dir, String name, long size) {
        if (dir == null) throw new IllegalStateException("External model directory unavailable");
        File file = new File(dir, name);
        if (!file.isFile() || file.length() != size) throw new IllegalStateException("Missing/incorrect model: " + name);
        return file;
    }

    private static void checkHash(File file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; int n;
            while ((n = in.read(bytes)) != -1) digest.update(bytes, 0, n);
        }
        StringBuilder actual = new StringBuilder();
        for (byte b : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!expected.equals(actual.toString())) throw new IllegalStateException("Cross model SHA-256 mismatch");
    }

    @Override public void close() {
        if (handle != 0) { WhisperNative.INSTANCE.release(handle); handle = 0; }
        for (List<TensorBuffer> buffers : java.util.Arrays.asList(crossOut, crossIn, encoderOut, encoderIn)) {
            if (buffers != null) for (TensorBuffer buffer : buffers) buffer.close();
        }
        if (cross != null) cross.close();
        if (encoder != null) encoder.close();
        if (environment != null) environment.close();
    }
}
