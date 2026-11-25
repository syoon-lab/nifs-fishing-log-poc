package kr.go.nifs.fieldrecorder;
import android.test.InstrumentationTestCase;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
public class OptimizationReplayTest extends InstrumentationTestCase {
 public void testEveryMeasuredTranscriptWithFinalParser() throws Exception {
  android.content.Context ctx=getInstrumentation().getTargetContext();
  File input=new File(ctx.getCacheDir(),"optimization-input/final-machine-corpus.json");
  JSONArray data=new JSONArray(new String(Files.readAllBytes(input.toPath()),StandardCharsets.UTF_8));assertEquals(23,data.length());
  for(int i=0;i<data.length();i++){JSONObject r=data.getJSONObject(i);if(r.has("initial_result"))assertTrue(RecognitionPolicy.needsFullContext(r.getJSONObject("initial_result"),r.getString("phase")));assertFalse(RecognitionPolicy.needsFullContext(r.getJSONObject("result"),r.getString("phase")));JSONObject fields=FisheryExtractor.extract(r.getJSONObject("result").getString("text"),r.getString("phase"));StructuredLog.validate(fields);r.put("fields",fields);}
  File output=new File(ctx.getFilesDir(),"optimization-qa/final-corpus.json");assertFalse(output.exists());Files.write(output.toPath(),data.toString(2).getBytes(StandardCharsets.UTF_8));
 }
}
