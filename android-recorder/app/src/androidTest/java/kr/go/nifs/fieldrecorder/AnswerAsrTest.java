package kr.go.nifs.fieldrecorder;
import android.content.*;
import android.os.*;
import android.test.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
public class AnswerAsrTest extends InstrumentationTestCase {
 public void testActualShortReplyInferenceToQuestionedField() throws Exception {
  assertTrue(Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"));Context ctx=getInstrumentation().getTargetContext();File dir=new File(ctx.getCacheDir(),"answer-asr-"+Store.id());assertTrue(dir.mkdirs());Context isolated=new RenamingDelegatingContext(ctx,"answerasr_"+Store.id()+"_"){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return dir;}};
  try(Store s=new Store(isolated)){
   String trip=s.depart(60000),op=s.startGearOperation(trip,"POT"),initial=s.prepareOperationRecording(op,"WORK","제어된 최초 기록",null);Files.write(s.audio(initial).toPath(),new byte[]{1,2,3});s.recordingStarted(initial);s.completeOperationRecording(initial,"COMPLETED","",null);String hash;try(InputStream in=new FileInputStream(s.audio(initial))){hash=Archive.sha256(in);}String t=s.beginTranscription(initial,"controlled fixture",OfflineAsr.SHA,hash);s.finishTranscription(t,new JSONObject().put("text","통발 양망했습니다. 꽃게 스무 마리 잡았습니다. 통발을 다시 놓았습니다.").put("duration_ms",1000).put("segments",new JSONArray().put(new JSONObject().put("start_ms",0).put("end_ms",1000).put("text","통발 양망했습니다. 꽃게 스무 마리 잡았습니다. 통발을 다시 놓았습니다."))));
   FieldChecks.Check c=FieldChecks.assess(s,op,"WORK");MissingQuestions.Question q=null;for(MissingQuestions.Question x:MissingQuestions.all(c))if(x.key.equals("haul_quantity"))q=x;assertNotNull(q);String rid=s.prepareAnswer(op,MissingQuestions.request(c,q),null);
   try(InputStream in=getInstrumentation().getContext().getAssets().open("questions/haul_30.m4a");OutputStream out=new FileOutputStream(s.audio(rid))){in.transferTo(out);}s.recordingStarted(rid);s.completeOperationRecording(rid,"COMPLETED","",null);try(InputStream in=new FileInputStream(s.audio(rid))){hash=Archive.sha256(in);}String attempt=s.beginTranscription(rid,OfflineAsr.ENGINE+";gear-hint-v1",OfflineAsr.SHA,hash);
   OfflineAsr.reset();long start=SystemClock.elapsedRealtime();JSONObject result=OfflineAsr.transcribeVariant(ctx,s.audio(rid),()->false,1,OfflineAsr.GEAR_HINT);long elapsed=SystemClock.elapsedRealtime()-start;s.finishTranscription(attempt,result);c=FieldChecks.assess(s,op,"WORK");
   JSONObject report=new JSONObject().put("result",result).put("elapsed_ms",elapsed).put("fields",c.data).put("source_sha256",hash).put("note","합성 한국어 짧은 답변 1건. 질문의 정답을 ASR 힌트에 넣지 않음. S24 실제 발화 성능이 아님.");Files.write(new File(ctx.getExternalFilesDir(null),"question-real-asr.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
   assertEquals(result.toString(),30,c.data.getInt("haul_quantity"));assertTrue(c.data.isNull("redeploy_quantity"));assertEquals(1,c.data.getJSONArray("catches").length());s.finishOperationChecked(op,FieldChecks.fingerprint(s,op));s.arrive(trip);Archive.write(s,new File(ctx.getExternalFilesDir(null),"question-real-asr.zip"));
  }
 }
}
