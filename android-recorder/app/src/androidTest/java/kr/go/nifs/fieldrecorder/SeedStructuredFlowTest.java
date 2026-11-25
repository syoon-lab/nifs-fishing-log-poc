package kr.go.nifs.fieldrecorder;
import android.test.InstrumentationTestCase;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
public class SeedStructuredFlowTest extends InstrumentationTestCase {
 private String voice(Store store,String sid,String op,String phase,String file) throws Exception {
  String r=store.prepareOperationRecording(op,phase,"합성 시험 · 실제 조업 아님",null);
  try(InputStream in=getInstrumentation().getContext().getAssets().open("extraction/"+file);OutputStream out=new FileOutputStream(store.audio(r))){in.transferTo(out);}
  store.recordingStarted(r);store.completeOperationRecording(r,"COMPLETED","",null);store.event(sid,r,op,"MOCK_FIXTURE","합성 시험음성. 현재 생성 시각은 실제 조업 시각이 아님.");return r;
 }
 public void testSeedSequentialVoicesForUi() throws Exception {
  assertTrue(Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"));Store s=RecorderApp.store();String trip=s.depart(60000),op=s.startOperation(trip),start=voice(s,trip,op,"START","01_caught_01_start.m4a");s.beginWrap(op);String end=voice(s,trip,op,"END","01_caught_02_end.m4a");s.finishOperation(op);s.arrive(trip);
  File dir=new File(getInstrumentation().getTargetContext().getFilesDir(),"extraction-qa");dir.mkdirs();Files.write(new File(dir,"ui-fixture.json").toPath(),new JSONObject().put("trip_id",trip).put("operation_id",op).put("start_recording",start).put("end_recording",end).toString(2).getBytes(StandardCharsets.UTF_8));
 }
}
