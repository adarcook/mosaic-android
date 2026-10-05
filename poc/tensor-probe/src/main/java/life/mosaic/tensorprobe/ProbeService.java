package life.mosaic.tensorprobe;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import com.google.ai.edge.litert.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Same UID, separate disposable process. No microphone, networking, or CPU fallback. */
public final class ProbeService extends Service {
    private boolean started;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger binder = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == 9) { android.os.Process.killProcess(android.os.Process.myPid()); return true; }
        if (msg.what != 1 || started || msg.replyTo == null) return true;
        started = true;
        Messenger reply = msg.replyTo;
        Message pid = Message.obtain(null, 1); pid.arg1 = android.os.Process.myPid();
        try { reply.send(pid); } catch (RemoteException e) { android.os.Process.killProcess(android.os.Process.myPid()); return true; }
        Executors.newSingleThreadExecutor().execute(() -> runProbe(reply));
        return true;
    }));

    private void send(Messenger reply, int what, String text) {
        Message msg = Message.obtain(null, what);
        Bundle data = new Bundle(); data.putString("text", text); msg.setData(data);
        try { reply.send(msg); } catch (RemoteException e) { android.os.Process.killProcess(android.os.Process.myPid()); }
    }

    private void runProbe(Messenger reply) {
        String result;
        try (Environment env = Environment.create(Collections.singletonMap(
                Environment.Option.DispatchLibraryDir, getApplicationInfo().nativeLibraryDir))) {
            Set<Accelerator> available = env.getAvailableAccelerators();
            send(reply, 2, "מאיצים זמינים: " + available);
            if (!available.contains(Accelerator.NPU)) throw new IllegalStateException("NPU unavailable: " + available);
            long start = SystemClock.elapsedRealtime();
            // The packaged model contains only a Google Tensor DISPATCH_OP;
            // it cannot execute using the ordinary CPU ADD kernel.
            try (CompiledModel model = CompiledModel.create(getAssets(), "probe_Google_Tensor_G5.tflite",
                    new CompiledModel.Options(Accelerator.NPU), env)) {
                long loadMs = SystemClock.elapsedRealtime() - start;
                send(reply, 2, "מודל TPU נטען; מריץ חישוב יחיד");
                List<TensorBuffer> inputs = model.createInputBuffers(0);
                List<TensorBuffer> outputs = new ArrayList<>();
                try {
                    outputs = model.createOutputBuffers(0);
                    if (inputs.size() != 2 || outputs.size() != 1) throw new IllegalStateException("Unexpected model IO");
                    inputs.get(0).writeFloat(new float[]{0,1,2,3,4,5,6,7});
                    inputs.get(1).writeFloat(new float[]{8,7,6,5,4,3,2,1});
                    model.run(inputs, outputs, 0);
                    float[] values = outputs.get(0).readFloat();
                    if (values.length != 8) throw new IllegalStateException("Unexpected output length " + values.length);
                    for (float v : values) if (!Float.isFinite(v) || Math.abs(v-8f) > 0.001f)
                        throw new IllegalStateException("Incorrect output: " + Arrays.toString(values));
                    result = "PASS — בדיקת TPU הצליחה\n" +
                        "NPU בלבד; חיבור 8 מספרים אומת.\nזמן טעינה: " + loadMs + " ms\n" +
                        "זו בדיקת תשתית בלבד, לא מדידת מהירות תמלול.";
                } finally {
                    for (TensorBuffer b : outputs) b.close();
                    for (TensorBuffer b : inputs) b.close();
                }
            }
        } catch (Throwable e) {
            result = "FAIL — " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        send(reply, 3, result);
        main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
    }

    @Override public IBinder onBind(Intent intent) { return binder.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { android.os.Process.killProcess(android.os.Process.myPid()); return false; }
}
