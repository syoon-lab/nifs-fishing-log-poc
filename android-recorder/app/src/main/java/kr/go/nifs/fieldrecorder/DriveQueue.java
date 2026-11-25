package kr.go.nifs.fieldrecorder;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Upload bookkeeping is separate from the scientific source database and ZIP schema. */
final class DriveQueue extends SQLiteOpenHelper {
 static final String DB="drive-upload.db";
 private final Context context;
 DriveQueue(Context c){super(c,DB,null,1);context=c.getApplicationContext();}
 @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE uploads(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,account_key TEXT NOT NULL,email TEXT NOT NULL,created_ms INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'WAITING',archive_path TEXT,sha256 TEXT,md5 TEXT,size INTEGER,file_id TEXT,upload_uri TEXT,offset INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,completed_ms INTEGER,error TEXT NOT NULL DEFAULT '',remote_link TEXT)");}
 @Override public void onUpgrade(SQLiteDatabase db,int a,int b){throw new IllegalStateException("지원하지 않는 업로드 대기열 버전");}
 synchronized String add(String sid,String owner,String email,boolean manual){
  if(owner.isEmpty()||email.isEmpty())throw new IllegalArgumentException("연결된 계정이 없습니다.");
  String id=manual?"manual-"+Store.id():"arrival-"+sid;
  ContentValues v=new ContentValues();v.put("id",id);v.put("session_id",sid);v.put("account_key",owner);v.put("email",email);v.put("created_ms",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("uploads",null,v,SQLiteDatabase.CONFLICT_IGNORE);return id;
 }
 synchronized JSONObject get(String id) throws JSONException {try(Cursor c=getReadableDatabase().query("uploads",null,"id=?",new String[]{id},null,null,null)){if(!c.moveToFirst())throw new IllegalArgumentException("업로드 자료가 없습니다.");return row(c);}}
 synchronized JSONArray rows() throws JSONException {JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM uploads ORDER BY created_ms,id",null)){while(c.moveToNext())a.put(row(c));}return a;}
 private JSONObject row(Cursor c) throws JSONException {JSONObject o=new JSONObject();for(int i=0;i<c.getColumnCount();i++)o.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getType(i)==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):c.getString(i));return o;}
 synchronized void update(String id,ContentValues values){if(getWritableDatabase().update("uploads",values,"id=?",new String[]{id})!=1)throw new IllegalArgumentException("업로드 자료가 없습니다.");}
 synchronized void state(String id,String status,String message){ContentValues v=new ContentValues();v.put("status",status);v.put("error",message==null?"":message);update(id,v);}
 synchronized JSONObject next(String owner) throws JSONException {try(Cursor c=getReadableDatabase().query("uploads",null,"account_key=? AND status IN ('WAITING','UPLOADING','RETRY')",new String[]{owner},null,null,"created_ms,id","1")){return c.moveToFirst()?row(c):null;}}
 synchronized int pending() {try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM uploads WHERE status!='DONE'",null)){c.moveToFirst();return c.getInt(0);}}
 synchronized int completed() {try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM uploads WHERE status='DONE'",null)){c.moveToFirst();return c.getInt(0);}}
 synchronized void retryBlocked(String owner){ContentValues v=new ContentValues();v.put("status","WAITING");v.put("error","");getWritableDatabase().update("uploads",v,"account_key=? AND status IN ('AUTH_REQUIRED','FAILED','RETRY')",new String[]{owner});}
 File archive(String id){if(!id.matches("[a-zA-Z0-9-]+"))throw new IllegalArgumentException("잘못된 전송 식별자");File dir=new File(context.getFilesDir(),"drive-outbox");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("업로드 보관 폴더를 만들 수 없습니다.");return new File(dir,id+".zip");}
}
