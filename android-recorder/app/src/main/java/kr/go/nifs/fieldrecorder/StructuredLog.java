package kr.go.nifs.fieldrecorder;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.util.*;

/** Typed analytical columns plus the complete extraction evidence, always append-only. */
final class StructuredLog {
 static final String[] FIELDS={"work_type","target_species","gear_quantity","gear_unit","workers","outcome","notes_state","notes_text"};
 static JSONObject extract(String source,String phase) throws JSONException {
  try{JSONObject data=FisheryExtractor.extract(source,phase);validate(data);return data;}
  catch(JSONException|IllegalArgumentException e){JSONObject empty=FisheryExtractor.extract("",phase);empty.getJSONArray("warnings").put("항목 자동 추출을 확인하세요: "+e.getMessage());return empty;}
 }
 static void schema(SQLiteDatabase db){
  db.execSQL("CREATE TABLE structured_logs(id TEXT PRIMARY KEY,transcription_id TEXT NOT NULL REFERENCES transcriptions(id),review_id TEXT UNIQUE REFERENCES transcript_reviews(id),created_ms INTEGER NOT NULL,extractor_version TEXT NOT NULL,source_text TEXT NOT NULL,phase TEXT NOT NULL,status TEXT NOT NULL,work_type TEXT,target_species TEXT,gear_quantity REAL,gear_unit TEXT,workers INTEGER,outcome TEXT NOT NULL,notes_state TEXT NOT NULL,notes_text TEXT,data_json TEXT NOT NULL)");
  db.execSQL("CREATE INDEX structured_transcript ON structured_logs(transcription_id)");
  db.execSQL("CREATE TABLE catch_items(id TEXT PRIMARY KEY,structured_log_id TEXT NOT NULL REFERENCES structured_logs(id),row_no INTEGER NOT NULL,species TEXT,quantity REAL,unit TEXT,weight_kg REAL,outcome TEXT NOT NULL,approximate INTEGER NOT NULL,evidence_json TEXT NOT NULL,data_json TEXT NOT NULL,UNIQUE(structured_log_id,row_no))");
 }
 private static void number(JSONObject data,String key,boolean integer) throws JSONException {
  if(data.isNull(key))return;double n=data.getDouble(key);if(!Double.isFinite(n)||n<0||n>1_000_000_000||integer&&n!=Math.rint(n))throw new IllegalArgumentException(FisheryExtractor.label(key)+": 올바른 수량을 입력하세요.");
 }
 static void validate(JSONObject data) throws JSONException {
  if(!Arrays.asList("common-fields-ko/1.0","common-fields-ko/1.1",FisheryExtractor.VERSION).contains(data.getString("version"))&&!GearLog.supportsVersion(data.getString("version")))throw new IllegalArgumentException("알 수 없는 추출 버전");
  if(!Arrays.asList("START","END","NOTE","LEGACY","WORK").contains(data.getString("phase")))throw new IllegalArgumentException("녹음 단계 오류");
  if("WORK".equals(data.getString("phase"))){if(!GearLog.supportsVersion(data.getString("version")))throw new IllegalArgumentException("자망·통발 추출 버전 오류");GearLog.validate(data);}
  else if(GearLog.supportsVersion(data.getString("version")))throw new IllegalArgumentException("자망·통발 녹음 단계 오류");
  for(String key:FIELDS)data.get(key);data.getBoolean("gear_approximate");
  number(data,"gear_quantity",true);number(data,"workers",true);
  if(!Arrays.asList("UNCONFIRMED","CAUGHT","NO_CATCH","NOT_FISHED").contains(data.getString("outcome")))throw new IllegalArgumentException("어획 상태 오류");
  if(!Arrays.asList("NOT_MENTIONED","EXPLICIT_NONE","REPORTED").contains(data.getString("notes_state")))throw new IllegalArgumentException("특이사항 상태 오류");
  JSONArray rows=data.getJSONArray("catches");if(rows.length()>1000)throw new IllegalArgumentException("어획 항목이 너무 많습니다.");
  boolean positive=false;
  for(int i=0;i<rows.length();i++){
   JSONObject c=rows.getJSONObject(i);for(String key:new String[]{"species","quantity","unit","weight_kg","outcome"})c.get(key);number(c,"quantity","마리".equals(c.optString("unit")));number(c,"weight_kg",false);
   if(!Arrays.asList("UNCONFIRMED","CAUGHT","NO_CATCH").contains(c.getString("outcome")))throw new IllegalArgumentException("어종별 어획 상태 오류");
   if(!c.isNull("quantity")&&c.isNull("unit"))throw new IllegalArgumentException("수량의 단위를 입력하세요.");
   if(!c.isNull("unit")&&!Arrays.asList("마리","개","상자","통","포대","바구니").contains(c.getString("unit")))throw new IllegalArgumentException("단위: 마리·개·상자·통·포대·바구니 중 입력하세요.");
   boolean hasPositive=c.optDouble("quantity",0)>0||c.optDouble("weight_kg",0)>0;positive|=hasPositive;
   if("NO_CATCH".equals(c.getString("outcome"))&&hasPositive)throw new IllegalArgumentException("무어획 항목에 양수가 있습니다.");
   c.getJSONArray("evidence");c.getBoolean("approximate");
  }
  // An ambiguous automatic draft is retained; a contradictory explicit outcome must never be confirmed.
  if(positive&&Arrays.asList("NO_CATCH","NOT_FISHED").contains(data.getString("outcome")))throw new IllegalArgumentException("어획량과 전체 무어획/미조업 상태가 다릅니다.");
  data.getJSONObject("evidence");data.getJSONArray("warnings");
 }
 private static void put(ContentValues v,JSONObject o,String key) throws JSONException {
  if(o.isNull(key))v.putNull(key);else {Object x=o.get(key);if(x instanceof Number)v.put(key,((Number)x).doubleValue());else v.put(key,x.toString());}
 }
 static String insert(SQLiteDatabase db,String tid,String review,String source,JSONObject data,String status) throws JSONException {
  validate(data);String id=Store.id();ContentValues v=new ContentValues();v.put("id",id);v.put("transcription_id",tid);v.put("review_id",review);v.put("created_ms",System.currentTimeMillis());v.put("extractor_version",data.getString("version"));v.put("source_text",source);v.put("phase",data.getString("phase"));v.put("status",status);
  for(String key:FIELDS)put(v,data,key);v.put("data_json",data.toString());db.insertOrThrow("structured_logs",null,v);
  JSONArray rows=data.getJSONArray("catches");for(int i=0;i<rows.length();i++){
   JSONObject c=rows.getJSONObject(i);v=new ContentValues();v.put("id",Store.id());v.put("structured_log_id",id);v.put("row_no",i+1);
   for(String key:new String[]{"species","quantity","unit","weight_kg","outcome"})put(v,c,key);
   v.put("approximate",c.getBoolean("approximate")?1:0);v.put("evidence_json",c.getJSONArray("evidence").toString());v.put("data_json",c.toString());db.insertOrThrow("catch_items",null,v);
  }GearLog.insert(db,id,data);return id;
 }
 static String summary(JSONObject d) throws JSONException {
  StringBuilder s=new StringBuilder();if("WORK".equals(d.optString("phase")))s.append(GearLog.summary(d));
  if(!"WORK".equals(d.optString("phase"))){if(!d.isNull("target_species"))s.append("목표 ").append(d.getString("target_species")).append(" · ");
  if(!d.isNull("work_type"))s.append(d.getString("work_type")).append(" · ");
  if(!d.isNull("gear_quantity"))s.append(format(d.getDouble("gear_quantity"))).append(d.optString("gear_unit","")).append(d.optBoolean("gear_approximate")?" 정도":"").append(" · ");
  if(!d.isNull("workers"))s.append(format(d.getDouble("workers"))).append("명");
  }if(s.length()>0)s.append('\n');
  JSONArray rows=d.getJSONArray("catches");for(int i=0;i<rows.length();i++){JSONObject c=rows.getJSONObject(i);s.append(c.isNull("species")?"어종 미확인":c.getString("species"));if(!c.isNull("quantity"))s.append(' ').append(format(c.getDouble("quantity"))).append(c.optString("unit",""));if(!c.isNull("weight_kg"))s.append(" / ").append(format(c.getDouble("weight_kg"))).append(" kg");else s.append(" / 무게 미확인");if(c.optBoolean("approximate"))s.append(" (약)");s.append('\n');}
  if("NO_CATCH".equals(d.getString("outcome")))s.append("전체 무어획\n");if("NOT_FISHED".equals(d.getString("outcome")))s.append("조업 안 함\n");
  if("REPORTED".equals(d.getString("notes_state")))s.append(d.optString("notes_text",""));return s.toString().trim();
 }
 static String format(double n){return n==Math.rint(n)?Long.toString((long)n):Double.toString(n);}
}
