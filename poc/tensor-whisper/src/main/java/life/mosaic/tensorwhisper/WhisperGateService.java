package life.mosaic.tensorwhisper;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.Environment;
import com.google.ai.edge.litert.TensorBuffer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import life.mosaic.voice.WhisperNative;

/**
 * Disposable Tensor G5 / whisper.cpp worker.
 *
 * The large AOT encoder and GGML decoder stay outside the APK and outside Git.
 * Real user audio is a short PCM16 cache file written by the foreground Activity;
 * the worker deletes it after the attempt.
 */
public final class WhisperGateService extends Service {
    private static final String MODEL_NAME =
            "ivrit_whisper_encoder_Google_Tensor_G5.tflite";
    private static final long EXPECTED_MODEL_BYTES = 1_327_807_744L;

    private static final String DECODER_NAME = "ivrit-whisper-decoder-q5_0.bin";
    private static final long EXPECTED_DECODER_BYTES = 574_041_195L;

    private static final int N_MELS = 128;
    private static final int N_FRAMES = 3000;
    private static final int INPUT_FLOATS = N_MELS * N_FRAMES;
    private static final int OUTPUT_FLOATS = 1500 * 1280;

    static final int REQUEST_LOAD_ONLY = 1;
    static final int REQUEST_RUN_ZERO_MEL = 2;
    static final int REQUEST_HYBRID_PCM = 3;
    static final int REQUEST_CROSS_LOAD = 4;
    static final int REQUEST_CROSS_RUN = 5;
    static final int REQUEST_CROSS_COMPARE = 6;
    static final int REQUEST_TPU_CROSS_PCM = 7;
    private static final String CROSS_NAME = "ivrit_whisper_cross_attention_Google_Tensor_G5.tflite";
    private static final long CROSS_BYTES = 26_846_992L;
    private static final String CROSS_SHA256 =
            "fa2d5bb9200d8db59860c5b23dfbe899762f42d269885e2633fc56b18e6306a3";
    private static final int CROSS_OUTPUT_FLOATS = 4 * 2 * 1500 * 1280;

