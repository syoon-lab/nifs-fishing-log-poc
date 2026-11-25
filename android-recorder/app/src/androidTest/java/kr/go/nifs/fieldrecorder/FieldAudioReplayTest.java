package kr.go.nifs.fieldrecorder;
import android.test.InstrumentationTestCase;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.file.Files;

/** Read-only replay of preserved November 23 audio; never changes Store or the upload queue. */
public class FieldAudioReplayTest extends InstrumentationTestCase {
 public void testBoundedRetryOnActualRepetition() throws Exception {
  Context c=getInstrumentation().getTargetContext();String rid="6f4b3c0d-a6eb-42a3-b079-263f73a62273";
  File f=new File(c.getFilesDir(),"records/audio/"+rid+".m4a");assertTrue("Preserved field audio required",f.isFile());
  String hash;try(InputStream in=new FileInputStream(f)){hash=Archive.sha256(in);}OfflineAsr.reset();long start=System.nanoTime();
  JSONObject result=OfflineAsr.transcribeVariant(c,f,()->false,5,OfflineAsr.GEAR_HINT);
  String after;try(InputStream in=new FileInputStream(f)){after=Archive.sha256(in);}assertEquals(hash,after);
  JSONObject evidence=new JSONObject().put("recording_id",rid).put("audio_sha256",hash).put("elapsed_ms",(System.nanoTime()-start)/1000000).put("result",result).put("repetition_detected",RecognitionPolicy.repetitive(result.getString("text"))).put("note","원음 읽기 전용 재전사. 수량의 실측 정답이나 정확도 평가가 아님. 운영 DB에 적용하지 않음.");
  Files.write(new File(c.getExternalFilesDir(null),"field-audio-replay.json").toPath(),evidence.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
}
