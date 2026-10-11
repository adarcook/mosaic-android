package life.mosaic.tensorllm;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.JSONObject;
import life.mosaic.models.ModelCopy;

/** Read-only, independent BlueTTS pack; validated disposable copies, no network. */
public final class TtsSetupActivity extends Activity {
    private static final java.util.concurrent.atomic.AtomicBoolean ACTIVE=new java.util.concurrent.atomic.AtomicBoolean();
    private TextView status;private Button choose;private volatile boolean cancelled;
    @Override public void onCreate(Bundle state){super.onCreate(state);LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(24,80,24,24);
        TextView title=new TextView(this);title.setText("BlueTTS — הקראה של טקסט חופשי\nבחר Documents/Mosaic/tts עם חבילת הקול שהוכנה בסקריפט. מקור המודלים נשאר מחוץ לאפליקציה. נדרש עותק עבודה נוסף של כ־1.50GB. השאר את המסך פתוח בזמן הייבוא.");layout.addView(title);
        choose=new Button(this);choose.setText("בחירת תיקיית BlueTTS");choose.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),71));layout.addView(choose);
        status=new TextView(this);status.setTextIsSelectable(true);layout.addView(status);Button back=new Button(this);back.setText("חזרה לשיחה");back.setOnClickListener(v->finish());layout.addView(back);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=71||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri tree=data.getData();if(!"com.android.externalstorage.documents".equals(tree.getAuthority())||!DocumentsContract.getTreeDocumentId(tree).startsWith("primary:Documents/")){status.setText("בחר תיקייה מקומית בתוך Documents.");return;}
        if(!ACTIVE.compareAndSet(false,true)){status.setText("ייבוא קודם עדיין נסגר; נסה שוב בעוד רגע");return;}
        try{getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(RuntimeException e){ACTIVE.set(false);status.setText(e.toString());return;}
        cancelled=false;choose.setEnabled(false);getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);new Thread(()->install(tree),"mosaic-tts-import").start();}
    private void show(String text){runOnUiThread(()->{if(!isDestroyed())status.setText(text);});}
    private void install(Uri tree){try{
        Map<String,Uri> sources=new HashMap<>();Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        try(Cursor cursor=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(cursor==null)throw new IOException("תיקייה אינה זמינה");int count=0;while(cursor.moveToNext()){if(++count>32)throw new IOException("בחר תיקייה ייעודית");String name=cursor.getString(1);if(sources.put(name,DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0)))!=null)throw new IOException("שם כפול");}}
        JSONObject manifest;try(InputStream input=getContentResolver().openInputStream(require(sources,"manifest.json"))){if(input==null)throw new IOException("אין manifest");manifest=TtsPack.manifest(input);}
        File root=new File(getExternalFilesDir(null),"blue-voice");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("לא ניתן ליצור עותק עבודה");
        JSONObject files=manifest.getJSONObject("files");int index=0;
        for(java.util.Iterator<String> names=files.keys();names.hasNext();){String name=names.next();
            JSONObject spec=files.getJSONObject(name);long bytes=spec.getLong("bytes");String sha=spec.getString("sha256");File target=new File(root,name);show("בודק/מייבא "+(++index)+"/7: "+name);
            boolean valid=ModelCopy.valid(target,bytes,sha,()->cancelled);
            try(InputStream input=getContentResolver().openInputStream(require(sources,name))){if(input==null)throw new IOException("מקור אינו זמין");
                if(valid)ModelCopy.verify(input,bytes,sha,()->cancelled);else{if(root.getUsableSpace()<bytes+67108864L)throw new IOException("אין מספיק מקום לעותק עבודה");ModelCopy.install(input,target,bytes,sha,()->cancelled);}}
        }
        if(cancelled)throw new IOException("הייבוא בוטל");byte[] json=manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);String sha=TranscriptSource.hex(java.security.MessageDigest.getInstance("SHA-256").digest(json));
        ModelCopy.install(new ByteArrayInputStream(json),new File(root,"manifest.json"),json.length,sha,()->cancelled);
        getSharedPreferences("tts-source",0).edit().putString("tree",tree.toString()).commit();show("הקול מוכן. חזור למסך השיחה.");
    }catch(Exception e){show("הייבוא לא הושלם: "+e.getMessage()+"\nהמקור העצמאי נשמר.");}finally{ACTIVE.set(false);runOnUiThread(()->{if(!isDestroyed()){choose.setEnabled(true);getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}});}}
    private static Uri require(Map<String,Uri> files,String name)throws IOException{Uri uri=files.get(name);if(uri==null)throw new IOException("חסר מקור: "+name);return uri;}
    @Override protected void onStop(){cancelled=true;super.onStop();}
}
