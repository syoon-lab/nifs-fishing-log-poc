package kr.go.nifs.fieldrecorder;
import android.content.*;
import android.os.Build;
import android.test.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** An isolated COPY of the previously verified S24 backup. Never opens the phone DB. */
public class GearMigrationTest extends AndroidTestCase {
 public void testRealSchema4CopyMigratesWithoutChangingAnyOriginalValue() throws Exception {
  assertTrue("Emulator only",Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"));
  File input=new File(getContext().getExternalFilesDir(null),"gear-migration-input");assertTrue(input.isDirectory());JSONObject before=new JSONObject(new String(Files.readAllBytes(new File(input,"expected.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
  File files=new File(getContext().getCacheDir(),"gear-migration-"+Store.id());assertTrue(files.mkdirs());
  Context c=new RenamingDelegatingContext(getContext(),"migration_"+Store.id()+"_"){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};
  Store store=new Store(c);String path=store.getWritableDatabase().getPath();store.close();
  Files.copy(new File(input,"field-records.db").toPath(),new File(path).toPath(),StandardCopyOption.REPLACE_EXISTING);
  File targetAudio=new File(files,"records/audio");assertTrue(targetAudio.mkdirs());File[] audio=new File(input,"audio").listFiles();assertNotNull(audio);for(File f:audio)Files.copy(f.toPath(),new File(targetAudio,f.getName()).toPath());
  store=new Store(c);JSONObject after=store.snapshot();assertEquals(5,after.getInt("schema_version"));assertEquals(0,store.count("gear_details"));
  int rows=0;JSONObject tables=before.getJSONObject("tables");for(Iterator<String> keys=tables.keys();keys.hasNext();){String table=keys.next();JSONArray old=tables.getJSONArray(table),now=after.getJSONArray(table);assertEquals(table,old.length(),now.length());for(int i=0;i<old.length();i++){JSONObject x=old.getJSONObject(i),y=store.row(table,x.getString("id"));assertEquals(table,x.length(),y.length());for(Iterator<String> fields=x.keys();fields.hasNext();){String key=fields.next();assertTrue(table+"."+key,StructuredIntegrity.equal(x.get(key),y.get(key)));}rows++;}}
  JSONObject hashes=before.getJSONObject("audio");for(Iterator<String> keys=hashes.keys();keys.hasNext();){String name=keys.next();try(InputStream in=new FileInputStream(new File(targetAudio,name))){assertEquals(hashes.getString(name),Archive.sha256(in));}}
  File out=getContext().getExternalFilesDir(null);Archive.write(store,new File(out,"gear-migrated-originals.zip"));
  Files.write(new File(out,"gear-migration-result.json").toPath(),new JSONObject().put("original_rows_unchanged",rows).put("original_audio_unchanged",audio.length).put("old_schema",4).put("new_schema",5).put("new_gear_rows",0).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));store.close();
 }
}
