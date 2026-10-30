package life.mosaic.tensorllm;

import android.content.Context;
import android.media.*;
import android.os.*;

/** Small verified PCM cues; no synthesis/model loading on the interaction path. */
final class VoiceFeedback {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile int generation;
    private volatile Thread worker;
    private volatile AudioTrack track;
    private volatile life.mosaic.audio.HeadsetAudio headset;
    private volatile boolean headsetRequired;
    void headset(life.mosaic.audio.HeadsetAudio value,boolean required){headset=value;headsetRequired=required;}

    VoiceFeedback(Context context){this.context=context.getApplicationContext();}
    boolean playing(){Thread current=worker;return current!=null&&current.isAlive();}
    void errorTone(Runnable done){
        cancel();final int token=generation;
        if(headsetRequired){done.run();return;}
        try{ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,60);tone.startTone(ToneGenerator.TONE_PROP_NACK,120);main.postDelayed(()->{tone.release();if(token==generation)done.run();},160);}
        catch(Exception unavailable){done.run();}
    }
    void play(String id,java.util.function.Consumer<Boolean> done){
        play(id,at->{},done);
    }
    void play(String id,java.util.function.LongConsumer started,java.util.function.Consumer<Boolean> done){
        play(id,started,()->{},done);
    }
    void play(String id,java.util.function.LongConsumer started,Runnable drained,java.util.function.Consumer<Boolean> done){
        cancel();final int token=generation;final life.mosaic.audio.HeadsetAudio route=headset;final boolean required=headsetRequired;
        worker=new Thread(()->{
            boolean success=false;AudioTrack output=null;life.mosaic.audio.HeadsetAudio.Playback guard=null;
            try{
                if(required&&route==null)throw new java.io.IOException("Headset route unavailable");
                FeedbackClips.Clip clip;
                if(id.equals("ack")){float[] tone=new float[1280];for(int n=0;n<tone.length;n++)tone[n]=(float)(.16*Math.sin(2*Math.PI*880*n/16000)*Math.sin(Math.PI*n/tone.length));clip=new FeedbackClips.Clip(16000,tone);}
                else clip=FeedbackClips.read(context.getFilesDir(),id);
                int minimum=AudioTrack.getMinBufferSize(clip.rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT);
                if(minimum<1||token!=generation)return;
                output=new AudioTrack.Builder().setAudioAttributes((route==null?new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build():route.attributes()))
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(clip.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes((route==null?Math.max(minimum,clip.rate*4):Math.max(minimum*2,clip.rate/5))).setTransferMode(AudioTrack.MODE_STREAM).build();
                synchronized(this){if(token!=generation)return;track=output;}
                if(route!=null)guard=route.guard(context.getSystemService(AudioManager.class),output);
                final life.mosaic.audio.HeadsetAudio.Playback protectedOutput=guard;
                output.setVolume(1f);final AudioTrack active=output;
                PlaybackLoop.play(clip.pcm,clip.rate,new PlaybackLoop.Output(){
                    public int start(int requested){int value=active.setStartThresholdInFrames(requested);active.play();try{if(protectedOutput!=null)protectedOutput.started();}catch(Exception e){throw new IllegalStateException(e);}return value;}
                    public int write(float[] audio,int offset,int count){try{if(protectedOutput!=null)protectedOutput.check();}catch(Exception e){throw new IllegalStateException(e);}return active.write(audio,offset,count,AudioTrack.WRITE_NON_BLOCKING);}
                    public long head(){try{if(protectedOutput!=null)protectedOutput.check();}catch(Exception e){throw new IllegalStateException(e);}return protectedOutput==null?Integer.toUnsignedLong(active.getPlaybackHeadPosition()):protectedOutput.head();}
                    public void started(long at){main.post(()->{if(token==generation)started.accept(at);});}
                },new PlaybackLoop.Clock(){public long now(){return SystemClock.elapsedRealtime();}public void pause()throws InterruptedException{Thread.sleep(10);}});
                if(token==generation)drained.run();
                success=true;
            }catch(Exception ignored){}
            finally{
                if(guard!=null)guard.close();
                if(output!=null){try{output.stop();}catch(Exception ignored){}output.release();synchronized(this){if(track==output)track=null;}}
                if(Thread.currentThread()==worker)worker=null;
                final boolean result=success;
                main.post(()->{if(token==generation)done.accept(result);});
            }
        },"mosaic-fixed-feedback");worker.start();
    }
    synchronized void cancel(){generation++;Thread previous=worker;worker=null;if(previous!=null)previous.interrupt();AudioTrack output=track;track=null;if(output!=null)try{output.stop();}catch(Exception ignored){}}
}
