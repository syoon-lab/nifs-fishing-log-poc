package kr.go.nifs.fieldrecorder;
import android.content.*;
import android.test.*;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Isolated replay of every field row and original audio; no mutation of the installed main Store. */
public class FieldSnapshotReplayTest extends AndroidTestCase {
 public void testAllFieldRowsReviewResolutionAndExportPreserveOriginals() throws Exception {
  try(Store original=new Store(getContext())){
   JSONObject before=original.snapshot();assertEquals(8,before.getJSONArray("recordings").length());
   File root=new File(getContext().getCacheDir(),"field-replay-"+Store.id());assertTrue(root.mkdirs());
   Context ctx=new RenamingDelegatingContext(getContext(),"field_replay_"+Store.id()+"_"){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return root;}};
   try(Store copy=new Store(ctx)){
    SQLiteDatabase db=copy.getWritableDatabase();db.execSQL("PRAGMA foreign_keys=OFF");int total=0;
    for(String table:Store.TABLES){JSONArray rows=before.getJSONArray(table);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);ContentValues v=new ContentValues();for(Iterator<String> keys=row.keys();keys.hasNext();){String key=keys.next();Object x=row.get(key);if(x==JSONObject.NULL)v.putNull(key);else if(x instanceof Number)v.put(key,x.toString());else v.put(key,x.toString());}db.insertOrThrow(table,null,v);total++;}}
    db.execSQL("PRAGMA foreign_keys=ON");assertEquals(239,total);
    JSONArray voices=before.getJSONArray("recordings");for(int i=0;i<voices.length();i++){String rid=voices.getJSONObject(i).getString("id");File dst=copy.audio(rid);dst.getParentFile().mkdirs();Files.copy(original.audio(rid).toPath(),dst.toPath());}
    String op="45cd1af5-56a6-42ec-ba6e-f167e426449f";FieldChecks.Check old=FieldChecks.assess(copy,op,"WORK");assertEquals(350,old.data.getInt("haul_quantity"));assertEquals(350,old.data.getInt("redeploy_quantity"));assertTrue(old.data.optBoolean("multiple_deployments"));
    JSONObject confirmed=new JSONObject(old.data.toString());GearLog.confirmDeploymentDate(confirmed,"2026-11-03");FieldChecks.saveReview(copy,old,confirmed);
    FieldChecks.Check after=FieldChecks.assess(copy,op,"WORK");assertEquals(0,after.issues.length());assertEquals(0,MissingQuestions.all(after).size());assertEquals(25,after.data.getJSONArray("catches").getJSONObject(0).getInt("quantity"));assertEquals(50d,after.data.getJSONArray("catches").getJSONObject(0).getDouble("weight_kg"));
    JSONObject now=copy.snapshot();for(String table:Store.TABLES){JSONArray rows=before.getJSONArray(table);for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);assertEquals(table,r.toString(),copy.row(table,r.getString("id")).toString());}assertEquals(table,rows.length()+(table.equals("events")?1:0),now.getJSONArray(table).length());}
    File out=new File(getContext().getExternalFilesDir(null),"field-snapshot-replay.zip");Archive.write(copy,out);
    Files.write(new File(getContext().getExternalFilesDir(null),"field-snapshot-replay.json").toPath(),new JSONObject().put("input_rows",total).put("audio_count",voices.length()).put("original_rows_preserved",true).put("new_review_events",1).put("issues_after_explicit_review",after.issues).put("fields_after_explicit_review",after.data).put("scope","isolated copy only; original phone records unchanged").toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   }
   JSONObject actual=original.snapshot();for(String table:Store.TABLES)assertEquals(table,before.getJSONArray(table).toString(),actual.getJSONArray(table).toString());
  }
 }
}
