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

/** Disposable emulator only: real local ASR, visible controls, then an acknowledged unresolved close. */
public class FieldFlowTest extends InstrumentationTestCase {
 private Activity activity;private Store store;private Context context;private String op;
 private void await(BooleanSupplier p,long ms,String reason) throws Exception {long until=SystemClock.elapsedRealtime()+ms;while(!p.getAsBoolean()&&SystemClock.elapsedRealtime()<until)Thread.sleep(150);assertTrue(reason,p.getAsBoolean());}
 private AccessibilityNodeInfo node(AccessibilityNodeInfo n,String text){if(n==null)return null;if(text.contentEquals(n.getText()==null?"":n.getText())||text.contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo r=node(n.getChild(i),text);if(r!=null)return r;}return null;}
 private boolean visible(String text){return node(getInstrumentation().getUiAutomation().getRootInActiveWindow(),text)!=null;}
 private void tap(String text) throws Exception {await(()->visible(text),15000,"Missing: "+text);AccessibilityNodeInfo n=node(getInstrumentation().getUiAutomation().getRootInActiveWindow(),text);while(n!=null&&!n.isClickable())n=n.getParent();assertNotNull(n);assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(350);}
 private void collectInputs(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> out){if(n==null)return;if("android.widget.EditText".contentEquals(n.getClassName()))out.add(n);for(int i=0;i<n.getChildCount();i++)collectInputs(n.getChild(i),out);}
 private void screen(String name) throws Exception {Thread.sleep(400);Bitmap b=getInstrumentation().getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
 private String fixtureHash(String id) throws Exception {try(InputStream in=new FileInputStream(store.audio(id))){return Archive.sha256(in);}}
 private String fixture(String phase,String asset) throws Exception {String r=store.prepareOperationRecording(op,phase,"가상기기 합성 시험",null);try(InputStream in=getInstrumentation().getContext().getAssets().open(asset);OutputStream out=new FileOutputStream(store.audio(r))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}store.recordingStarted(r);store.completeOperationRecording(r,"COMPLETED","",null);return r;}
 public void testFieldFlowPreservesOldDataAndAllowsUnconfirmedClose() throws Exception {
  assertTrue("Emulator only",Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"));context=getInstrumentation().getTargetContext();
  activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
  store=RecorderApp.store();assertNotNull(store);JSONObject before=store.snapshot();
  tap("출항");await(()->CaptureService.running,10000,"departure");String trip=CaptureService.sessionId;op=store.startOperation(trip);
  // End-script audio intentionally lacks crew. No expected transcript is sent to the ASR engine.
  String start=fixture("START","extraction/01_caught_02_end.m4a");
  await(()->TranscriptionService.running,15000,"automatic ASR did not start while voyage active");assertTrue(CaptureService.running);
  await(()->visible("녹음 내용 확인 중"),10000,"prominent progress");screen("progress_01_checking");
  await(()->{try{JSONObject t=store.latest("transcriptions","recording_id",start);return !TranscriptionService.running&&t!=null&&"COMPLETED".equals(t.optString("status"));}catch(Exception e){return false;}},180000,"real ASR failed: "+TranscriptionService.message);
  await(()->visible("직접 입력 · 확인"),15000,"missing-field notice");screen("field_01_missing");tap("직접 입력 · 확인");
  List<AccessibilityNodeInfo> inputs=new ArrayList<>();collectInputs(getInstrumentation().getUiAutomation().getRootInActiveWindow(),inputs);assertTrue(inputs.size()>=2);
  Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"통발 회수");assertTrue(inputs.get(0).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value));value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"3");assertTrue(inputs.get(1).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value));screen("field_02_manual");tap("확인 내용 저장");assertEquals(0,FieldChecks.assess(store,op,"START").issues.length());
  tap("필수 항목 확인됨");tap("시작 기록 확인");tap("추가 녹음");await(()->CaptureService.recording,10000,"supplement microphone");assertEquals("START_SUPPLEMENT",CaptureService.recordingPhase);assertEquals("FISHING",store.operation(op).getString("stage"));Thread.sleep(1800);tap("■  녹음 종료 · 저장");await(()->!CaptureService.recording,5000,"supplement save");await(()->TranscriptionService.running,10000,"supplement check");getInstrumentation().runOnMainSync(()->activity.startService(new Intent(activity,TranscriptionService.class).setAction("cancel")));await(()->!TranscriptionService.running,30000,"supplement cancel");await(()->visible("나중에 확인"),10000,"supplement notice");tap("나중에 확인");assertEquals(3,FieldChecks.assess(store,op,"START").data.getInt("workers"));
  tap("조업 마무리 · 녹음");await(()->CaptureService.recording,10000,"end microphone");Thread.sleep(1800);tap("■  녹음 종료 · 저장");await(()->!CaptureService.recording,5000,"save end");
  // Stop the automatic check via its service action, retaining the raw end recording.
  await(()->TranscriptionService.running,10000,"end check did not start");
  getInstrumentation().runOnMainSync(()->activity.startService(new Intent(activity,TranscriptionService.class).setAction("cancel")));
  await(()->!TranscriptionService.running,30000,"cancel did not complete");
  if(visible("나중에 확인"))tap("나중에 확인");else{await(()->visible("나중에 확인"),10000,"end unresolved notice");tap("나중에 확인");}
  tap("조업 종료");await(()->visible("미확인으로 종료"),10000,"close confirmation");screen("field_03_close");tap("미확인으로 종료");await(()->"COMPLETED".equals(store.operation(op).optString("status")),5000,"operation did not close");tap("입항");await(()->!CaptureService.running,10000,"arrival");screen("field_04_arrived");
  assertNotNull(FieldChecks.event(store,"FIELD_OPERATION_CLOSE",null,op,null));assertTrue(FieldChecks.operationIssues(store,op).length()>0);
  JSONObject after=store.snapshot();for(String table:Store.TABLES){JSONArray old=before.getJSONArray(table);for(int i=0;i<old.length();i++){JSONObject r=old.getJSONObject(i);assertEquals(table,r.toString(),store.row(table,r.getString("id")).toString());}}
  File out=context.getExternalFilesDir(null);Archive.write(store,new File(out,"field-flow.zip"));JSONObject report=new JSONObject().put("old_rows_preserved",true).put("automatic_asr_during_voyage",true).put("manual_review",true).put("supplement_mic",true).put("unresolved_close",true).put("arrival",true).put("real_machine_text",store.latest("transcriptions","recording_id",start).getString("text")).put("final_snapshot",after);Files.write(new File(out,"field-flow.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }

 public void testPendingCloseWarningKeepsRecordingAndOperation() throws Exception {
  assertTrue("Emulator only",Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"));context=getInstrumentation().getTargetContext();
  activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));store=RecorderApp.store();
  tap("출항");await(()->CaptureService.running,10000,"departure");String trip=CaptureService.sessionId;
  final String[] endAttempt={null};
  getInstrumentation().runOnMainSync(()->{try{
   op=store.startOperation(trip);String start=fixture("START","extraction/01_caught_02_end.m4a");
   String a=store.beginTranscription(start,"UI fixture",OfflineAsr.SHA,fixtureHash(start));store.failTranscription(a,"CANCELLED","UI fixture");FieldChecks.log(store,FieldChecks.assess(store,op,"START"),FieldChecks.SHOWN,new JSONObject());
   store.beginWrap(op);String end=fixture("END","extraction/01_caught_02_end.m4a");endAttempt[0]=store.beginTranscription(end,"UI fixture",OfflineAsr.SHA,fixtureHash(end));
  }catch(Exception e){throw new RuntimeException(e);}});
  await(()->visible("녹음 내용 확인 중"),10000,"queued/running state");screen("progress_02_end_checking");tap("조업 종료");
  await(()->visible("기다리기"),10000,"wait choice");assertTrue(visible("아직 녹음 내용을\n확인 중입니다"));screen("progress_03_pending_close");tap("기다리기");assertEquals("ACTIVE",store.operation(op).optString("status"));
  // A new result while the warning is open must not reuse stale acknowledgment.
  tap("조업 종료");store.failTranscription(endAttempt[0],"CANCELLED","UI fixture");tap("미확인으로 종료");
  assertEquals("ACTIVE",store.operation(op).optString("status"));await(()->visible("남은 확인 사항"),10000,"updated results before close");tap("미확인으로 종료");
  await(()->"COMPLETED".equals(store.operation(op).optString("status")),10000,"explicit unresolved close");
  // Dismiss the newly available result if it is shown before arrival.
  Thread.sleep(1400);if(visible("나중에 확인"))tap("나중에 확인");tap("입항");await(()->!CaptureService.running,10000,"arrival");
 }
}