    private boolean started;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Messenger binder = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == 9) {
            android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }
        if ((msg.what != REQUEST_LOAD_ONLY
                && msg.what != REQUEST_RUN_ZERO_MEL
                && msg.what != REQUEST_HYBRID_PCM
                && msg.what != REQUEST_CROSS_LOAD
                && msg.what != REQUEST_CROSS_RUN
                && msg.what != REQUEST_CROSS_COMPARE
                && msg.what != REQUEST_TPU_CROSS_PCM)
                || started
                || msg.replyTo == null) {
            return true;
        }
        started = true;

        Messenger reply = msg.replyTo;
        Message pid = Message.obtain(null, 1);
        pid.arg1 = android.os.Process.myPid();
        try {
            reply.send(pid);
        } catch (RemoteException e) {
            android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }

        final int request = msg.what;
        final String pcmPath = msg.getData().getString("pcmPath", "");
        Executors.newSingleThreadExecutor().execute(() -> runGate(reply, request, pcmPath));
        return true;
    }));

    private void send(Messenger reply, int what, String text) {
        Message msg = Message.obtain(null, what);
        Bundle data = new Bundle();
        data.putString("text", text);
        msg.setData(data);
        try {
            reply.send(msg);
        } catch (RemoteException e) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private File externalFile(String name, long expectedBytes) {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            throw new IllegalStateException("External app files directory unavailable");
        }
        File file = new File(dir, name);
        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Model missing. Push " + name + " to " + dir.getAbsolutePath());
        }
        if (file.length() != expectedBytes) {
            throw new IllegalStateException(
                    "Unexpected size for " + name + ": " + file.length()
                            + " bytes; expected " + expectedBytes);
        }
        return file;
    }

    private File requirePcmFile(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IllegalStateException("PCM path missing");
        }
        File file = new File(path).getCanonicalFile();
        File cache = getCacheDir().getCanonicalFile();
        if (!file.getPath().startsWith(cache.getPath() + File.separator) || !file.isFile()) {
            throw new IllegalStateException("PCM file is outside the app cache");
        }
        if (file.length() < 16_000L || file.length() > 16_000L * 8L * 2L) {
            throw new IllegalStateException("PCM must contain 0.5 to 8 seconds at 16 kHz mono");
        }
        if ((file.length() & 1L) != 0L) {
            throw new IllegalStateException("PCM16 file has an odd byte length");
        }
        return file;
    }

    private float[] readPcm16(File file) throws IOException {
        if (file.length() > Integer.MAX_VALUE) {
            throw new IllegalStateException("PCM file too large");
        }
        byte[] bytes = new byte[(int) file.length()];
        int offset = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (offset < bytes.length) {
                int n = in.read(bytes, offset, bytes.length - offset);
                if (n < 0) break;
                offset += n;
            }
        }
        if (offset != bytes.length) {
            throw new IOException("Short PCM read: " + offset + "/" + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] pcm = new float[bytes.length / 2];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = buffer.getShort() / 32768.0f;
        }
        return pcm;
    }

    private static void requireFinite(float[] values, String label) {
        if (values.length == 0) {
            throw new IllegalStateException(label + " is empty");
        }
        int finite = 0;
        int stride = Math.max(1, values.length / 4096);
        for (int i = 0; i < values.length; i += stride) {
            if (Float.isFinite(values[i])) finite++;
        }
        if (finite == 0) {
            throw new IllegalStateException(label + " contains no finite sampled values");
        }
    }

    private File verifiedCrossFile() throws Exception {
        File file = externalFile(CROSS_NAME, CROSS_BYTES);
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] chunk = new byte[65536];
            int n;
            while ((n = in.read(chunk)) != -1) digest.update(chunk, 0, n);
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!CROSS_SHA256.equals(hash.toString())) {
            throw new IllegalStateException("Cross-attention model SHA-256 mismatch");
        }
        return file;
    }

    private static final class CrossExecution {
        float[] values;
        long loadMs;
        long stageMs;
    }

    private CrossExecution prepareCross(Environment env, float[] encoded) throws Exception {
        File file = verifiedCrossFile();
        CrossExecution result = new CrossExecution();
        long loadStart = SystemClock.elapsedRealtime();
        try (CompiledModel model = CompiledModel.create(file.getAbsolutePath(),
                new CompiledModel.Options(Accelerator.NPU), env)) {
            result.loadMs = SystemClock.elapsedRealtime() - loadStart;
            List<TensorBuffer> inputs = model.createInputBuffers(0);
            List<TensorBuffer> outputs = new ArrayList<>();
            try {
                outputs = model.createOutputBuffers(0);
                if (inputs.size() != 1 || outputs.size() != 1) {
                    throw new IllegalStateException("Unexpected cross-attention signature");
                }
                long start = SystemClock.elapsedRealtime();
                inputs.get(0).writeFloat(encoded);
                model.run(inputs, outputs, 0);
                result.values = outputs.get(0).readFloat();
                result.stageMs = SystemClock.elapsedRealtime() - start;
                if (result.values.length != CROSS_OUTPUT_FLOATS) {
                    throw new IllegalStateException("Unexpected cross-attention output size");
                }
                // All elements checked in native compare/injection; avoid duplicate
                // full Java diagnostic scan in the actual ASR performance trial.
            } finally {
                for (TensorBuffer b : outputs) b.close();
                for (TensorBuffer b : inputs) b.close();
            }
        }
        return result;
    }

    private String runCrossGate(Environment env, Messenger reply, boolean loadOnly)
            throws Exception {
        long totalStart = SystemClock.elapsedRealtime();
        File file = verifiedCrossFile();
        long verifyMs = SystemClock.elapsedRealtime() - totalStart;
        send(reply, 2, "קובץ cross-attention אומת. טוען דרך NPU…");
        long loadStart = SystemClock.elapsedRealtime();
        try (CompiledModel model = CompiledModel.create(file.getAbsolutePath(),
                new CompiledModel.Options(Accelerator.NPU), env)) {
            long loadMs = SystemClock.elapsedRealtime() - loadStart;
            if (loadOnly) return "PASS — cross-attention load only\n"
                    + "verify: " + verifyMs + " ms\nload: " + loadMs
                    + " ms\nלא בוצע חישוב. אפשר להמשיך לשלב 5.";
            List<TensorBuffer> inputs = model.createInputBuffers(0);
            List<TensorBuffer> outputs = new ArrayList<>();
            try {
                outputs = model.createOutputBuffers(0);
                if (inputs.size() != 1 || outputs.size() != 1) {
                    throw new IllegalStateException("Unexpected cross-attention signature");
                }
                // Non-zero synthetic embeddings; no microphone or Whisper context.
                float[] sample = new float[OUTPUT_FLOATS];
                for (int i = 0; i < sample.length; i++) {
                    sample[i] = ((i % 127) - 63) * (0.05f / 63.0f);
                }
                long writeStart = SystemClock.elapsedRealtime();
                inputs.get(0).writeFloat(sample);
                long writeMs = SystemClock.elapsedRealtime() - writeStart;
                send(reply, 2, "מריץ cross-attention יחיד על קלט בדיקה…");
                long runStart = SystemClock.elapsedRealtime();
                model.run(inputs, outputs, 0);
                long runMs = SystemClock.elapsedRealtime() - runStart;
                long readStart = SystemClock.elapsedRealtime();
                float[] values = outputs.get(0).readFloat();
                long readMs = SystemClock.elapsedRealtime() - readStart;
                if (values.length != CROSS_OUTPUT_FLOATS) {
                    throw new IllegalStateException("Unexpected cross output length: " + values.length);
                }
                long validateStart = SystemClock.elapsedRealtime();
                float maxAbs = 0;
                for (float value : values) {
                    if (!Float.isFinite(value)) {
                        throw new IllegalStateException("Cross output contains NaN/Inf");
                    }
                    maxAbs = Math.max(maxAbs, Math.abs(value));
                }
                if (maxAbs == 0) throw new IllegalStateException("Cross output is all zero");
                long validateMs = SystemClock.elapsedRealtime() - validateStart;
                return "PASS — cross-attention runtime probe\n"
                        + "קלט בדיקה בלבד; ללא תמלול וללא בדיקת דיוק מול Q5.\n\n"
                        + "verify: " + verifyMs + " ms\n"
                        + "load: " + loadMs + " ms\n"
                        + "input write: " + writeMs + " ms\n"
                        + "NPU run: " + runMs + " ms\n"
                        + "output read (61.44 MB): " + readMs + " ms\n"
                        + "run + transfers: " + (writeMs + runMs + readMs) + " ms\n"
                        + "validation: " + validateMs + " ms\n"
                        + "output floats: " + values.length + "\nmax abs: " + maxAbs + "\n"
                        + "total worker: " + (SystemClock.elapsedRealtime() - totalStart) + " ms";
            } finally {
                for (TensorBuffer b : outputs) b.close();
                for (TensorBuffer b : inputs) b.close();
            }
        }
    }

    private void runGate(Messenger reply, int request, String pcmPath) {
        String result;
        File pcmFile = null;
        long whisperHandle = 0L;
        try (Environment env = Environment.create(Collections.singletonMap(
                Environment.Option.DispatchLibraryDir,
                getApplicationInfo().nativeLibraryDir))) {

            Set<Accelerator> available = env.getAvailableAccelerators();
            send(reply, 2, "מאיצים זמינים: " + available);
            if (!available.contains(Accelerator.NPU)) {
                throw new IllegalStateException("NPU unavailable: " + available);
            }

            if (request == REQUEST_CROSS_LOAD || request == REQUEST_CROSS_RUN) {
                result = runCrossGate(env, reply, request == REQUEST_CROSS_LOAD);
            } else {
            File modelFile = externalFile(MODEL_NAME, EXPECTED_MODEL_BYTES);
            long totalStart = SystemClock.elapsedRealtime();

            long tensorLoadStart = SystemClock.elapsedRealtime();
            try (CompiledModel model = CompiledModel.create(
                    modelFile.getAbsolutePath(),
                    new CompiledModel.Options(Accelerator.NPU),
                    env)) {

                long tensorLoadMs = SystemClock.elapsedRealtime() - tensorLoadStart;
                send(reply, 2, "Tensor encoder נטען ב-" + tensorLoadMs + " ms");

                if (request == REQUEST_LOAD_ONLY) {
                    result =
                            "PASS — מודל ivrit.ai המלא נטען דרך נתיב NPU/TPU\n"
                                    + "זמן טעינה: " + tensorLoadMs + " ms\n"
                                    + "לא בוצע inference בשלב הזה.";
                } else if (request == REQUEST_RUN_ZERO_MEL) {
                    List<TensorBuffer> inputs = model.createInputBuffers(0);
                    List<TensorBuffer> outputs = new ArrayList<>();
                    try {
                        outputs = model.createOutputBuffers(0);
                        if (inputs.size() != 1 || outputs.isEmpty()) {
                            throw new IllegalStateException("Unexpected Tensor encoder signature");
                        }
                        inputs.get(0).writeFloat(new float[INPUT_FLOATS]);

                        long runStart = SystemClock.elapsedRealtime();
                        model.run(inputs, outputs, 0);
                        long runMs = SystemClock.elapsedRealtime() - runStart;
                        float[] encoded = outputs.get(0).readFloat();
                        if (encoded.length != OUTPUT_FLOATS) {
                            throw new IllegalStateException(
                                    "Unexpected encoder output: " + encoded.length);
                        }
                        requireFinite(encoded, "encoder output");

                        result =
                                "PASS — ivrit.ai encoder המלא רץ דרך נתיב NPU/TPU\n"
                                        + "זמן טעינה: " + tensorLoadMs + " ms\n"
                                        + "זמן encoder: " + runMs + " ms\n"
                                        + "output floats: " + encoded.length;
                    } finally {
                        for (TensorBuffer b : outputs) b.close();
                        for (TensorBuffer b : inputs) b.close();
                    }
                } else {
                    pcmFile = requirePcmFile(pcmPath);
                    File decoderFile = externalFile(DECODER_NAME, EXPECTED_DECODER_BYTES);
                    float[] pcm = readPcm16(pcmFile);
                    send(reply, 2, String.format(
                            java.util.Locale.ROOT,
                            "אודיו אמיתי: %.2f שניות", pcm.length / 16000.0));

                    long decoderLoadStart = SystemClock.elapsedRealtime();
                    WhisperNative.INSTANCE.ensureLoaded();
                    whisperHandle = WhisperNative.INSTANCE.createHybrid(decoderFile.getAbsolutePath());
                    if (whisperHandle == 0L) {
                        throw new IllegalStateException("Whisper hybrid decoder load failed");
                    }
                    long decoderLoadMs = SystemClock.elapsedRealtime() - decoderLoadStart;
                    send(reply, 2, "decoder Q5 נטען ב-" + decoderLoadMs + " ms");

                    long melStart = SystemClock.elapsedRealtime();
                    float[] encoderInput =
                            WhisperNative.INSTANCE.prepareEncoderInput(whisperHandle, pcm);
                    long melMs = SystemClock.elapsedRealtime() - melStart;
                    if (encoderInput.length != INPUT_FLOATS) {
                        throw new IllegalStateException(
                                "Unexpected mel input size: " + encoderInput.length);
                    }
                    requireFinite(encoderInput, "mel input");
                    send(reply, 2, "log-mel מוכן ב-" + melMs + " ms");

                    List<TensorBuffer> inputs = model.createInputBuffers(0);
                    List<TensorBuffer> outputs = new ArrayList<>();
                    float[] encoded;
                    long encoderMs;
                    try {
                        outputs = model.createOutputBuffers(0);
                        if (inputs.size() != 1 || outputs.isEmpty()) {
                            throw new IllegalStateException("Unexpected Tensor encoder signature");
                        }
                        inputs.get(0).writeFloat(encoderInput);

                        send(reply, 2, "מריץ Tensor G5 encoder על ההקלטה…");
                        long runStart = SystemClock.elapsedRealtime();
                        model.run(inputs, outputs, 0);
                        encoderMs = SystemClock.elapsedRealtime() - runStart;
                        encoded = outputs.get(0).readFloat();
                    } finally {
                        for (TensorBuffer b : outputs) b.close();
                        for (TensorBuffer b : inputs) b.close();
                    }

                    if (encoded.length != OUTPUT_FLOATS) {
                        throw new IllegalStateException(
                                "Unexpected encoder output: " + encoded.length);
                    }
                    requireFinite(encoded, "encoder output");

                    CrossExecution cross = null;
                    if (request == REQUEST_CROSS_COMPARE || request == REQUEST_TPU_CROSS_PCM) {
                        send(reply, 2, "מכין cross-attention על Tensor G5…");
                        cross = prepareCross(env, encoded);
                    }
                    if (request == REQUEST_CROSS_COMPARE) {
                        send(reply, 2, "משווה מטמון Q5 ב-CPU מול TPU; בדיקה חד-פעמית איטית…");
                        long compareStart = SystemClock.elapsedRealtime();
                        result = WhisperNative.INSTANCE.compareCross(whisperHandle, encoded, cross.values)
                                + "\ncomparison: " + (SystemClock.elapsedRealtime() - compareStart) + " ms"
                                + "\nTPU cross + transfers: " + cross.stageMs + " ms"
                                + "\nאין כאן תמלול; זהו סף התאמה ניסיוני ל-Q5.";
                    } else {
                    send(reply, 2, "מפענח עברית עם whisper.cpp Beam 5…");
                    long decodeStart = SystemClock.elapsedRealtime();
                    String transcript = cross == null
                            ? WhisperNative.INSTANCE.transcribeEncoded(whisperHandle, encoded, true, 60).trim()
                            : WhisperNative.INSTANCE.transcribeEncodedCross(whisperHandle, encoded, cross.values, true, 60).trim();
                    long decodeMs = SystemClock.elapsedRealtime() - decodeStart;
                    if (transcript.isEmpty()) {
                        throw new IllegalStateException("Decoder returned an empty transcript");
                    }

                    long totalMs = SystemClock.elapsedRealtime() - totalStart;
                    result =
                            (cross == null ? "PASS — תמלול hybrid אמיתי\n" : "PASS — תמלול עם encoder ו-cross-attention על TPU\n")
                                    + "תמלול: " + transcript + "\n\n"
                                    + "audio: " + String.format(
                                            java.util.Locale.ROOT,
                                            "%.2f s", pcm.length / 16000.0) + "\n"
                                    + "Tensor load: " + tensorLoadMs + " ms\n"
                                    + "decoder load: " + decoderLoadMs + " ms\n"
                                    + "mel: " + melMs + " ms\n"
                                    + "Tensor encoder: " + encoderMs + " ms\n"
                                    + (cross == null ? "" : "cross load: " + cross.loadMs + " ms\n"
                                            + "TPU cross + transfers: " + cross.stageMs + " ms\n")
                                    + "cache injection + CPU decode: " + decodeMs + " ms\n"
                                    + "total worker: " + totalMs + " ms\n"
                                    + WhisperNative.INSTANCE.timings(whisperHandle);
                    }
                }
            }
            }
        } catch (Throwable e) {
            result = "FAIL — " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (whisperHandle != 0L) {
                try {
                    WhisperNative.INSTANCE.release(whisperHandle);
                } catch (Throwable ignored) {
                }
            }
            if (pcmFile != null) {
                // User audio is transient test input, not a persisted Mosaic record.
                //noinspection ResultOfMethodCallIgnored
                pcmFile.delete();
            }
        }

        send(reply, 3, result);
        main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
    }

    @Override public IBinder onBind(Intent intent) {
        getExternalFilesDir(null);
        return binder.getBinder();
    }

    @Override public boolean onUnbind(Intent intent) {
        android.os.Process.killProcess(android.os.Process.myPid());
        return false;
    }
}
