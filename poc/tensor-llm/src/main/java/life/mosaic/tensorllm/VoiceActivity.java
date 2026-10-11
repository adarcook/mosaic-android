package life.mosaic.tensorllm;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Foreground push-to-talk. One sequential turn, with durable stage events and isolated engines. */
public final class VoiceActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView asr,llm,tts,transcript,answer,status;private Button talk,stop,setup,export,archive;
    private ProgressBar asrProgress,llmProgress,ttsProgress;
    private volatile boolean recording;private boolean active,foreground,bound;
    private volatile AudioRecord recorder;
    private Messenger remote;private ServiceConnection connection;private int pid,bindToken;private volatile int turn;
    private long turnStart,stageStart;private String stage="idle",recognized="",response="";
    private File journal,audioFile;private Runnable deadline;
    private final Runnable clock=new Runnable(){public void run(){if(active){status.setText("שלב: "+label(stage)+" · "+((SystemClock.elapsedRealtime()-stageStart)/1000)+" שניות · כולל: "+((SystemClock.elapsedRealtime()-turnStart)/1000)+" שניות\n"+(journal==null?"":journal.getName()));main.postDelayed(this,1000);}}};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(24,80,24,32);
        TextView title=new TextView(this);title.setText("מוזאיק — דבר וקבל תשובה\nלחץ כדי לדבר, ואז סיים משפט. עד 12 שניות בכל פנייה.");title.setTextSize(22);layout.addView(title);
        setup=new Button(this);setup.setText("הכנת הקול BlueTTS");setup.setOnClickListener(v->startActivity(new Intent(this,TtsSetupActivity.class)));layout.addView(setup);
        archive=new Button(this);archive.setText("מודל LLM ותמלילים שמורים");archive.setOnClickListener(v->startActivity(new Intent(this,LlmActivity.class)));layout.addView(archive);
        talk=new Button(this);talk.setText("דבר עם מוזאיק");talk.setOnClickListener(v->requestMic());layout.addView(talk);
        stop=new Button(this);stop.setText("סיום משפט / ביטול");stop.setOnClickListener(v->{if(recording)stopMic();else if(active)finish("cancelled","הפעולה בוטלה");});stop.setEnabled(false);layout.addView(stop);
        asr=card(layout,"1 · תמלול — ממתין");asrProgress=progress(layout);transcript=card(layout,"");
        llm=card(layout,"2 · תשובת מודל — ממתין");llmProgress=progress(layout);answer=card(layout,"");
        tts=card(layout,"3 · הקראה — ממתין");ttsProgress=progress(layout);status=card(layout,"");
        export=new Button(this);export.setText("ייצוא יומן השיחה האחרונה");export.setOnClickListener(v->{if(journal!=null&&journal.isFile())startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/x-ndjson").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,journal.getName()),72);});layout.addView(export);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        String previous=getPreferences(0).getString("journal","");if(!previous.isEmpty()){File file=new File(new File(getFilesDir(),"voice-turns"),previous);if(file.isFile())journal=file;}
        transcript.setText(getPreferences(0).getString("transcript",""));answer.setText(getPreferences(0).getString("answer",""));status.setText(getPreferences(0).getString("result","כל שלב מוצג בנפרד. התמליל, התשובה והיומן נשמרים מקומית."));
        if(getPreferences(0).getBoolean("active",false)&&journal!=null){status.setText("השיחה הקודמת הופרעה. היומן והאודיו שכבר נשמרו נשארו במכשיר.\n"+journal.getName());getPreferences(0).edit().putBoolean("active",false).apply();}
    }
    private TextView card(LinearLayout layout,String text){TextView view=new TextView(this);view.setText(text);view.setTextSize(17);view.setTextIsSelectable(true);view.setPadding(12,20,12,20);layout.addView(view);return view;}
    private ProgressBar progress(LinearLayout layout){ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setIndeterminate(true);bar.setVisibility(android.view.View.GONE);layout.addView(bar);return bar;}
    private void requestMic(){if(active||!foreground)return;if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},73);return;}begin();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==73){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&foreground)begin();else status.setText("לא אושרה הרשאת מיקרופון");}}
    private void begin(){if(active||!foreground)return;
        try{
            if(new File(getExternalFilesDir(null),ModelSpec.FILE).length()!=ModelSpec.BYTES)throw new IOException("הכן את מודל Gemma במסך המודלים תחילה");
            if(!new File(getExternalFilesDir(null),"blue-voice/manifest.json").isFile())throw new IOException("הכן את חבילת BlueTTS דרך ‘הכנת הקול’ תחילה");
            File folder=new File(getFilesDir(),"voice-turns");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("לא ניתן לשמור שיחה");
            String id="voice-"+System.currentTimeMillis()+"-"+java.util.UUID.randomUUID();journal=new File(folder,id+".jsonl");audioFile=new File(folder,id+".pcm");
            turnStart=SystemClock.elapsedRealtime();active=true;turn++;recognized="";response="";transcript.setText("");answer.setText("");
            asr.setText("1 · תמלול — ממתין");llm.setText("2 · תשובת מודל — ממתין");tts.setText("3 · הקראה — ממתין");
            event(new JSONObject().put("type","start").put("experiment","ASR-22 / VOICE-01").put("build",BuildConfig.VERSION_NAME).put("android_fingerprint",Build.FINGERPRINT).put("sample_rate",16000).put("beam",5).put("llm_model_sha256",ModelSpec.SHA256).put("dispatch_sha256",ModelSpec.DISPATCH_SHA256).put("tts_renikud_revision",TtsPack.REV).put("mode","foreground_push_to_talk"));
            if(!getPreferences(0).edit().putBoolean("active",true).putString("journal",journal.getName()).putString("transcript","").putString("answer","").putString("result","השיחה התחילה ללא תוצאה סופית").commit())throw new IOException("לא ניתן לשמור מצב שיחה");
            controls(false);getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);transition("capture","מקשיב…");
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)throw new IOException("המיקרופון אינו זמין");
            AudioRecord mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,8192));
            if(mic.getState()!=AudioRecord.STATE_INITIALIZED){mic.release();throw new IOException("המיקרופון לא נפתח");}
            recorder=mic;recording=true;stop.setText("סיום משפט");int idTurn=turn;File target=audioFile;
            main.postDelayed(()->{if(active&&turn==idTurn&&recording)stopMic();},12000);
            new Thread(()->capture(mic,target,idTurn),"mosaic-voice-capture").start();
        }catch(Exception e){if(active)finish("error",e.getMessage());else status.setText(e.getMessage());}
    }
    private void capture(AudioRecord mic,File target,int idTurn){
        byte[] pcm=null;Throwable failure=null;
        try(FileOutputStream output=new FileOutputStream(target)){
            if(!recording||turn!=idTurn)return;mic.startRecording();short[] block=new short[1024];ByteBuffer bytes=ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);int count=0;
            while(recording&&turn==idTurn&&count<192000){int n=mic.read(block,0,Math.min(block.length,192000-count),AudioRecord.READ_BLOCKING);if(n<=0){if(!recording)break;throw new IOException("Microphone read failed: "+n);}bytes.clear();for(int i=0;i<n;i++)bytes.putShort(block[i]);output.write(bytes.array(),0,n*2);count+=n;}
            output.getFD().sync();if(target.length()<16000)throw new IOException("דבר לפחות חצי שנייה");pcm=java.nio.file.Files.readAllBytes(target.toPath());
        }catch(Throwable e){failure=e;}finally{try{mic.stop();}catch(RuntimeException ignored){}mic.release();if(recorder==mic)recorder=null;}
        byte[] captured=pcm;Throwable error=failure;main.post(()->{if(!active||turn!=idTurn)return;recording=false;if(error!=null){finish("error",error.toString());return;}
            try{event(new JSONObject().put("type","capture_end").put("audio_file",target.getName()).put("audio_sha256",life.mosaic.models.ModelCopy.sha256(target,()->false)).put("audio_seconds",captured.length/32000.0));transition("asr","מתמלל…");Bundle data=new Bundle();data.putByteArray("pcm16",captured);bind(new Intent().setComponent(new ComponentName("life.mosaic.tensorwhisper","life.mosaic.tensorwhisper.VoiceAsrService")),data,120000);}
            catch(Exception e){finish("error",e.toString());}
        });
    }
    private void stopMic(){recording=false;AudioRecord mic=recorder;if(mic!=null)try{mic.stop();}catch(RuntimeException ignored){}stop.setText("ביטול הפעולה");}
    private void transition(String next,String text)throws Exception{
        stage=next;stageStart=SystemClock.elapsedRealtime();event(new JSONObject().put("type","phase").put("stage",stage).put("state","running"));
        TextView card=stage.equals("capture")||stage.equals("asr")?asr:stage.equals("llm")?llm:tts;card.setText(label(stage)+" · "+text);card.setTextColor(Color.rgb(35,85,180));main.removeCallbacks(clock);main.post(clock);
        asrProgress.setVisibility(stage.equals("capture")||stage.equals("asr")?android.view.View.VISIBLE:android.view.View.GONE);llmProgress.setVisibility(stage.equals("llm")?android.view.View.VISIBLE:android.view.View.GONE);ttsProgress.setVisibility(stage.equals("tts")?android.view.View.VISIBLE:android.view.View.GONE);
        android.util.Log.i("MosaicVoice","stage="+stage+" state=running turn="+turn);stop.setText(recording?"סיום משפט":"ביטול הפעולה");
    }
    private void bind(Intent intent,Bundle data,long budget){final int token=++bindToken;final int id=turn;final String expected=stage;
        Messenger reply=new Messenger(new Handler(Looper.getMainLooper(),msg->{if(!active||turn!=id||bindToken!=token||!stage.equals(expected))return true;
            if(msg.what==1)pid=msg.arg1;else if(msg.what==4)completeStage(msg.getData());else if(msg.what==5)finish("error",msg.getData().getString("text","שגיאה"));
            else if(msg.what==3&&expected.equals("llm"))answer.setText(msg.getData().getString("text",""));
            else {String phase=msg.getData().getString("text","");TextView card=expected.equals("asr")?asr:expected.equals("llm")?llm:tts;card.setText(label(expected)+" · "+phase);try{event(new JSONObject().put("type","phase_detail").put("stage",expected).put("phase",phase));}catch(Exception e){finish("error","לא ניתן לשמור יומן: "+e.getMessage());}}
            return true;}));
        connection=new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder binder){if(!active||turn!=id||bindToken!=token)return;remote=new Messenger(binder);android.os.Message request=android.os.Message.obtain(null,1);request.replyTo=reply;request.setData(data);try{remote.send(request);}catch(RemoteException e){finish("error","חיבור למנוע נסגר");}}
            public void onServiceDisconnected(ComponentName name){failed("תהליך המנוע נסגר");}public void onBindingDied(ComponentName name){failed("חיבור למנוע אבד");}public void onNullBinding(ComponentName name){failed("לא ניתן לפתוח מנוע");}private void failed(String text){if(active&&turn==id&&bindToken==token)finish("error",text);}};
        deadline=()->{if(active&&turn==id&&bindToken==token)finish("error","TIMEOUT — "+label(expected));};main.postDelayed(deadline,budget);
        try{bound=bindService(intent,connection,BIND_AUTO_CREATE);if(!bound)finish("error","המנוע אינו מותקן או אינו זמין. עדכן את שתי האפליקציות.");}catch(RuntimeException e){finish("error",e.toString());}
    }
    private void completeStage(Bundle data){String completed=stage;long elapsed=SystemClock.elapsedRealtime()-stageStart;
        try{
            JSONObject result=new JSONObject().put("type","stage_end").put("stage",stage).put("state","complete").put("elapsed_ms",elapsed);
            for(String key:data.keySet())if(!key.equals("text"))result.put(key,data.get(key));event(result);
            TextView card=stage.equals("asr")?asr:stage.equals("llm")?llm:tts;card.setText(label(stage)+" · הושלם · "+elapsed+" ms");card.setTextColor(Color.rgb(25,120,65));
            dispose();
            if(completed.equals("asr")){recognized=data.getString("transcript","");if(recognized.isBlank())throw new IOException("תמלול ריק");transcript.setText("אמרת: "+recognized);getPreferences(0).edit().putString("transcript",recognized).apply();transition("llm","פותח מודל…");Bundle request=new Bundle();request.putString("text",recognized);request.putBoolean("voice",true);request.putString("source_sha256",life.mosaic.models.ModelCopy.sha256(audioFile,()->false));bind(new Intent(this,LlmService.class),request,240000);}
            else if(completed.equals("llm")){response=data.getString("answer","");if(response.isBlank())throw new IOException("תשובה ריקה");answer.setText("מוזאיק: "+response);getPreferences(0).edit().putString("answer",response).apply();transition("tts","מכין הקראה…");Bundle request=new Bundle();request.putString("answer",response);bind(new Intent(this,TtsService.class),request,240000);}
            else finish("complete","השיחה וההקראה הסתיימו");
        }catch(Exception e){finish("error",e.toString());}
    }
    private void dispose(){bindToken++;if(deadline!=null)main.removeCallbacks(deadline);if(pid>0){android.os.Process.killProcess(pid);pid=0;}else if(remote!=null)try{remote.send(android.os.Message.obtain(null,9));}catch(RemoteException ignored){}remote=null;if(bound){try{unbindService(connection);}catch(IllegalArgumentException ignored){}bound=false;}}
    private void finish(String state,String text){if(!active)return;String failedStage=stage;active=false;turn++;stopMic();dispose();main.removeCallbacks(clock);
        try{event(new JSONObject().put("type","end").put("state",state).put("stage",failedStage).put("elapsed_ms",SystemClock.elapsedRealtime()-turnStart).put("message",text));}catch(Exception e){text+="\nלא ניתן לשמור יומן: "+e.getMessage();}
        if(!state.equals("complete")){TextView card=failedStage.equals("capture")||failedStage.equals("asr")?asr:failedStage.equals("llm")?llm:tts;card.setText(label(failedStage)+" · "+text);card.setTextColor(Color.rgb(170,45,45));}
        asrProgress.setVisibility(android.view.View.GONE);llmProgress.setVisibility(android.view.View.GONE);ttsProgress.setVisibility(android.view.View.GONE);
        String result=text+"\n"+(journal==null?"":"נשמר: "+journal.getName());status.setText(result);getPreferences(0).edit().putBoolean("active",false).putString("result",result).putString("journal",journal==null?"":journal.getName()).apply();stage="idle";controls(true);getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void controls(boolean idle){talk.setEnabled(idle);setup.setEnabled(idle);archive.setEnabled(idle);export.setEnabled(idle);stop.setEnabled(!idle);}
    private void event(JSONObject value)throws Exception{value.put("at_ms",System.currentTimeMillis()).put("since_start_ms",SystemClock.elapsedRealtime()-turnStart);try(FileOutputStream output=new FileOutputStream(journal,true)){output.write((value+"\n").getBytes(StandardCharsets.UTF_8));output.getFD().sync();}}
    private static String label(String stage){return switch(stage){case "capture"->"1 · הקלטה";case "asr"->"1 · תמלול";case "llm"->"2 · תשובת מודל";case "tts"->"3 · הקראה";default->"שיחה";};}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=72||result!=RESULT_OK||data==null||data.getData()==null||journal==null)return;
        try(InputStream input=new FileInputStream(journal);OutputStream output=getContentResolver().openOutputStream(data.getData())){if(output==null)throw new IOException("יעד אינו זמין");input.transferTo(output);status.setText("יומן השיחה יוצא. הוא כולל תמליל ותשובה אישיים.");}catch(Exception e){status.setText("הייצוא נכשל: "+e.getMessage());}}
    @Override protected void onResume(){super.onResume();foreground=true;}
    @Override protected void onPause(){foreground=false;if(active)finish("cancelled","השיחה נעצרה במעבר לרקע; הנתונים שכבר נשמרו נשארו במכשיר");super.onPause();}
}
