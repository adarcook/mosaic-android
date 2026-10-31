package life.mosaic.audio;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Worker-thread lease owned by the same UID that records standby audio. */
public final class StandbyHeadsetRoute implements AutoCloseable {
    private final AudioManager audio;
    private final Handler main=new Handler(Looper.getMainLooper());
    private AudioFocusRequest focus;
    private AudioManager.OnCommunicationDeviceChangedListener listener;
    private boolean modeOwned,selectionRequested;
    private volatile boolean lost,closed;
    private int sinkId;
    public StandbyHeadsetRoute(Context context){audio=context.getSystemService(AudioManager.class);}
    public HeadsetAudio open(int requestedSink,BooleanSupplier running)throws Exception {
        if(audio.getMode()!=AudioManager.MODE_NORMAL)throw new IOException("Communication busy");
        AudioDeviceInfo sink=null;
        for(AudioDeviceInfo device:audio.getAvailableCommunicationDevices())
            if(device.isSink()&&device.getId()==requestedSink&&HeadsetAudio.headset(device.getType()))sink=device;
        if(sink==null)throw new IOException("Headset unavailable");
        sinkId=sink.getId();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(false).setOnAudioFocusChangeListener(value->{if(value!=AudioManager.AUDIOFOCUS_GAIN)lost=true;},main).build();
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IOException("Headset focus denied");
        modeOwned=true;audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        selectionRequested=true;
        if(!audio.setCommunicationDevice(sink))throw new IOException("Headset selection denied");
        long end=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<end&&running.getAsBoolean()&&!lost){
            AudioDeviceInfo selected=audio.getCommunicationDevice();
            if(selected!=null&&selected.getId()==sinkId){
                AudioDeviceInfo input=null;
                for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_INPUTS))if(device.isSource()&&device.getType()==sink.getType()){
                    if(input!=null)throw new IOException("Ambiguous headset input");input=device;
                }
                if(input!=null){
                    listener=device->{if(!closed&&(device==null||device.getId()!=sinkId))lost=true;};
                    audio.addOnCommunicationDeviceChangedListener(main::post,listener);
                    return new HeadsetAudio(sinkId,input.getId(),sink.getType());
                }
            }
            Thread.sleep(20);
        }
        throw new IOException("Headset setup interrupted or timed out");
    }
    public void requireHealthy()throws IOException{if(lost||closed)throw new IOException("Standby headset route lost");}
    public void acknowledge(HeadsetAudio headset)throws Exception{
        requireHealthy();float[] tone=new float[1280];
        for(int n=0;n<tone.length;n++)tone[n]=(float)(.16*Math.sin(2*Math.PI*880*n/16000)*Math.sin(Math.PI*n/tone.length));
        int minimum=AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT);
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(headset.attributes())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum*2,3200)).build();
        HeadsetAudio.Playback guard=null;
        try{
            guard=headset.guard(audio,track);track.setStartThresholdInFrames(320);track.play();guard.started();
            long end=SystemClock.elapsedRealtime()+3000;int offset=0;
            while(offset<tone.length||guard.head()<tone.length){
                requireHealthy();guard.check();if(SystemClock.elapsedRealtime()>end)throw new IOException("Acknowledgement timeout");
                if(offset<tone.length){int n=track.write(tone,offset,tone.length-offset,AudioTrack.WRITE_NON_BLOCKING);if(n<0)throw new IOException("Acknowledgement output failed");offset+=n;}
                Thread.sleep(5);
            }
        }finally{if(guard!=null)guard.close();try{track.stop();}catch(RuntimeException ignored){}track.release();}
    }
    @Override public void close(){
        closed=true;
        try{if(listener!=null)audio.removeOnCommunicationDeviceChangedListener(listener);}finally{
            listener=null;
            try{if(selectionRequested)audio.clearCommunicationDevice();}finally{
                selectionRequested=false;
                try{if(modeOwned&&audio.getMode()==AudioManager.MODE_IN_COMMUNICATION)audio.setMode(AudioManager.MODE_NORMAL);}finally{
                    modeOwned=false;if(focus!=null){AudioFocusRequest held=focus;focus=null;audio.abandonAudioFocusRequest(held);}
                }
            }
        }
    }
}
