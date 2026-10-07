package life.mosaic.tensorwhisper;

import android.app.Service;
import android.content.Intent;
import android.os.*;

import com.google.ai.edge.litert.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Executes the compiled ivrit.ai Whisper Large v3 Turbo encoder in a private
 * disposable process.
 *
 * The 1.24 GiB AOT model is deliberately NOT packaged in the APK. Push it to
 * the app-specific external files directory after installation.
 */
public final class WhisperGateService extends Service {
    private static final String MODEL_NAME =
            "ivrit_whisper_encoder_Google_Tensor_G5.tflite";
    private static final long EXPECTED_MODEL_BYTES = 1_327_807_744L;
    private static final int N_MELS = 128;
    private static final int N_FRAMES = 3000;
    private static final int INPUT_FLOATS = N_MELS * N_FRAMES;

    static final int REQUEST_LOAD_ONLY = 1;
    static final int REQUEST_RUN_ZERO_MEL = 2;

    private boolean started;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Messenger binder = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == 9) {
            android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }
        if ((msg.what != REQUEST_LOAD_ONLY && msg.what != REQUEST_RUN_ZERO_MEL)
                || started || msg.replyTo == null) {
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
        Executors.newSingleThreadExecutor().execute(() -> runGate(reply, request));
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

    private File requireModelFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            throw new IllegalStateException("External app files directory unavailable");
        }

        File model = new File(dir, MODEL_NAME);
        if (!model.isFile()) {
            throw new IllegalStateException(
                    "Model missing. Push " + MODEL_NAME + " to " + dir.getAbsolutePath());
        }
        if (model.length() != EXPECTED_MODEL_BYTES) {
            throw new IllegalStateException(
                    "Unexpected model size: " + model.length()
                            + " bytes; expected " + EXPECTED_MODEL_BYTES);
        }
        return model;
    }

    private void runGate(Messenger reply, int request) {
        String result;
        try (Environment env = Environment.create(Collections.singletonMap(
                Environment.Option.DispatchLibraryDir,
                getApplicationInfo().nativeLibraryDir))) {

            Set<Accelerator> available = env.getAvailableAccelerators();
            send(reply, 2, "מאיצים זמינים: " + available);
            if (!available.contains(Accelerator.NPU)) {
                throw new IllegalStateException("NPU unavailable: " + available);
            }

            File modelFile = requireModelFile();
            send(
                    reply,
                    2,
                    "מודל: " + modelFile.getAbsolutePath()
                            + "\nגודל: " + modelFile.length() + " bytes");

            long createStart = SystemClock.elapsedRealtime();
            try (CompiledModel model = CompiledModel.create(
                    modelFile.getAbsolutePath(),
                    new CompiledModel.Options(Accelerator.NPU),
                    env)) {

                long loadMs = SystemClock.elapsedRealtime() - createStart;
                send(reply, 2, "מודל ivrit.ai AOT נטען ב-" + loadMs + " ms");

                if (request == REQUEST_LOAD_ONLY) {
                    result =
                            "PASS — מודל ivrit.ai המלא נטען דרך נתיב NPU/TPU\n"
                                    + "זמן טעינה: " + loadMs + " ms\n"
                                    + "לא בוצע inference בשלב הזה.";
                } else {
                    List<TensorBuffer> inputs = model.createInputBuffers(0);
                    List<TensorBuffer> outputs = new ArrayList<>();
                    try {
                        outputs = model.createOutputBuffers(0);

                        if (inputs.size() != 1) {
                            throw new IllegalStateException(
                                    "Encoder graph expected 1 input, got " + inputs.size());
                        }
                        if (outputs.isEmpty()) {
                            throw new IllegalStateException("Encoder graph returned no outputs");
                        }

                        // Large-v3 encoder contract: [1, 128, 3000] float32 log-mel.
                        // Zeros make this a deterministic backend/stability gate only.
                        inputs.get(0).writeFloat(new float[INPUT_FLOATS]);

                        send(reply, 2, "מריץ encoder מלא (128×3000 zero log-mel)…");
                        long runStart = SystemClock.elapsedRealtime();
                        model.run(inputs, outputs, 0);
                        long runMs = SystemClock.elapsedRealtime() - runStart;

                        float[] encoded = outputs.get(0).readFloat();
                        if (encoded.length == 0) {
                            throw new IllegalStateException("Empty encoder output");
                        }

                        int finite = 0;
                        double checksum = 0.0;
                        int stride = Math.max(1, encoded.length / 4096);
                        for (int i = 0; i < encoded.length; i += stride) {
                            float v = encoded[i];
                            if (Float.isFinite(v)) {
                                finite++;
                                checksum += v;
                            }
                        }
                        if (finite == 0 || !Double.isFinite(checksum)) {
                            throw new IllegalStateException("Encoder output is not finite");
                        }

                        result =
                                "PASS — ivrit.ai encoder המלא רץ דרך נתיב NPU/TPU\n"
                                        + "זמן טעינה: " + loadMs + " ms\n"
                                        + "זמן encoder: " + runMs + " ms\n"
                                        + "output floats: " + encoded.length + "\n"
                                        + "sample checksum: " + checksum + "\n\n"
                                        + "זה עדיין gate ללא מיקרופון וללא decoder.";
                    } finally {
                        for (TensorBuffer b : outputs) b.close();
                        for (TensorBuffer b : inputs) b.close();
                    }
                }
            }
        } catch (Throwable e) {
            result = "FAIL — " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        send(reply, 3, result);
        main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
    }

    @Override public IBinder onBind(Intent intent) {
        // Ensure the adb destination exists as soon as the app binds this service.
        getExternalFilesDir(null);
        return binder.getBinder();
    }

    @Override public boolean onUnbind(Intent intent) {
        android.os.Process.killProcess(android.os.Process.myPid());
        return false;
    }
}
