package kr.go.nifs.fieldrecorder;
import android.test.AndroidTestCase;
import org.json.JSONObject;
public class RecognitionPolicyTest extends AndroidTestCase {
 public void testPaddingTimestampKeepsRawEvidenceAndAllText() throws Exception {
  JSONObject r=new JSONObject("{\"duration_ms\":5440,\"segments\":[{\"start_ms\":0,\"end_ms\":18720,\"text\":\"통발 40개\"},{\"start_ms\":18720,\"end_ms\":21000,\"text\":\"두 명\"}]}");
  OfflineAsr.boundSegments(r);org.json.JSONArray a=r.getJSONArray("segments");
  assertEquals(2,a.length());assertEquals(5440,a.getJSONObject(0).getLong("end_ms"));assertEquals(18720,a.getJSONObject(0).getLong("decoder_end_ms"));assertEquals("통발 40개",a.getJSONObject(0).getString("text"));
  assertEquals(5440,a.getJSONObject(1).getLong("start_ms"));assertEquals(5440,a.getJSONObject(1).getLong("end_ms"));assertEquals("두 명",a.getJSONObject(1).getString("text"));assertTrue(a.getJSONObject(1).getBoolean("timing_adjusted"));
 }
 public void testValidTimestampIsUnchanged() throws Exception {
  JSONObject r=new JSONObject("{\"duration_ms\":12190,\"segments\":[{\"start_ms\":0,\"end_ms\":12180,\"text\":\"꽃게 20마리\"}]}");
  String before=r.toString();OfflineAsr.boundSegments(r);assertEquals(before,r.toString());
 }
 public void testContextAlwaysCoversCompleteAudio(){
  for(long samples=1;samples<=1800L*16000;samples+=131){int c=OfflineAsr.shortAudioContext(samples);assertTrue(c==0||c>=512&&c<1500&&samples<=c*320L);}
  assertEquals(512,OfflineAsr.shortAudioContext(5*16000));assertEquals(768,OfflineAsr.shortAudioContext(12*16000));assertEquals(1024,OfflineAsr.shortAudioContext(16*16000));assertEquals(0,OfflineAsr.shortAudioContext(30*16000));assertEquals(0,OfflineAsr.shortAudioContext(1800*16000));
 }
 public void testExactParserNeverChangesMisheardNumber(){assertNull(KoreanNumber.parse("신"));assertNull(KoreanNumber.parse("신계"));assertEquals(Integer.valueOf(50),KoreanNumber.vowelCandidate("신"));assertEquals(Integer.valueOf(51),KoreanNumber.vowelCandidate("신한"));assertNull(KoreanNumber.vowelCandidate("쉰"));assertNull(KoreanNumber.vowelCandidate("신규"));assertNull(KoreanNumber.vowelCandidate("시인"));}
 public void testActualErrorIsReviewCandidateOnly() throws Exception {
  JSONObject r=FisheryExtractor.extract("시험 녹음입니다. 통발을 걷기 시작합니다. 목표 어종은 꽃게입니다. 통발 신계 작업인원 세 명입니다.","START");
  assertTrue(r.isNull("gear_quantity"));assertTrue(r.isNull("gear_unit"));assertTrue(r.toString(),r.has("gear_quantity_candidate"));assertEquals(50,r.getInt("gear_quantity_candidate"));assertEquals("개",r.getString("gear_unit_candidate"));assertEquals(3,r.getInt("workers"));assertEquals("꽃게",r.getString("target_species"));StructuredLog.validate(r);
 }
 public void testCandidateWorksForCompoundNumberAndCounterSpelling() throws Exception {JSONObject r=FisheryExtractor.extract("통발 신한 개입니다.","START");assertTrue(r.toString(),r.has("gear_quantity_candidate"));assertEquals(51,r.getInt("gear_quantity_candidate"));assertTrue(r.isNull("gear_quantity"));r=FisheryExtractor.extract("통발 쉰게입니다.","START");assertTrue(r.toString(),r.has("gear_quantity_candidate"));assertEquals(50,r.getInt("gear_quantity_candidate"));assertTrue(r.isNull("gear_quantity"));}
 public void testNoCandidateForPlaceUncertaintyOrWrongPhase() throws Exception {for(String s:new String[]{"신계항에서 출항합니다.","통발 신계항에 있습니다.","통발 신계인지 모르겠습니다.","통발 -신계입니다.","작업 신계입니다."})assertFalse(s,FisheryExtractor.extract(s,"START").has("gear_quantity_candidate"));assertFalse(FisheryExtractor.extract("통발 신계입니다.","END").has("gear_quantity_candidate"));}
 public void testConflictingCandidatesRemainUnconfirmed() throws Exception {JSONObject r=FisheryExtractor.extract("통발 신 개입니다. 통발 신한 개입니다.","START");assertTrue(r.isNull("gear_quantity"));assertFalse(r.has("gear_quantity_candidate"));assertTrue(r.toString(),r.optBoolean("conflict_gear_candidate"));}
 public void testCorrectOtherNumbersRemainExact() throws Exception {for(String s:new String[]{"통발 마흔 개입니다.","통발 예순 개입니다.","통발 오십 개입니다."}){JSONObject r=FisheryExtractor.extract(s,"START");assertFalse(r.isNull("gear_quantity"));assertFalse(r.has("gear_quantity_candidate"));}}
 public void testPunctuationSeparatedCountNeedsReview() throws Exception {JSONObject r=FisheryExtractor.extract("통발, 쉰, 한, 개를 회수합니다. 작업 인원은 세 명입니다.","START");assertTrue(r.toString(),r.isNull("gear_quantity"));assertEquals(51,r.getInt("gear_quantity_candidate"));assertEquals("개",r.getString("gear_unit_candidate"));assertEquals(3,r.getInt("workers"));}
 public void testLastDigitOfSplitCountNeverBecomesOne() throws Exception {JSONObject r=FisheryExtractor.extract("통발 쉰, 한 개입니다.","START");assertTrue(r.toString(),r.isNull("gear_quantity"));assertEquals(51,r.getInt("gear_quantity_candidate"));}
 public void testSeparateCountsAreNotCombined() throws Exception {JSONObject r=FisheryExtractor.extract("통발 50개, 60개입니다.","START");assertTrue(r.isNull("gear_quantity"));assertFalse(r.has("gear_quantity_candidate"));}
 public void testQualityRetryIsBoundedAndOnlyForUncertainUnknownSpecies() throws Exception {
  JSONObject r=new JSONObject().put("text","곶게는 10에서 15마리 정도인 것 같습니다.").put("audio_ctx",512);
  assertTrue(RecognitionPolicy.needsFullContext(r,"END"));assertFalse(RecognitionPolicy.needsFullContext(r,"START"));r.put("audio_ctx",0);assertFalse(RecognitionPolicy.needsFullContext(r,"END"));r.put("audio_ctx",512);
  for(String text:new String[]{"꽃게는 10에서 15마리 정도인 것 같습니다.","두 상자 정도 나왔습니다.","꽃게 스무 마리입니다.","통발 신계 작업 인원 세 명입니다."}){r.put("text",text);assertFalse(text,RecognitionPolicy.needsFullContext(r,"END"));}
 }
 public void testFieldRepetitionGetsOnlyOneFullContextRetry() throws Exception {
  String text=String.join(" ",java.util.Collections.nCopies(19,"어종원 문어."));assertTrue(RecognitionPolicy.repetitive(text));
  JSONObject r=new JSONObject().put("text",text).put("audio_ctx",1024);assertTrue(RecognitionPolicy.needsFullContext(r,"WORK"));r.put("audio_ctx",0);assertFalse(RecognitionPolicy.needsFullContext(r,"WORK"));
  assertFalse(RecognitionPolicy.repetitive("문어 25마리입니다. 문어 25마리입니다."));assertFalse(RecognitionPolicy.needsFullContext(new JSONObject().put("text","투망한 통발은 350개입니다.").put("audio_ctx",512),"WORK"));
 }

}
