package kr.go.nifs.fieldrecorder;
import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.test.InstrumentationTestCase;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Disposable emulator only. Controlled transcript fixtures test forms; real ASR is separate. */
public class GearFlowTest extends InstrumentationTestCase {
 private Activity activity;private Store s;private Context context;
 private void await(BooleanSupplier p,long ms,String reason) throws Exception {long until=SystemClock.elapsedRealtime()+ms;while(!p.getAsBoolean()&&SystemClock.elapsedRealtime()<until)Thread.sleep(150);assertTrue(reason,p.getAsBoolean());}
 private AccessibilityNodeInfo node(AccessibilityNodeInfo n,String text){if(n==null)return null;if(text.contentEquals(n.getText()==null?"":n.getText())||text.contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo r=node(n.getChild(i),text);if(r!=null)return r;}return null;}
 private boolean visible(String text){return node(getInstrumentation().getUiAutomation().getRootInActiveWindow(),text)!=null;}
 private void tap(String text) throws Exception {await(()->visible(text),15000,"Missing: "+text);AccessibilityNodeInfo n=node(getInstrumentation().getUiAutomation().getRootInActiveWindow(),text);while(n!=null&&!n.isClickable())n=n.getParent();assertNotNull(n);assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(350);}
 private void screen(String name) throws Exception {Thread.sleep(250);Bitmap b=getInstrumentation().getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
 private void open() throws Exception {
  assertTrue("Emulator only",Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"));context=getInstrumentation().getTargetContext();activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));s=RecorderApp.store();
 }
 private void assertOld(JSONObject before) throws Exception {for(String table:Store.TABLES){JSONArray rows=before.getJSONArray(table);for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);assertEquals(table,r.toString(),s.row(table,r.getString("id")).toString());}}}
 public void testMicrophoneSingleRecordDateCandidateAndConfirmedClose() throws Exception {
  open();JSONObject before=s.snapshot();tap("출항");await(()->CaptureService.running,10000,"departure");String trip=CaptureService.sessionId;tap("작업 기록 · 녹음");screen("gear_01_method");assertTrue(visible("자망 · 폭"));assertTrue(visible("통발 · 개"));tap("통발 · 개");await(()->CaptureService.recording,10000,"mic start");assertEquals("WORK",CaptureService.recordingPhase);String rid=CaptureService.recordingId,op=s.activeOperation(trip);Thread.sleep(1600);screen("gear_02_recording");tap("■  녹음 종료 · 저장");await(()->!CaptureService.recording,5000,"save");
  await(()->TranscriptionService.running,15000,"auto check");getInstrumentation().runOnMainSync(()->activity.startService(new Intent(activity,TranscriptionService.class).setAction("cancel")));await(()->!TranscriptionService.running,60000,"cancel silent test audio");tap("닫기");
  // Explicit controlled transcript to exercise candidate confirmation, not an ASR accuracy claim.
  String text="8일에 놓은 통발 서른 개 걷었습니다. 꽃게 스무 마리 사 킬로그램 잡았습니다. 통발 스물다섯 개 다시 투망했습니다.";
  String hash;try(InputStream in=new FileInputStream(s.audio(rid))){hash=Archive.sha256(in);}String t=s.beginTranscription(rid,"UI controlled transcript fixture",OfflineAsr.SHA,hash);s.finishTranscription(t,new JSONObject().put("text",text).put("duration_ms",1000).put("segments",new JSONArray().put(new JSONObject().put("start_ms",0).put("end_ms",1000).put("text",text))));
  tap("직접 입력 · 확인");await(()->visible("이번에 걷은 어구의 투망일"),5000,"deployment editor");assertFalse(visible("작업 인원 (명)"));screen("gear_03_date_candidate");String candidate=FieldChecks.assess(s,op,"WORK").data.getString("deployment_date_candidate");tap(candidate+" 투망일 적용");tap("확인 내용 저장");
  await(()->{try{return FieldChecks.operationIssues(s,op).length()==0;}catch(Exception e){return false;}},5000,"confirmed fields");FieldChecks.Check check=FieldChecks.assess(s,op,"WORK");assertEquals(30,check.data.getInt("haul_quantity"));assertEquals(25,check.data.getInt("redeploy_quantity"));assertEquals(candidate,check.data.getString("deployment_date"));screen("gear_04_confirmed");
  tap("기록 마침");await(()->"COMPLETED".equals(s.operation(op).optString("status")),5000,"single voice close");tap("입항");await(()->!CaptureService.running,10000,"arrival");screen("gear_05_arrival");assertEquals(before.getJSONArray("recordings").length()+1,s.count("recordings"));assertOld(before);Archive.write(s,new File(context.getExternalFilesDir(null),"gear-flow.zip"));
 }
 public void testRealOfflineCheckWithGearHintAndUnconfirmedClose() throws Exception {
  open();JSONObject before=s.snapshot();tap("출항");await(()->CaptureService.running,10000,"departure");String trip=CaptureService.sessionId;final String[] ids=new String[2];
  getInstrumentation().runOnMainSync(()->{try{ids[0]=s.startGearOperation(trip,"GILLNET");ids[1]=s.prepareOperationRecording(ids[0],"WORK","가상기기 합성음성 ASR 시험",null);try(InputStream in=getInstrumentation().getContext().getAssets().open("extraction/01_caught_02_end.m4a");OutputStream out=new FileOutputStream(s.audio(ids[1]))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}s.recordingStarted(ids[1]);s.completeOperationRecording(ids[1],"COMPLETED","",null);}catch(Exception e){throw new RuntimeException(e);}});
  await(()->TranscriptionService.running,15000,"gear check start");await(()->visible("녹음 내용 확인 중"),10000,"large checking");screen("gear_06_checking");
  await(()->{try{JSONObject t=s.latest("transcriptions","recording_id",ids[1]);return !TranscriptionService.running&&t!=null&&"COMPLETED".equals(t.optString("status"));}catch(Exception e){return false;}},180000,"real gear ASR: "+TranscriptionService.message);
  JSONObject t=s.latest("transcriptions","recording_id",ids[1]);assertTrue(t.getString("engine").contains("gear-hint-v1"));assertFalse(t.getString("text").trim().isEmpty());tap("닫기");tap("기록 마침");screen("gear_07_missing_close");tap("미확인으로 종료");await(()->"COMPLETED".equals(s.operation(ids[0]).optString("status")),5000,"unconfirmed close");tap("입항");await(()->!CaptureService.running,10000,"arrival");assertOld(before);Archive.write(s,new File(context.getExternalFilesDir(null),"gear-flow.zip"));
  Files.write(new File(context.getExternalFilesDir(null),"gear-real-asr.json").toPath(),new JSONObject().put("transcription",t).put("fields",FieldChecks.assess(s,ids[0],"WORK").data).put("note","합성 시험 음성. 자망·통발 실제 현장 인식률 평가가 아님").toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
}
