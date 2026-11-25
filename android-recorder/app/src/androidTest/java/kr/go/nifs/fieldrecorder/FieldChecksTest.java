package kr.go.nifs.fieldrecorder;
import android.content.*;
import android.test.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;

public class FieldChecksTest extends AndroidTestCase {
 private Store s;private File files;private String trip,op;
 @Override protected void setUp() throws Exception{super.setUp();files=new File(getContext().getCacheDir(),"field-check-test-"+Store.id());assertTrue(files.mkdirs());Context c=new RenamingDelegatingContext(getContext(),"checks_"+Store.id()+"_"){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};s=new Store(c);trip=s.depart(60000);op=s.startOperation(trip);}
 @Override protected void tearDown() throws Exception{s.close();super.tearDown();}
 private String voice(String phase,String text,boolean supplement) throws Exception {
  if("END".equals(phase)&&!supplement)s.beginWrap(op);
  String id=supplement?s.prepareSupplement(op,phase,"추가",null):s.prepareOperationRecording(op,phase,"시험",null);
  Files.write(s.audio(id).toPath(),new byte[]{4,5,6});s.recordingStarted(id);s.completeOperationRecording(id,"COMPLETED","",null);
  if(text!=null)transcribe(id,text);return id;
 }
 private void transcribe(String r,String text) throws Exception {String sha;try(InputStream in=new FileInputStream(s.audio(r))){sha=Archive.sha256(in);}String t=s.beginTranscription(r,"fixture",OfflineAsr.SHA,sha);s.finishTranscription(t,new JSONObject().put("text",text).put("duration_ms",1000).put("segments",new JSONArray().put(new JSONObject().put("start_ms",0).put("end_ms",1000).put("text",text))));}
 public void testOptionalSpeciesGearNotesAndAutomaticTime() throws Exception {voice("START","채낚기를 시작합니다. 작업 인원 세 명입니다.",false);FieldChecks.Check c=FieldChecks.assess(s,op,"START");assertEquals(0,c.issues.length());assertTrue(c.data.isNull("target_species"));assertTrue(c.data.isNull("gear_quantity"));assertTrue(s.rows("recordings").getJSONObject(0).getLong("started_ms")>0);}
 public void testMissingAndHomophoneWorkersAreNotZero() throws Exception {voice("START","통발을 걷습니다. 인원은 내 명입니다.",false);FieldChecks.Check c=FieldChecks.assess(s,op,"START");assertEquals(1,c.issues.length());assertTrue(c.data.isNull("workers"));assertEquals(4,c.data.getInt("workers_candidate"));}
 public void testZeroWorkersNeedsReview() throws Exception {assertTrue(FieldChecks.issues(FieldChecks.extract("채낚기 시작. 인원 0명.","START")).length()>0);}
 public void testNoCatchSkipsSpeciesAndAmount() throws Exception {assertEquals(0,FieldChecks.issues(FieldChecks.extract("이번에는 잡힌 것이 없습니다.","END")).length());assertEquals(0,FieldChecks.issues(FieldChecks.extract("어획은 없습니다.","END")).length());}
 public void testCountOrWeightEnoughAndUnknownSpeciesUnresolved() throws Exception {assertEquals(0,FieldChecks.issues(FieldChecks.extract("꽃게 스무 마리입니다.","END")).length());assertEquals(0,FieldChecks.issues(FieldChecks.extract("꽃게 사 킬로그램입니다.","END")).length());assertTrue(FieldChecks.issues(FieldChecks.extract("두 상자입니다.","END")).length()>0);}
 public void testMultipleSpeciesNeverSum() throws Exception {JSONObject d=FieldChecks.extract("꽃게 스무 마리. 고등어 세 마리.","END");assertEquals(2,d.getJSONArray("catches").length());assertEquals(0,FieldChecks.issues(d).length());assertTrue(FieldChecks.issues(FieldChecks.extract("꽃게 세 마리. 꽃게 네 마리.","END")).length()>0);}
 public void testSupplementFillsWorkersWithoutChangingOriginalOrStage() throws Exception {String r=voice("START","통발을 걷습니다.",false);String before=s.row("recordings",r).toString();voice("START","작업 인원 세 명입니다.",true);assertEquals(0,FieldChecks.assess(s,op,"START").issues.length());assertEquals("FISHING",s.operation(op).getString("stage"));assertEquals(before,s.row("recordings",r).toString());}
 public void testEndSupplementDoesNotRegressStageAndExportPreservesAll() throws Exception {voice("START","채낚기. 인원 세 명.",false);voice("END","조업 마무리.",false);voice("END","꽃게 스무 마리.",true);assertEquals("READY_TO_END",s.operation(op).getString("stage"));assertEquals(0,FieldChecks.assess(s,op,"END").issues.length());s.finishOperationChecked(op,null);s.arrive(trip);Archive.write(s,new File(files,"supplement.zip"));assertEquals(3,s.count("recordings"));assertEquals(3,s.count("transcriptions"));}
 public void testManualBeforeTranscriptionAndEmptyValuesRetained() throws Exception {voice("START",null,false);FieldChecks.Check c=FieldChecks.assess(s,op,"START");JSONObject d=new JSONObject(c.data.toString()).put("work_type","양망").put("workers",3);FieldChecks.saveReview(s,c,d);assertEquals(0,FieldChecks.assess(s,op,"START").issues.length());assertEquals(0,s.count("transcript_reviews"));assertEquals(0,s.count("structured_logs"));assertNotNull(FieldChecks.event(s,FieldChecks.REVIEW,null,op,"START"));}
 public void testNewTranscriptInvalidatesOpenReviewAndPreviousReviewRetained() throws Exception {String r=voice("START",null,false);FieldChecks.Check c=FieldChecks.assess(s,op,"START");FieldChecks.saveReview(s,c,new JSONObject(c.data.toString()).put("work_type","채낚기").put("workers",3));transcribe(r,"채낚기. 인원 네 명.");try{FieldChecks.saveReview(s,c,c.data);fail();}catch(IllegalStateException expected){}assertEquals(4,FieldChecks.assess(s,op,"START").data.getInt("workers"));assertNotNull(FieldChecks.event(s,FieldChecks.REVIEW,null,op,"START"));}
 public void testDeferredIsStillUnresolvedAndClosureRequiresCurrentToken() throws Exception {voice("START","채낚기.",false);voice("END","마무리.",false);FieldChecks.Check c=FieldChecks.assess(s,op,"START");FieldChecks.log(s,c,FieldChecks.DEFER,new JSONObject());assertTrue(FieldChecks.seen(s,c));assertTrue(FieldChecks.operationIssues(s,op).length()>0);try{s.finishOperationChecked(op,null);fail();}catch(IllegalStateException expected){}s.finishOperationChecked(op,FieldChecks.fingerprint(s,op));assertEquals("COMPLETED",s.operation(op).getString("status"));assertNotNull(FieldChecks.event(s,"FIELD_OPERATION_CLOSE",null,op,null));}
 public void testPendingNotSilentlyPassedAndSaveFailureNotQueued() throws Exception {String r=s.prepareOperationRecording(op,"START","",null);s.completeOperationRecording(r,"FAILED","storage failure",null);assertNull(FieldChecks.queued(s));String r2=voice("START",null,false);assertEquals(r2,FieldChecks.queued(s));assertTrue(FieldChecks.assess(s,op,"START").pending);}
 public void testManualContradictionRejectedAndRawUntouched() throws Exception {voice("START","채낚기. 세 명.",false);voice("END","꽃게 스무 마리.",false);FieldChecks.Check c=FieldChecks.assess(s,op,"END");String before=s.rows("transcriptions").toString();try{FieldChecks.saveReview(s,c,new JSONObject(c.data.toString()).put("outcome","NO_CATCH"));fail();}catch(IllegalArgumentException expected){}assertEquals(before,s.rows("transcriptions").toString());assertNull(FieldChecks.event(s,FieldChecks.REVIEW,null,op,"END"));}
 public void testConfirmedStructuredValuesUsedWithoutRewriting() throws Exception {String r=voice("START","채낚기. 세 명.",false);JSONObject t=s.latest("transcriptions","recording_id",r);JSONObject d=StructuredLog.extract(t.getString("text"),"START").put("workers",4);s.reviewFields(t.getString("id"),t.getString("text"),d);assertEquals(4,FieldChecks.assess(s,op,"START").data.getInt("workers"));}
 public void testSpeciesMentionWithoutAmountIsStillMissing() throws Exception {JSONObject d=FieldChecks.extract("꽃게 스무 마리. 갈치도 잡았습니다.","END");assertEquals(2,d.getJSONArray("catches").length());assertTrue(FieldChecks.issues(d).toString().contains("갈치"));}
 public void testCombinedWeightNotAllocatedToLastSpecies() throws Exception {JSONObject d=FieldChecks.extract("꽃게와 갈치를 잡았습니다. 총 무게는 사 킬로그램입니다.","END");assertTrue(FieldChecks.issues(d).length()>0);JSONArray a=d.getJSONArray("catches");for(int i=0;i<a.length();i++)assertTrue(a.getJSONObject(i).isNull("weight_kg"));}
 public void testManualMissingUnitCanBeSavedUnconfirmed() throws Exception {voice("START","채낚기. 세 명.",false);voice("END","꽃게 세 마리.",false);FieldChecks.Check c=FieldChecks.assess(s,op,"END");JSONObject d=new JSONObject(c.data.toString());d.getJSONArray("catches").getJSONObject(0).put("unit",JSONObject.NULL);FieldChecks.saveReview(s,c,d);assertTrue(FieldChecks.assess(s,op,"END").issues.length()>0);}
 public void testSupplementKeepsEarlierManualValuesAndFlagsNewConflicts() throws Exception {voice("START","통발을 걷습니다.",false);FieldChecks.Check c=FieldChecks.assess(s,op,"START");FieldChecks.saveReview(s,c,new JSONObject(c.data.toString()).put("workers",3));voice("START","목표는 꽃게입니다.",true);assertEquals(3,FieldChecks.assess(s,op,"START").data.getInt("workers"));assertEquals(0,FieldChecks.assess(s,op,"START").issues.length());voice("START","작업 인원 네 명입니다.",true);assertTrue(FieldChecks.assess(s,op,"START").data.isNull("workers"));assertTrue(FieldChecks.assess(s,op,"START").issues.length()>0);}
 public void testUnknownTypedLabelsRemainUnconfirmed() throws Exception {JSONObject d=FieldChecks.extract("채낚기. 세 명.","START").put("work_type","모름");assertTrue(FieldChecks.issues(d).length()>0);}

