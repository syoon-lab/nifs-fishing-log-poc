package kr.go.nifs.fieldrecorder;
import android.test.InstrumentationTestCase;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
public class AsrBenchmarkTest extends InstrumentationTestCase {
 public void testCandidateDecoders() throws Exception {
  assertTrue(Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"));
  android.content.Context ctx=getInstrumentation().getTargetContext();android.content.res.AssetManager assets=getInstrumentation().getContext().getAssets();
  JSONArray clips=new JSONArray(new String(assets.open("extraction/clips.json").readAllBytes(),StandardCharsets.UTF_8)),out=new JSONArray();
  File dir=new File(ctx.getFilesDir(),"extraction-qa");dir.mkdirs();
  for(int i=0;i<clips.length();i++){
   JSONObject clip=clips.getJSONObject(i);File audio=new File(ctx.getCacheDir(),"benchmark.m4a");try(InputStream in=assets.open("extraction/"+clip.getString("file"));OutputStream dst=new FileOutputStream(audio)){in.transferTo(dst);}
   for(int mode=0;mode<3;mode++){
    OfflineAsr.reset();long start=SystemClock.elapsedRealtime();JSONObject result=OfflineAsr.transcribeVariant(ctx,audio,()->false,mode==0?1:5,mode==2?OfflineAsr.HINT:"");
    out.put(new JSONObject().put("file",clip.getString("file")).put("phase",clip.getString("phase")).put("mode",mode).put("elapsed_ms",SystemClock.elapsedRealtime()-start).put("result",result));
    Files.write(new File(dir,"benchmark.json").toPath(),out.toString(2).getBytes(StandardCharsets.UTF_8));
   }
   audio.delete();
  }
  assertEquals(27,out.length());
 }
}
