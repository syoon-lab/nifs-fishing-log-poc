package kr.go.nifs.fieldrecorder;
import android.test.InstrumentationTestCase;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
public class CorpusReplayTest extends InstrumentationTestCase {
 public void testFinalExtractorOnEveryActualAsrResult() throws Exception {
  JSONArray data=new JSONArray(new String(getInstrumentation().getContext().getAssets().open("corpus-machine-results.json").readAllBytes(),StandardCharsets.UTF_8));assertEquals(17,data.length());
  for(int i=0;i<data.length();i++){JSONObject r=data.getJSONObject(i),fields=FisheryExtractor.extract(r.getJSONObject("result").getString("text"),r.getString("phase"));StructuredLog.validate(fields);r.put("fields",fields);}
  File dir=new File(getInstrumentation().getTargetContext().getFilesDir(),"extraction-qa");dir.mkdirs();Files.write(new File(dir,"replayed-corpus.json").toPath(),data.toString(2).getBytes(StandardCharsets.UTF_8));
 }
}