 public void testProgressQueueFailureRetryAndCompletion() throws Exception {
  assertNull(CheckProgress.read(s,op,"",""));
  String r=voice("START",null,false);assertTrue(CheckProgress.read(s,op,"","").busy);assertTrue(CheckProgress.pending(s,op,"",""));
  String attempt=s.beginTranscription(r,"fixture",OfflineAsr.SHA,"test");assertTrue(CheckProgress.read(s,op,"","").busy);
  s.failTranscription(attempt,"CANCELLED","cancel");assertFalse(CheckProgress.read(s,op,"","").busy);assertTrue(CheckProgress.read(s,op,"","").title.contains("못했어요"));
  assertTrue(CheckProgress.read(s,op,"",r).busy);
  transcribe(r,"통발을 걷습니다. 작업 인원 세 명입니다.");assertFalse(CheckProgress.read(s,op,"","").busy);assertEquals("필수 항목\n확인 완료",CheckProgress.read(s,op,"","").title);
  assertTrue(CheckProgress.pending(s,op,r,"")); // retry still running after an earlier successful pass
  voice("END","꽃게를 잡았습니다.",false);assertEquals("확인할 항목이 있어요",CheckProgress.read(s,op,"","").title);
  assertNull(CheckProgress.read(s,null,"",""));
 }
}
