package kr.go.nifs.fieldrecorder;
import android.test.InstrumentationTestCase;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;

/** Diagnostic only: verified model copies and audio copies in cache, no user DB writes. */
public class ModelComparisonTest extends InstrumentationTestCase {
 public void testSuppliedModel() throws Exception {
  android.content.Context ctx=getInstrumentation().getTargetContext();File input=new File(ctx.getCacheDir(),"optimization-input");
  JSONObject config=new JSONObject(new String(Files.readAllBytes(new File(input,"config.json").toPath()),StandardCharsets.UTF_8));
  File model=new File(input,config.getString("model"));try(InputStream in=new FileInputStream(model)){assertEquals(config.getString("model_sha256"),Archive.sha256(in));}
  Method call=OfflineAsr.class.getDeclaredMethod("recognize",String.class,String.class,int.class,int.class,String.class,int.class);call.setAccessible(true);
  File dir=new File(ctx.getFilesDir(),"optimization-qa");assertTrue(dir.isDirectory()||dir.mkdirs());File report=new File(dir,config.getString("output"));assertFalse(report.exists());JSONArray out=new JSONArray(),jobs=config.getJSONArray("jobs");
  for(int i=0;i<jobs.length();i++){
   JSONObject job=jobs.getJSONObject(i);File pcm=File.createTempFile("asr-bench-",".f32",ctx.getCacheDir());OfflineAsr.reset();long start=SystemClock.elapsedRealtime();
   try{
    AudioDecoder.decode(new File(input,job.getString("file")),pcm,()->false);
    byte[] bytes=(byte[])call.invoke(null,model.getAbsolutePath(),pcm.getAbsolutePath(),job.getInt("threads"),job.getInt("beam"),job.optString("hint",OfflineAsr.HINT),job.getInt("context"));
    JSONObject result=new JSONObject(new String(bytes,StandardCharsets.UTF_8));out.put(new JSONObject(job.toString()).put("elapsed_ms",SystemClock.elapsedRealtime()-start).put("result",result).put("fields",FisheryExtractor.extract(result.getString("text"),job.getString("phase"))));
    Files.write(report.toPath(),out.toString(2).getBytes(StandardCharsets.UTF_8));
   }finally{pcm.delete();}
  }
 }
}
