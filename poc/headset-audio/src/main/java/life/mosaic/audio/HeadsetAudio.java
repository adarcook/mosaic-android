package life.mosaic.audio;

import android.content.Context;
import android.media.*;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.IOException;

/** Transient signed-IPC route contract. No device address or audio is persisted. */
public final class HeadsetAudio {
    public final int sinkId, inputId, type;
    public HeadsetAudio(int sinkId, int inputId, int type) {
        if (sinkId <= 0 || inputId <= 0 || !headset(type)) throw new IllegalArgumentException("Invalid headset route");
        this.sinkId=sinkId; this.inputId=inputId; this.type=type;
    }
    public static boolean headset(int type) { return type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type==AudioDeviceInfo.TYPE_BLE_HEADSET; }
    public static HeadsetAudio read(Bundle data) {
        return data.getBoolean("headset_required",false) ? new HeadsetAudio(data.getInt("headset_sink",-1),data.getInt("headset_input",-1),data.getInt("headset_type",-1)) : null;
    }
    public void put(Bundle data) {
        data.putBoolean("headset_required",true);data.putInt("headset_sink",sinkId);data.putInt("headset_input",inputId);data.putInt("headset_type",type);
    }
    public AudioAttributes attributes() { return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(); }
    public void requireSelected(AudioManager manager) throws IOException {
        AudioDeviceInfo sink=manager.getCommunicationDevice();
        if(sink==null || sink.getId()!=sinkId || sink.getType()!=type) throw new IOException("Headset communication route lost");
    }
    public void prefer(AudioManager manager, AudioRecord recorder) throws IOException {
        requireSelected(manager);
        for(AudioDeviceInfo input:manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if(input.getId()==inputId && input.getType()==type && input.isSource()) {
                if(!recorder.setPreferredDevice(input)) throw new IOException("Headset input refused");return;
            }
        throw new IOException("Headset input unavailable");
    }
    public void requireInput(AudioManager manager, AudioRecord recorder) throws IOException {
        requireSelected(manager);AudioDeviceInfo input=recorder.getRoutedDevice();
        if(input==null || !HeadsetEvidence.input(inputId,type,input.getId(),input.getType(),recorder.getActiveRecordingConfiguration()!=null,recorder.getActiveRecordingConfiguration()==null || recorder.getActiveRecordingConfiguration().isClientSilenced()))
            throw new IOException("Headset microphone not verified");
    }
    // Discard all pre-verification frames; no ready cue or retained request precedes this boundary.
    public void awaitInput(AudioManager manager, AudioRecord recorder) throws Exception {
        long end=SystemClock.elapsedRealtime()+3000;short[] discard=new short[320];
        while(SystemClock.elapsedRealtime()<end) {
            requireSelected(manager);
            if(recorder.read(discard,0,discard.length,AudioRecord.READ_NON_BLOCKING)<0)throw new IOException("Headset microphone read failed");
            try { requireInput(manager,recorder);return; } catch(IOException pending) { Thread.sleep(20); }
        }
        throw new IOException("Headset microphone verification timed out");
    }
    public Playback guard(AudioManager manager, AudioTrack track) throws Exception { return new Playback(manager,track); }
    public final class Playback implements AutoCloseable {
        private final AudioManager manager;private final AudioTrack track;
        private volatile boolean lost, verified;
        private int silentFrames;
        private final AudioRouting.OnRoutingChangedListener listener;
        Playback(AudioManager manager,AudioTrack track)throws Exception {
            this.manager=manager;this.track=track;requireSelected(manager);
            AudioDeviceInfo target=null;
            for(AudioDeviceInfo device:manager.getAvailableCommunicationDevices())if(device.getId()==sinkId&&device.getType()==type)target=device;
            if(target==null||!track.setPreferredDevice(target))throw new IOException("Headset output unavailable");
            listener=router->{AudioDeviceInfo actual=track.getRoutedDevice();if(verified&&(actual==null||actual.getId()!=sinkId)){lost=true;try{track.pause();track.flush();}catch(RuntimeException ignored){}}};
            track.addOnRoutingChangedListener(listener,new android.os.Handler(android.os.Looper.getMainLooper()));
        }
        public void started()throws Exception {
            // Silence establishes AudioTrack routing before any speech is submitted.
            long end=SystemClock.elapsedRealtime()+2000;float[] silence=new float[160];
            while(SystemClock.elapsedRealtime()<end){
                requireSelected(manager);
                AudioDeviceInfo actual=track.getRoutedDevice();
                if(actual!=null&&actual.getId()==sinkId&&actual.getType()==type){verified=true;check();return;}
                if(lost)throw new IOException("Headset output changed");
                int written=track.write(silence,0,silence.length,AudioTrack.WRITE_NON_BLOCKING);
                if(written<0)throw new IOException("Headset output failed");silentFrames+=written;
                Thread.sleep(10);
            }
            throw new IOException("Headset output verification timed out");
        }
        public void check()throws IOException {
            requireSelected(manager);AudioDeviceInfo actual=track.getRoutedDevice();
            if(lost||actual==null||!HeadsetEvidence.output(sinkId,type,manager.getCommunicationDevice()==null?-1:manager.getCommunicationDevice().getId(),actual.getId(),actual.getType()))throw new IOException("Headset playback route lost");
        }
        public long head(){return Math.max(0,Integer.toUnsignedLong(track.getPlaybackHeadPosition())-silentFrames);}
        public void close(){track.removeOnRoutingChangedListener(listener);}
    }
}
