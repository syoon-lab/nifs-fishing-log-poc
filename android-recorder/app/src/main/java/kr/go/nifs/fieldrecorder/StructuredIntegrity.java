package kr.go.nifs.fieldrecorder;
import org.json.*;
import java.io.IOException;
import java.util.*;
final class StructuredIntegrity {
 private static Map<String,JSONObject> index(JSONObject data,String key) throws Exception {Map<String,JSONObject> map=new HashMap<>();JSONArray a=data.getJSONArray(key);for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);if(map.put(r.getString("id"),r)!=null)throw new IOException("항목 식별자 중복");}return map;}
 static boolean equal(Object a,Object b){if(a instanceof Number&&b instanceof Number)return Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0;return Objects.equals(a,b);}
 static void validate(JSONObject data) throws Exception {
  Map<String,JSONObject> logs=index(data,"structured_logs"),items=index(data,"catch_items"),attempts=index(data,"transcriptions"),reviews=index(data,"transcript_reviews"),voices=index(data,"recordings");
  Map<String,JSONObject> gearByLog=new HashMap<>();for(JSONObject detail:index(data,"gear_details").values()){
   if(!logs.containsKey(detail.getString("structured_log_id"))||gearByLog.put(detail.getString("structured_log_id"),detail)!=null)throw new IOException("어구 항목 연결 오류");
  }
  Set<String> usedReviews=new HashSet<>();Map<String,Map<Integer,JSONObject>> grouped=new HashMap<>();
  for(JSONObject c:items.values()){
   if(!logs.containsKey(c.getString("structured_log_id")))throw new IOException("어획 항목의 일지 연결 누락");
   Map<Integer,JSONObject> group=grouped.computeIfAbsent(c.getString("structured_log_id"),x->new HashMap<>());if(group.put(c.getInt("row_no"),c)!=null)throw new IOException("어획 행 중복");
  }
  for(JSONObject l:logs.values()){
   JSONObject t=attempts.get(l.getString("transcription_id"));if(t==null||!"COMPLETED".equals(t.getString("status")))throw new IOException("항목의 전사 연결 오류");
   JSONObject r=voices.get(t.getString("recording_id"));if(!r.getString("phase").equals(l.getString("phase")))throw new IOException("항목의 녹음 단계 오류");
   String source=t.getString("text");if(!l.isNull("review_id")){
    JSONObject review=reviews.get(l.getString("review_id"));if(review==null||!review.getString("transcription_id").equals(t.getString("id"))||!usedReviews.add(review.getString("id")))throw new IOException("항목의 확인본 연결 오류");source=review.getString("text");
   }
   if(!source.equals(l.getString("source_text")))throw new IOException("항목 추출 원문 불일치");
   if(!Arrays.asList("DRAFT","CONFIRMED").contains(l.getString("status"))||"CONFIRMED".equals(l.getString("status"))&&l.isNull("review_id"))throw new IOException("항목 확인 상태 오류");
   JSONObject d=new JSONObject(l.getString("data_json"));StructuredLog.validate(d);
   JSONObject gear=gearByLog.get(l.getString("id"));if("WORK".equals(d.optString("phase"))){if(gear==null)throw new IOException("어구 상세 누락");for(String key:GearLog.COLUMNS)if(!equal(d.get(key),gear.get(key)))throw new IOException("어구 열/JSON 불일치: "+key);}else if(gear!=null)throw new IOException("과거 항목에 어구 상세가 추가됨");
   if(!d.getString("version").equals(l.getString("extractor_version"))||!d.getString("phase").equals(l.getString("phase")))throw new IOException("추출 메타데이터 오류");
   for(String key:StructuredLog.FIELDS)if(!equal(d.get(key),l.get(key)))throw new IOException("항목 열/JSON 불일치: "+key);
   Map<Integer,JSONObject> group=grouped.getOrDefault(l.getString("id"),Collections.emptyMap());JSONArray rows=d.getJSONArray("catches");if(group.size()!=rows.length())throw new IOException("어획 항목 누락");
   for(int i=0;i<rows.length();i++){
    JSONObject c=group.get(i+1),expected=rows.getJSONObject(i);if(c==null)throw new IOException("어획 행 순서 누락");
    JSONObject stored=new JSONObject(c.getString("data_json"));if(!stored.toString().equals(expected.toString()))throw new IOException("어획 JSON 불일치");
    for(String key:new String[]{"species","quantity","unit","weight_kg","outcome"})if(!equal(c.get(key),expected.get(key)))throw new IOException("어획 항목 값 불일치: "+key);
    if(c.getInt("approximate")!=(expected.getBoolean("approximate")?1:0)||!c.getString("evidence_json").equals(expected.getJSONArray("evidence").toString()))throw new IOException("어획 근거 불일치");
   }
  }
 }
}
