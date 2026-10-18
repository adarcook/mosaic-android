package life.mosaic.tensorllm;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Foreground warm session. Sequential turns, durable stages and isolated native runtimes. */
public final class VoiceActivity extends Activity {
    private VoiceScreen screen;
    private boolean handsfreeTurn, autoCapture, armOnResume, armingStandby;
    private VoiceFeedback feedback;
    private boolean locked(){return ((android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isDeviceLocked();}
    private final BroadcastReceiver handsfreeChanged=new BroadcastReceiver(){public void onReceive(Context c,Intent i){controls(!active);}};
    private void wakeSetup(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},75);return;}
        try{startActivityForResult(new Intent().setComponent(new ComponentName(HandsFreeController.COMPANION,HandsFreeController.COMPANION+".WakeSetupActivity")),78);}
        catch(Exception e){status.setText("נדרש עדכון חבילת Zipformer להפעלה בלי מגע");}
    }
    private void acceptWake(Intent intent){if(HandsFreeController.consume(intent)){handsfreeTurn=true;autoCapture=true;setShowWhenLocked(true);setTurnScreenOn(true);}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);acceptWake(intent);if(foreground&&autoCapture&&!active){autoCapture=false;readyCapture();}}

    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView asr,llm,tts,transcript,answer,status;private Button talk,stop,setup,export,archive,warm,notificationAccess,notificationCheck;private CheckBox beam2,zipformer;
    private ProgressBar asrProgress,llmProgress,ttsProgress;
    private volatile boolean recording;private boolean active,foreground,prewarming,prepared,toolTurn;
    private volatile AudioRecord recorder;
    private static final class Worker {Messenger remote;ServiceConnection connection;boolean bound;}
    private final java.util.Map<String,Worker> workers=new java.util.HashMap<>();
    private String sessionId=java.util.UUID.randomUUID().toString();
    private final Runnable idleRelease=()->{clearWorkers();controls(true);status.setText("המנועים שוחררו אחרי שתי דקות ללא פנייה. לחץ הכנת מנועים כדי להמשיך.");};
    private int bindToken;private volatile int turn;
    private long turnStart,stageStart;private int selectedBeam=life.mosaic.models.VoiceAsrBeam.BASELINE;private String selectedAsrEngine=life.mosaic.models.VoiceAsrBackend.WHISPER,stage="idle",recognized="",response="";
    private File journal,audioFile;private Runnable deadline;private WorkerRelease pendingRelease;
    private final Runnable clock=new Runnable(){public void run(){if(active){status.setText("שלב: "+label(stage)+" · "+((SystemClock.elapsedRealtime()-stageStart)/1000)+" שניות · כולל: "+((SystemClock.elapsedRealtime()-turnStart)/1000)+" שניות\n"+(journal==null?"":journal.getName()));screen.tick(SystemClock.elapsedRealtime()-turnStart);main.postDelayed(this,1000);}}};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);screen=new VoiceScreen(this);feedback=new VoiceFeedback(this);acceptWake(getIntent());registerReceiver(handsfreeChanged,new IntentFilter(HandsFreeController.CHANGED),Context.RECEIVER_NOT_EXPORTED);
        asr=screen.asr;llm=screen.llm;tts=screen.tts;transcript=screen.transcript;answer=screen.answer;status=screen.status;
        talk=screen.talk;stop=screen.stop;setup=screen.setup;export=screen.export;archive=screen.archive;warm=screen.warm;
        notificationAccess=screen.notificationAccess;notificationCheck=screen.notificationCheck;beam2=screen.beam2;zipformer=screen.zipformer;
        asrProgress=screen.asrProgress;llmProgress=screen.llmProgress;ttsProgress=screen.ttsProgress;
        setup.setOnClickListener(v->startActivity(new Intent(this,TtsSetupActivity.class)));
        archive.setOnClickListener(v->startActivity(new Intent(this,LlmActivity.class)));
        beam2.setChecked(getPreferences(0).getBoolean("voice_beam_2",false));
        beam2.setOnCheckedChangeListener((button,checked)->getPreferences(0).edit().putBoolean("voice_beam_2",checked).apply());
        zipformer.setChecked(getPreferences(0).getBoolean("voice_zipformer_opt_in",false));
        zipformer.setOnCheckedChangeListener((button,checked)->{
            getPreferences(0).edit().putBoolean("voice_zipformer_opt_in",checked).apply();
            if(prepared){main.removeCallbacks(idleRelease);clearWorkers();status.setText("המנוע השתנה. לחץ שוב 'הכנת מנועים לשיחה'.");}
            controls(!active);
        });
        notificationAccess.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        notificationCheck.setOnClickListener(v->{if(active||!foreground)return;
            WhatsAppNotificationTool.Result result=WhatsAppNotificationListener.readLatest(this);
            status.setText("בדיקת כלי: "+result.status.name()+
                (result.found()?" · נמצאה התראה. בדוק הקראה בפקודה קולית.":" · "+WhatsAppNotificationTool.spoken(result)));
        });
        warm.setOnClickListener(v->begin(true));
        talk.setOnClickListener(v->{if(prepared)requestMic();else begin(true);});
        stop.setOnClickListener(v->{if(recording)stopMic();else if(active)finish("cancelled","הפעולה בוטלה");});
        export.setOnClickListener(v->{if(journal!=null&&journal.isFile())startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/x-ndjson").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,journal.getName()),72);});controls(true);
        screen.assistantButton.setOnClickListener(v->startActivityForResult(new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),77));
        screen.handsfreeButton.setOnClickListener(v->{if(HandsFreeController.enabled())HandsFreeController.stop(this,"ההאזנה הופסקה לפי בקשה");else wakeSetup();});
        screen.wakeExport.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/x-ndjson").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"mosaic-handsfree-status.jsonl"),76));
        String previous=getPreferences(0).getString("journal","");if(!previous.isEmpty()){File file=new File(new File(getFilesDir(),"voice-turns"),previous);if(file.isFile())journal=file;}
        if(!locked()){transcript.setText(getPreferences(0).getString("transcript",""));answer.setText(getPreferences(0).getString("answer",""));status.setText(getPreferences(0).getString("result","כל שלב מוצג בנפרד. התמליל, התשובה והיומן נשמרים מקומית."));}
        if(getPreferences(0).getBoolean("active",false)&&journal!=null){status.setText("השיחה הקודמת הופרעה. היומן והאודיו שכבר נשמרו נשארו במכשיר.\n"+journal.getName());getPreferences(0).edit().putBoolean("active",false).apply();}
    }
    private void readyCapture(){
        if(!foreground||!handsfreeTurn||active)return;
        HandsFreeController.status(this,"משוב מוכן · אני כאן, דבר אליי");
        feedback.play("ready",ok->{if(!foreground||!handsfreeTurn||active)return;if(!ok){endHandsFree(false);return;}HandsFreeController.status(this,"מוכן לבקשה · אפשר לדבר עכשיו");main.postDelayed(()->{if(foreground&&handsfreeTurn&&!active)begin(false);},100);});
    }
    private void prepareStandby(){armingStandby=true;HandsFreeController.stop(this,"מכין הפעלה ללא מסך ומשוב קולי · הכנה חד־פעמית להפעלה");begin(true);if(!active){armingStandby=false;HandsFreeController.stop(this,"הכנת ההאזנה לא התחילה; יש לבדוק אבחון");}}
    private void speakPhase(String cue)throws Exception{
        if(!handsfreeTurn||prewarming)return;final int id=turn;long started=SystemClock.elapsedRealtime();
        event(new JSONObject().put("type","feedback_start").put("cue",cue));
        feedback.play(cue,ok->{if(!active||turn!=id)return;try{event(new JSONObject().put("type","feedback_end").put("cue",cue).put("played",ok).put("elapsed_ms",SystemClock.elapsedRealtime()-started));}catch(Exception e){finish("error","לא ניתן לתעד משוב קולי");return;}if(!ok)HandsFreeController.status(this,"משוב קולי לא הושמע · בדוק עוצמת מדיה ואבחון");});
    }
    private void endHandsFree(boolean success){
        handsfreeTurn=false;autoCapture=false;
        Runnable leave=()->{MosaicSessionService.close();setShowWhenLocked(false);setTurnScreenOn(false);if(locked())moveTaskToBack(true);};
        if(success){feedback.cancel();if(foreground&&HandsFreeController.requested())HandsFreeController.arm(this);leave.run();}
        else{HandsFreeController.stop(this,"הפנייה נעצרה · יש להפעיל האזנה שוב");feedback.play("error",ok->leave.run());}
    }
    private void requestMic(){if(active||!foreground)return;if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},73);return;}begin();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==75){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)wakeSetup();return;}if(code==73||code==74){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&foreground)begin(code==74);else{status.setText("לא אושרה הרשאת מיקרופון");screen.problem();controls(!active);}}}
    private void begin(){begin(false);}
    private void begin(boolean warming){if(active||!foreground)return;
        if(warming&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},74);return;}
        if(!warming&&!prepared&&!handsfreeTurn){status.setText("לחץ הכנת מנועים לפני הדיבור");return;}
        main.removeCallbacks(idleRelease);prewarming=warming;
        if(warming)clearWorkers();
        try{
            if(handsfreeTurn){sessionId=HandsFreeController.session();if(sessionId.isEmpty())throw new IOException("יש להפעיל האזנה מחדש");}
            if(new File(getExternalFilesDir(null),ModelSpec.FILE).length()!=ModelSpec.BYTES)throw new IOException("הכן את מודל Gemma במסך המודלים תחילה");
            if(!new File(getExternalFilesDir(null),"blue-voice/manifest.json").isFile())throw new IOException("הכן את חבילת BlueTTS דרך ‘הכנת הקול’ תחילה");
            File folder=new File(getFilesDir(),"voice-turns");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("לא ניתן לשמור שיחה");
            String id="voice-"+System.currentTimeMillis()+"-"+java.util.UUID.randomUUID();journal=new File(folder,id+".jsonl");audioFile=new File(folder,id+".pcm");
            turnStart=SystemClock.elapsedRealtime();selectedAsrEngine=zipformer.isChecked()?life.mosaic.models.VoiceAsrBackend.ZIPFORMER:life.mosaic.models.VoiceAsrBackend.WHISPER;selectedBeam=beam2.isChecked()?life.mosaic.models.VoiceAsrBeam.EXPERIMENT:life.mosaic.models.VoiceAsrBeam.BASELINE;active=true;toolTurn=false;turn++;recognized="";response="";transcript.setText("");answer.setText("");
            asr.setText("1 · תמלול — ממתין");llm.setText("2 · תשובת מודל — ממתין");tts.setText("3 · הקראה — ממתין");
            asr.setTextColor(VoiceScreen.MUTED);llm.setTextColor(VoiceScreen.MUTED);tts.setTextColor(VoiceScreen.MUTED);
            event(new JSONObject().put("type","start").put("experiment",selectedAsrEngine.equals(life.mosaic.models.VoiceAsrBackend.ZIPFORMER)?"ASR-30 / VOICE-07":"ASR-26 / VOICE-05").put("build",BuildConfig.VERSION_NAME).put("asr_engine",selectedAsrEngine).put("asr_package_version",getPackageManager().getPackageInfo(life.mosaic.models.VoiceAsrBackend.packageName(selectedAsrEngine),0).versionName).put("android_fingerprint",Build.FINGERPRINT).put("sample_rate",16000).put("beam",selectedBeam).put("llm_model_sha256",ModelSpec.SHA256).put("dispatch_sha256",ModelSpec.DISPATCH_SHA256).put("tts_renikud_revision",TtsPack.REV).put("interaction_experiment",handsfreeTurn||armingStandby?"ASR-34 / VOICE-12":"manual").put("standby_activation_prepare",armingStandby).put("activation",handsfreeTurn?"local_wake":"touch").put("mode",handsfreeTurn?(warming?"handsfree_prepare":"handsfree_request"):(warming?"foreground_sequential_prepare":"foreground_push_to_talk")).put("foreground_session",sessionId));
            if(!getPreferences(0).edit().putBoolean("active",true).putString("journal",journal.getName()).putString("transcript","").putString("answer","").putString("result","השיחה התחילה ללא תוצאה סופית").commit())throw new IOException("לא ניתן לשמור מצב שיחה");
            controls(false);getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if(warming){transition("asr","מכין מנוע תמלול…");Bundle data=new Bundle();data.putBoolean("prepare",true);bind(asrIntent(),data,120000);return;}
            transition("capture","מקשיב…");
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)throw new IOException("המיקרופון אינו זמין");
            AudioRecord mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,8192));
            if(mic.getState()!=AudioRecord.STATE_INITIALIZED){mic.release();throw new IOException("המיקרופון לא נפתח");}
            recorder=mic;recording=true;stop.setText("סיום משפט");int idTurn=turn;File target=audioFile;
            main.postDelayed(()->{if(active&&turn==idTurn&&recording)stopMic();},12000);
            new Thread(()->capture(mic,target,idTurn),"mosaic-voice-capture").start();
        }catch(Exception e){if(active)finish("error",e.getMessage());else{status.setText(e.getMessage());screen.problem();if(handsfreeTurn)endHandsFree(false);controls(true);}}
    }
    private void capture(AudioRecord mic,File target,int idTurn){
        byte[] pcm=null;Throwable failure=null;
        try(FileOutputStream output=new FileOutputStream(target)){
            if(!recording||turn!=idTurn)return;mic.startRecording();short[] block=new short[1024];ByteBuffer bytes=ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);int count=0;SpeechEndpoint endpoint=new SpeechEndpoint();
            while(recording&&turn==idTurn&&count<192000){int n=mic.read(block,0,Math.min(block.length,192000-count),AudioRecord.READ_BLOCKING);if(n<=0){if(!recording)break;throw new IOException("Microphone read failed: "+n);}bytes.clear();for(int i=0;i<n;i++)bytes.putShort(block[i]);output.write(bytes.array(),0,n*2);count+=n;if(handsfreeTurn&&endpoint.accept(block,n)){recording=false;break;}}
            output.getFD().sync();if(target.length()<16000)throw new IOException("דבר לפחות חצי שנייה");pcm=java.nio.file.Files.readAllBytes(target.toPath());
        }catch(Throwable e){failure=e;}finally{try{mic.stop();}catch(RuntimeException ignored){}mic.release();if(recorder==mic)recorder=null;}
        byte[] captured=pcm;Throwable error=failure;main.post(()->{if(!active||turn!=idTurn)return;recording=false;if(error!=null){finish("error",error.toString());return;}
            try{event(new JSONObject().put("type","capture_end").put("audio_file",target.getName()).put("audio_sha256",life.mosaic.models.ModelCopy.sha256(target,()->false)).put("audio_seconds",captured.length/32000.0));transition("asr","מתמלל…");Bundle data=new Bundle();data.putByteArray("pcm16",captured);data.putInt("beam_size",selectedBeam);bind(asrIntent(),data,120000);}
            catch(Exception e){finish("error",e.toString());}
        });
    }
    private Intent asrIntent(){return new Intent().setComponent(new ComponentName(
            life.mosaic.models.VoiceAsrBackend.packageName(selectedAsrEngine),
            life.mosaic.models.VoiceAsrBackend.serviceName(selectedAsrEngine)));}
    private void stopMic(){recording=false;AudioRecord mic=recorder;if(mic!=null)try{mic.stop();}catch(RuntimeException ignored){}stop.setText("ביטול הפעולה");}
    private void transition(String next,String text)throws Exception{
        if(handsfreeTurn&&!prewarming){if(next.equals("asr"))speakPhase("received");else if(next.equals("llm"))speakPhase("thinking");else if(next.equals("tts"))speakPhase("voicing");}
        stage=next;stageStart=SystemClock.elapsedRealtime();event(new JSONObject().put("type","phase").put("stage",stage).put("state","running"));
        TextView card=stage.equals("capture")||stage.equals("asr")?asr:stage.equals("llm")?llm:tts;card.setText(label(stage)+" · "+text);card.setTextColor(VoiceScreen.ACCENT);screen.session(prepared,active,recording,prewarming,stage);main.removeCallbacks(clock);main.post(clock);
        asrProgress.setVisibility(stage.equals("capture")||stage.equals("asr")?android.view.View.VISIBLE:android.view.View.GONE);llmProgress.setVisibility(stage.equals("llm")?android.view.View.VISIBLE:android.view.View.GONE);ttsProgress.setVisibility(stage.equals("tts")?android.view.View.VISIBLE:android.view.View.GONE);
        android.util.Log.i("MosaicVoice","stage="+stage+" state=running turn="+turn);stop.setText(recording?"סיום משפט":"ביטול הפעולה");
    }
    private void bind(Intent intent,Bundle data,long budget){data.putString("foreground_session",sessionId);final int token=++bindToken;final int id=turn;final String expected=stage;
        Worker worker=workers.get(expected);if(worker==null){worker=new Worker();workers.put(expected,worker);}final Worker selected=worker;
        Messenger reply=new Messenger(new Handler(Looper.getMainLooper(),msg->{if(!active||turn!=id||bindToken!=token||!stage.equals(expected))return true;
            if(msg.what==1){}else if(msg.what==4)completeStage(msg.getData());else if(msg.what==5){try{JSONObject failure=new JSONObject().put("type","stage_end").put("stage",expected).put("state","error").put("elapsed_ms",SystemClock.elapsedRealtime()-stageStart);for(String key:msg.getData().keySet())failure.put(key,msg.getData().get(key));event(failure);}catch(Exception e){finish("error","לא ניתן לשמור יומן: "+e.getMessage());return true;}finish("error",msg.getData().getString("text","שגיאה"));}
            else if(msg.what==3&&expected.equals("llm"))answer.setText(msg.getData().getString("text",""));
            else {if(msg.getData().getBoolean("speech_playback",false))feedback.cancel();String phase=msg.getData().getString("text","");TextView card=expected.equals("asr")?asr:expected.equals("llm")?llm:tts;card.setText(label(expected)+" · "+phase);try{JSONObject detail=new JSONObject().put("type","phase_detail").put("stage",expected).put("phase",phase);for(String key:msg.getData().keySet())if(!key.equals("text"))detail.put(key,msg.getData().get(key));event(detail);}catch(Exception e){finish("error","לא ניתן לשמור יומן: "+e.getMessage());}}
            return true;}));
        deadline=()->{if(active&&turn==id&&bindToken==token)finish("error","TIMEOUT — "+label(expected));};main.postDelayed(deadline,budget);
        android.os.Message request=android.os.Message.obtain(null,1);request.replyTo=reply;request.setData(data);
        if(selected.remote!=null){try{selected.remote.send(request);}catch(RemoteException e){finish("error","חיבור למנוע נסגר");}return;}
        selected.connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){if(!active||turn!=id||bindToken!=token||workers.get(expected)!=selected)return;selected.remote=new Messenger(binder);try{selected.remote.send(request);}catch(RemoteException e){finish("error","חיבור למנוע נסגר");}}
            public void onServiceDisconnected(ComponentName name){failed();}public void onBindingDied(ComponentName name){failed();}public void onNullBinding(ComponentName name){failed();}
            private void failed(){if(workers.get(expected)!=selected)return;if(active)finish("error","מנוע השיחה נסגר; הכן מנועים מחדש");else{clearWorkers();controls(true);status.setText("מנוע שוחרר על ידי המערכת. הכן מנועים מחדש.");}}
        };
        try{selected.bound=bindService(intent,selected.connection,BIND_AUTO_CREATE);if(!selected.bound)finish("error","המנוע אינו מותקן או אינו זמין. עדכן את שתי האפליקציות.");}catch(RuntimeException e){finish("error",e.toString());}
    }
    private void completeStage(Bundle data){String completed=stage;long elapsed=SystemClock.elapsedRealtime()-stageStart;
        try{
            JSONObject result=new JSONObject().put("type","stage_end").put("stage",stage).put("state","complete").put("elapsed_ms",elapsed);
            for(String key:data.keySet())if(!key.equals("text")&&!(toolTurn&&("normalized_text".equals(key)||"answer".equals(key))))result.put(key,data.get(key));event(result);
            TextView card=stage.equals("asr")?asr:stage.equals("llm")?llm:tts;card.setText(label(stage)+" · הושלם · "+elapsed+" ms");card.setTextColor(VoiceScreen.SUCCESS);
            releaseStage();
            disposeWorker(completed,()->continueStage(completed,data));
        }catch(Exception e){finish("error",e.toString());}
    }
    private void continueStage(String completed,Bundle data){
        try {
            if(prewarming){
                if(!data.getBoolean("prepared",false))throw new IOException("עדכן את שתי האפליקציות לגרסת המנועים החמים");
                if(completed.equals("asr")){transition("llm","מכין Gemma…");Bundle request=new Bundle();request.putBoolean("prepare",true);bind(new Intent(this,LlmService.class),request,240000);}
                else if(completed.equals("llm")){transition("tts","מכין קול…");Bundle request=new Bundle();request.putBoolean("prepare",true);request.putBoolean("feedback_clips",armingStandby);bind(new Intent(this,TtsService.class),request,240000);}
                else{prepared=true;finish("complete","ההכנה הושלמה. אפשר לדבר; כל מנוע ייטען בתורו, ואימות הקבצים נשמר למסך הנוכחי.");}
                return;
            }
            if(completed.equals("asr")){if(!life.mosaic.models.VoiceAsrBackend.matchesResult(selectedAsrEngine,data.getString("asr_engine",null),selectedBeam,data.getInt("beam_size",life.mosaic.models.VoiceAsrBeam.BASELINE)))throw new IOException("מנוע התמלול החזיר זהות או הגדרת Beam שגויה; בדוק גרסאות");recognized=data.getString("transcript","");if(recognized.isBlank())throw new IOException("תמלול ריק");transcript.setText("אמרת: "+recognized);getPreferences(0).edit().putString("transcript",recognized).apply();
                if(WhatsAppReadIntent.matches(recognized)){
                    toolTurn=true;
                    WhatsAppNotificationTool.Result result=WhatsAppNotificationListener.readLatest(this);
                    event(new JSONObject().put("type","tool_invocation").put("tool",WhatsAppNotificationTool.ID)
                            .put("status",result.status.name()).put("read_only",true));
                    response=WhatsAppNotificationTool.spoken(result);
                    answer.setText("מוזאיק: "+response); // ephemeral; do not save notification text to preferences
                    llm.setText("2 · כלי מקומי: "+WhatsAppNotificationTool.ID+" · "+result.status.name());
                    transition("tts","מכין הקראת התראה…");Bundle toolRequest=new Bundle();
                    toolRequest.putString("answer",response);
                    bind(new Intent(this,TtsService.class),toolRequest,240000);
                    return;
                }
                transition("llm","פותח מודל…");Bundle request=new Bundle();request.putString("text",recognized);request.putBoolean("voice",true);request.putString("source_sha256",life.mosaic.models.ModelCopy.sha256(audioFile,()->false));bind(new Intent(this,LlmService.class),request,240000);}
            else if(completed.equals("llm")){response=data.getString("answer","");if(response.isBlank())throw new IOException("תשובה ריקה");answer.setText("מוזאיק: "+response);getPreferences(0).edit().putString("answer",response).apply();transition("tts","מכין הקראה…");Bundle request=new Bundle();request.putString("answer",response);bind(new Intent(this,TtsService.class),request,240000);}
            else finish("complete","השיחה וההקראה הסתיימו");
        }catch(Exception e){finish("error",e.toString());}
    }
    private void releaseStage(){bindToken++;if(deadline!=null)main.removeCallbacks(deadline);deadline=null;}
    private void disposeWorker(String completed,Runnable next)throws Exception {
        Worker worker=workers.remove(completed);
        if(worker==null||worker.remote==null)throw new IOException("אין חיבור למנוע לשחרור");
        IBinder binder=worker.remote.getBinder();long started=SystemClock.elapsedRealtime();final int id=turn;
        event(new JSONObject().put("type","worker_release_start").put("stage",completed).put("mechanism","death_notification").put("binder_class",binder.getClass().getName()));
        pendingRelease=new WorkerRelease(new WorkerRelease.Endpoint(){
            IBinder.DeathRecipient recipient;
            public boolean watch(Runnable death)throws Exception {
                recipient=death::run;
                try{binder.linkToDeath(recipient,0);return true;}catch(RemoteException alreadyDead){recipient=null;return false;}
            }
            public void stop()throws Exception{worker.remote.send(android.os.Message.obtain(null,9));}
            public void unbind(){if(worker.bound){try{unbindService(worker.connection);}catch(IllegalArgumentException ignored){}worker.bound=false;}}
            public void unwatch(){if(recipient!=null){try{binder.unlinkToDeath(recipient,0);}catch(java.util.NoSuchElementException ignored){}recipient=null;}}
        },new WorkerRelease.Queue(){
            public void post(Runnable action){main.post(action);}
            public void later(Runnable action,long delay){main.postDelayed(action,delay);}
            public void remove(Runnable action){main.removeCallbacks(action);}
        },new WorkerRelease.Result(){
            public void released(String evidence){pendingRelease=null;if(!active||turn!=id)return;
                try{event(releaseEvent("worker_release_end").put("binder_dead",true).put("death_evidence",evidence));next.run();}catch(Exception e){finish("error",e.toString());}
            }
            public void failed(String reason){pendingRelease=null;if(!active||turn!=id)return;
                try{event(releaseEvent("worker_release_error").put("reason",reason).put("binder_alive_snapshot",binder.isBinderAlive()));}catch(Exception ignored){}
                finish("error","המנוע לא השתחרר בזמן; השלב הבא לא נטען: "+reason);
            }
            public void stopError(Exception error){if(!active||turn!=id)return;try{event(new JSONObject().put("type","worker_stop_send_error").put("stage",completed).put("message",error.toString()));}catch(Exception e){finish("error",e.toString());}}
            private JSONObject releaseEvent(String type)throws Exception {
                android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();
                ((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
                return new JSONObject().put("type",type).put("stage",completed).put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("available_memory_bytes",memory.availMem).put("low_memory",memory.lowMemory);
            }
        });pendingRelease.start();
    }
    private void clearWorkers(){if(pendingRelease!=null){pendingRelease.cancel();pendingRelease=null;}prepared=false;sessionId=java.util.UUID.randomUUID().toString();java.util.List<Worker> previous=new java.util.ArrayList<>(workers.values());workers.clear();for(Worker worker:previous){if(worker.remote!=null)try{worker.remote.send(android.os.Message.obtain(null,9));}catch(RemoteException ignored){}if(worker.bound)try{unbindService(worker.connection);}catch(IllegalArgumentException ignored){}}}
    private void finish(String state,String text){if(!active)return;boolean nextCapture=handsfreeTurn&&prewarming&&state.equals("complete");String failedStage=stage;active=false;turn++;stopMic();releaseStage();if(!state.equals("complete"))clearWorkers();main.removeCallbacks(clock);
        try{event(new JSONObject().put("type","end").put("state",state).put("stage",failedStage).put("elapsed_ms",SystemClock.elapsedRealtime()-turnStart).put("message",text));}catch(Exception e){text+="\nלא ניתן לשמור יומן: "+e.getMessage();}
        if(!state.equals("complete")){TextView card=failedStage.equals("capture")||failedStage.equals("asr")?asr:failedStage.equals("llm")?llm:tts;card.setText(label(failedStage)+" · "+text);card.setTextColor(VoiceScreen.ERROR);}
        asrProgress.setVisibility(android.view.View.GONE);llmProgress.setVisibility(android.view.View.GONE);ttsProgress.setVisibility(android.view.View.GONE);
        String result=text+"\n"+(journal==null?"":"נשמר: "+journal.getName());status.setText(result);getPreferences(0).edit().putBoolean("active",false).putString("result",result).putString("journal",journal==null?"":journal.getName()).apply();stage="idle";controls(true);if(prepared){main.removeCallbacks(idleRelease);main.postDelayed(idleRelease,120000);}getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(armingStandby){armingStandby=false;if(state.equals("complete")){HandsFreeController.verifiedSession(sessionId);HandsFreeController.arm(this);}else HandsFreeController.stop(this,"הכנת ההאזנה נכשלה · בדוק אבחון");return;}
        if(nextCapture){main.postDelayed(this::readyCapture,100);}
        else if(handsfreeTurn)endHandsFree(state.equals("complete"));
    }
    private void controls(boolean idle){notificationAccess.setEnabled(idle);notificationCheck.setEnabled(idle);zipformer.setEnabled(idle&&!locked());beam2.setEnabled(idle&&!locked()&&!zipformer.isChecked());talk.setEnabled(idle);warm.setEnabled(idle);setup.setEnabled(idle);archive.setEnabled(idle);export.setEnabled(idle&&journal!=null&&journal.isFile());stop.setEnabled(!idle);screen.session(prepared,active,recording,prewarming,stage);
        boolean deviceLocked=locked();
        screen.handsfreeStatus.setText(HandsFreeController.status(this)+(deviceLocked?"\nהמכשיר נעול · תוכן אישי מחייב פתיחה":""));
        screen.assistantButton.setVisibility(HandsFreeController.selected(this)?android.view.View.GONE:android.view.View.VISIBLE);
        screen.assistantButton.setEnabled(idle&&!deviceLocked);
        screen.handsfreeButton.setText(HandsFreeController.enabled()?"הפסקת האזנה":"הפעלת האזנה מקומית");
        screen.handsfreeButton.setEnabled(idle&&(HandsFreeController.enabled()||(!deviceLocked&&HandsFreeController.selected(this))));
        screen.wakeExport.setEnabled(idle&&!deviceLocked&&new File(getFilesDir(),"handsfree-status.jsonl").isFile());
        if(HandsFreeController.enabled()){talk.setEnabled(false);warm.setEnabled(false);}
        if(deviceLocked){setup.setEnabled(false);archive.setEnabled(false);export.setEnabled(false);notificationAccess.setEnabled(false);notificationCheck.setEnabled(false);}
    }
    private void event(JSONObject value)throws Exception{value.put("at_ms",System.currentTimeMillis()).put("since_start_ms",SystemClock.elapsedRealtime()-turnStart);try(FileOutputStream output=new FileOutputStream(journal,true)){output.write((value+"\n").getBytes(StandardCharsets.UTF_8));output.getFD().sync();}screen.event(value);}
    private static String label(String stage){return switch(stage){case "capture"->"1 · הקלטה";case "asr"->"1 · תמלול";case "llm"->"2 · תשובת מודל";case "tts"->"3 · הקראה";default->"שיחה";};}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==78){if(result==RESULT_OK)armOnResume=true;return;}
        if((request!=72&&request!=76)||result!=RESULT_OK||data==null||data.getData()==null||locked())return;
        File source=request==76?new File(getFilesDir(),"handsfree-status.jsonl"):journal;if(source==null)return;
        try(InputStream input=new FileInputStream(source);OutputStream output=getContentResolver().openOutputStream(data.getData())){if(output==null)throw new IOException("יעד אינו זמין");input.transferTo(output);status.setText(request==76?"אבחון ההאזנה יוצא ללא אודיו או תמלילים":"יומן השיחה יוצא. הוא כולל תמליל ותשובה אישיים.");}catch(Exception e){status.setText("הייצוא נכשל: "+e.getMessage());}}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_LOW){if(active)finish("error","לחץ זיכרון במכשיר — המנועים שוחררו");else{main.removeCallbacks(idleRelease);clearWorkers();controls(true);status.setText("המנועים שוחררו בגלל לחץ זיכרון. הכן אותם שוב כדי להמשיך.");}}}
    @Override protected void onResume(){super.onResume();foreground=true;if(locked()){transcript.setText("");answer.setText("");}controls(!active);if(armOnResume){armOnResume=false;prepareStandby();}if(autoCapture&&!active){autoCapture=false;readyCapture();}notificationAccess.setText(WhatsAppNotificationListener.hasAccess(this)?"גישה להתראות מאושרת — שינוי הרשאה":"אישור גישה להתראות WhatsApp");}
    @Override protected void onDestroy(){unregisterReceiver(handsfreeChanged);super.onDestroy();}
    @Override protected void onPause(){foreground=false;feedback.cancel();if(active)finish("cancelled","השיחה נעצרה במעבר לרקע; הנתונים שכבר נשמרו נשארו במכשיר");if(handsfreeTurn){handsfreeTurn=false;autoCapture=false;MosaicSessionService.close();HandsFreeController.stop(this,"השיחה הופרעה · יש להפעיל האזנה שוב");setShowWhenLocked(false);setTurnScreenOn(false);}if(toolTurn){answer.setText("");response="";}main.removeCallbacks(idleRelease);clearWorkers();super.onPause();}
}
