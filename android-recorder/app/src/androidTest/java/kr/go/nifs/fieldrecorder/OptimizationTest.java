package kr.go.nifs.fieldrecorder;
import android.test.InstrumentationTestCase;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/** Reads only supplied cache fixtures; never creates/updates Store or user recordings. */
public class OptimizationTest extends InstrumentationTestCase {
 public void testSuppliedCandidates() throws Exception {
  android.content.Context ctx=getInstrumentation().getTargetContext();
  File inputs=new File(ctx.getCacheDir(),"optimization-input");
  JSONObject config=new JSONObject(new String(Files.readAllBytes(new File(inputs,"config.json").toPath()),StandardCharsets.UTF_8));
  JSONArray jobs=config.getJSONArray("jobs"),out=new JSONArray();
  File dir=new File(ctx.getFilesDir(),"optimization-qa");assertTrue(dir.isDirectory()||dir.mkdirs());
  File report=new File(dir,config.getString("output"));assertFalse("Do not overwrite benchmark evidence",report.exists());
  for(int i=0;i<jobs.length();i++){
   JSONObject job=jobs.getJSONObject(i);File audio=new File(inputs,job.getString("file"));assertTrue(audio.isFile());
   OfflineAsr.reset();long started=SystemClock.elapsedRealtime();
   JSONObject row=new JSONObject(job.toString());
   try{
    JSONObject result=OfflineAsr.transcribeConfigured(ctx,audio,()->false,job.getInt("threads"),job.getInt("beam"),job.optString("hint",OfflineAsr.HINT),job.getInt("context"));
    row.put("result",result).put("fields",FisheryExtractor.extract(result.getString("text"),job.getString("phase")));
   }catch(Exception e){row.put("error",e.toString());}
   row.put("elapsed_ms",SystemClock.elapsedRealtime()-started);out.put(row);
   Files.write(report.toPath(),out.toString(2).getBytes(StandardCharsets.UTF_8));
  }
  for(int i=0;i<out.length();i++)assertFalse(out.getJSONObject(i).toString(),out.getJSONObject(i).has("error"));
 }
}
