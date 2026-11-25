package kr.go.nifs.fieldrecorder;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.test.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
public class MigrationV2Test extends InstrumentationTestCase {
 private JSONArray rows(SQLiteDatabase db,String table) throws Exception {JSONArray out=new JSONArray();try(Cursor c=db.rawQuery("SELECT * FROM "+table+" ORDER BY rowid",null)){while(c.moveToNext()){JSONObject r=new JSONObject();for(int i=0;i<c.getColumnCount();i++){Object v=switch(c.getType(i)){case Cursor.FIELD_TYPE_NULL->JSONObject.NULL;case Cursor.FIELD_TYPE_INTEGER->c.getLong(i);case Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);default->c.getString(i);};r.put(c.getColumnName(i),v);}out.put(r);}}return out;}
 public void testActualS24Schema2CopyMigratesDirectlyTo4() throws Exception {
  Context base=getInstrumentation().getTargetContext();File files=new File(base.getCacheDir(),"migration2-"+Store.id());assertTrue(files.mkdirs());Context ctx=new RenamingDelegatingContext(base,"migration2_"+Store.id()+"_"){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};
  SQLiteDatabase db=ctx.openOrCreateDatabase("field-records.db",0,null);String path=db.getPath();db.close();
  try(InputStream in=getInstrumentation().getContext().getAssets().open("migration-v2.db")){Files.write(new File(path).toPath(),in.readAllBytes());}
  db=SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY);assertEquals(2,db.getVersion());JSONObject before=new JSONObject();String[] tables={"sessions","operations","recordings","locations","events"};for(String t:tables)before.put(t,rows(db,t));db.close();
  Store store=new Store(ctx);try{assertEquals(4,store.getReadableDatabase().getVersion());for(String t:tables)assertEquals(t,before.getJSONArray(t).toString(),store.rows(t).toString());for(String t:new String[]{"transcriptions","transcript_reviews","diary_entries","structured_logs","catch_items"})assertEquals(t,0,store.count(t));try(Cursor c=store.getReadableDatabase().rawQuery("PRAGMA foreign_key_check",null)){assertEquals(0,c.getCount());}}finally{store.close();}
 }
}
