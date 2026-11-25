package kr.go.nifs.fieldrecorder;
import android.test.InstrumentationTestCase;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
public class FinalCorpusTest extends InstrumentationTestCase {
 public void testOfflineCorpusAndStructuredFields() throws Exception {
  assertTrue(Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"));android.content.Context ctx=getInstrumentation().getTargetContext();android.content.res.AssetManager assets=getInstrumentation().getContext().getAssets();JSONArray out=new JSONArray();File dir=new File(ctx.getFilesDir(),"extraction-qa");dir.mkdirs();
  for(String group:new String[]{"extraction","holdout"}){
   JSONArray clips=new JSONArray(new String(assets.open(group+"/clips.json").readAllBytes(),StandardCharsets.UTF_8));
   for(int i=0;i<clips.length();i++){
    JSONObject clip=clips.getJSONObject(i);File audio=new File(ctx.getCacheDir(),"corpus.m4a");try(InputStream in=assets.open(group+"/"+clip.getString("file"));OutputStream dst=new FileOutputStream(audio)){in.transferTo(dst);}
    OfflineAsr.reset();long start=SystemClock.elapsedRealtime();JSONObject result=OfflineAsr.transcribe(ctx,audio,()->false);JSONObject fields=FisheryExtractor.extract(result.getString("text"),clip.getString("phase"));StructuredLog.validate(fields);
    out.put(new JSONObject().put("group",group).put("file",clip.getString("file")).put("phase",clip.getString("phase")).put("elapsed_ms",SystemClock.elapsedRealtime()-start).put("result",result).put("fields",fields));Files.write(new File(dir,"final-corpus.json").toPath(),out.toString(2).getBytes(StandardCharsets.UTF_8));audio.delete();
   }
  }
  assertEquals(17,out.length());
 }
}
