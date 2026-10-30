package life.mosaic.tensorllm;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/** API 31+ route foundation. Not yet wired into wake IPC or enabled in the product. */
final class AndroidHeadsetRoute implements AutoCloseable {
    static final long SELECTION_TIMEOUT_MS = 5000;
    private final AudioManager audio;
    private final Handler handler;
    private final HeadsetRouteLease lease;
    private AudioFocusRequest focus;
    private AudioManager.OnCommunicationDeviceChangedListener listener;
    private Runnable timeout;
    private boolean modeOwned, selectionRequested;
    private int oldMode;

    AndroidHeadsetRoute(AudioManager audio, Handler handler, Runnable stopAudio) {
        this.audio = audio;
        this.handler = handler;
        lease = new HeadsetRouteLease(new HeadsetRouteLease.Port() {
            public boolean acquireFocus() {
                // Do not take communication mode from a phone call or another communication app.
                oldMode = audio.getMode();
                if (oldMode != AudioManager.MODE_NORMAL) return false;
                long ticket = lease.generation();
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes()).setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change != AudioManager.AUDIOFOCUS_GAIN) lease.interrupted(ticket);
                    }, handler).build();
                if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false;
                modeOwned = true;
                audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
                return true;
            }
            public boolean select(int sinkId) {
                AudioDeviceInfo sink = null;
                for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices())
                    if (device.getId() == sinkId && headset(device)) sink = device;
                if (sink == null) return false;
                long ticket = lease.generation();
                listener = device -> lease.selected(ticket, device == null ? -1 : device.getId());
                audio.addOnCommunicationDeviceChangedListener(handler::post, listener);
                selectionRequested = true;
                if (!audio.setCommunicationDevice(sink)) return false;
                // Queue the snapshot so start() has completed before the callback is processed.
                handler.post(() -> {
                    AudioDeviceInfo current = audio.getCommunicationDevice();
                    lease.selected(ticket, current == null ? -1 : current.getId());
                });
                timeout = () -> lease.timeout(ticket);
                handler.postDelayed(timeout, SELECTION_TIMEOUT_MS);
                return true;
            }
            public void release() { releasePlatform(); }
        }, stopAudio);
    }
    static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }
    static boolean headset(AudioDeviceInfo device) {
        return device.isSink() && (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }
    void start(int sinkId) { checkThread(); lease.start(sinkId); }
    HeadsetRouteLease.State state() { checkThread(); return lease.state(); }
    @Override public void close() { checkThread(); lease.close(); }
    private void checkThread() {
        if (Looper.myLooper() != handler.getLooper()) throw new IllegalStateException("Wrong route thread");
    }
    private void releasePlatform() {
        if (timeout != null) { handler.removeCallbacks(timeout); timeout = null; }
        // Each cleanup must run even if Android rejects another operation after revocation.
        try {
            if (listener != null) audio.removeOnCommunicationDeviceChangedListener(listener);
        } finally {
            listener = null;
            try {
                if (selectionRequested) audio.clearCommunicationDevice();
            } finally {
                selectionRequested = false;
                try {
                    if (modeOwned && audio.getMode() == AudioManager.MODE_IN_COMMUNICATION) audio.setMode(oldMode);
                } finally {
                    modeOwned = false;
                    if (focus != null) { AudioFocusRequest held = focus; focus = null; audio.abandonAudioFocusRequest(held); }
                }
            }
        }
    }
}
