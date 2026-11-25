package kr.go.nifs.fieldrecorder;

import org.json.*;
import java.util.*;

/** Field triage only. Never turns a missing recognition into a claim of missing speech.
 * Versioned, append-only events keep schema 4 and all original rows/audio compatible. */
final class FieldChecks {
 static final String REQUEST="FIELD_CHECK_REQUESTED", SUPPLEMENT="FIELD_SUPPLEMENT", REVIEW="FIELD_REVIEW", DEFER="FIELD_DEFERRED", SHOWN="FIELD_NOTICE_SHOWN";
 static final class Check {
  String operation,phase,signature; JSONObject data; JSONArray sources=new JSONArray(),issues=new JSONArray();
  boolean pending,reviewed; int recordings;
  String title(){return "WORK".equals(phase)?"작업 기록":"START".equals(phase)?"시작 기록":"마무리 기록";}
  String message(){StringBuilder b=new StringBuilder();for(int i=0;i<issues.length();i++){if(b.length()>0)b.append('\n');b.append("• ").append(shortIssue(issues.optString(i)));}return b.length()==0?"필수 항목이 채워졌습니다.":b.toString();}
 }
 static String shortIssue(String message){
  if(message.startsWith("음성을 확인하지"))return "음성 확인 미완료";
  if(message.startsWith("음성 항목 확인 중"))return "음성 항목 확인 중";
  if(message.startsWith("어떤 작업인지"))return "작업 내용 확인 필요";
  if(message.startsWith("작업 인원을"))return "작업 인원 확인 필요";
  if(message.startsWith("어획이 있었는지"))return "어획 유무 확인 필요";
  if(message.startsWith("어종과 수량"))return "어종·수량 또는 무게 확인 필요";
  return message;
 }
 static boolean required(String phase){return "START".equals(phase)||"END".equals(phase)||"WORK".equals(phase);}
 static String effectivePhase(Store s,JSONObject r) throws JSONException {
  if(required(r.optString("phase")))return r.getString("phase");
  JSONObject link=event(s,SUPPLEMENT,r.optString("id"),null,null);
  return link==null?r.optString("phase"):new JSONObject(link.getString("detail")).getString("phase");
 }
 static JSONObject event(Store s,String type,String recording,String operation,String phase) throws JSONException {
  JSONArray events=s.rows("events");for(int i=events.length()-1;i>=0;i--){JSONObject e=events.getJSONObject(i);
   if(!type.equals(e.optString("type"))||recording!=null&&!recording.equals(e.optString("recording_id"))||operation!=null&&!operation.equals(e.optString("operation_id")))continue;
   if(phase!=null&&!phase.equals(new JSONObject(e.getString("detail")).optString("phase")))continue;return e;
  }return null;
 }
 static void request(Store s,String rid) throws JSONException {JSONObject r=s.row("recordings",rid);if(required(effectivePhase(s,r))&&event(s,REQUEST,rid,null,null)==null)s.event(r.getString("session_id"),rid,r.getString("operation_id"),REQUEST,new JSONObject().put("version",1).put("phase",effectivePhase(s,r)).toString());}
 static String queued(Store s) throws JSONException {
  JSONArray events=s.rows("events");for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);if(!REQUEST.equals(e.optString("type")))continue;
   String rid=e.getString("recording_id");JSONObject r=s.row("recordings",rid);
   if(!"INTERRUPTED".equals(s.row("sessions",r.getString("session_id")).optString("status"))&&"COMPLETED".equals(r.optString("status"))&&s.latest("transcriptions","recording_id",rid)==null&&event(s,"FIELD_ASR_ATTEMPTED",rid,null,null)==null&&!manuallyCovered(s,r)&&!assess(s,r.getString("operation_id"),effectivePhase(s,r)).reviewed)return rid;
  }return null;
 }
 static boolean manuallyCovered(Store s,JSONObject r) throws JSONException {
  JSONObject e=event(s,REVIEW,null,r.getString("operation_id"),effectivePhase(s,r));if(e==null)return false;
  JSONArray sources=new JSONObject(e.getString("detail")).getJSONArray("sources");for(int i=0;i<sources.length();i++)if(r.getString("id").equals(sources.getJSONObject(i).getString("recording_id")))return true;return false;
 }
 static Check assess(Store s,String op,String phase) throws JSONException {
  Check c=new Check();c.operation=op;c.phase=phase;StringBuilder source=new StringBuilder();Map<String,String> texts=new LinkedHashMap<>();JSONObject confirmed=null;boolean recognitionFailed=false;JSONArray rows=s.rows("recordings");
  for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(!op.equals(r.optString("operation_id"))||!phase.equals(effectivePhase(s,r)))continue;
   String rid=r.getString("id");JSONObject t=s.latest("transcriptions","recording_id",rid),best=null,review=null;
   JSONArray attempts=s.rows("transcriptions");for(int j=0;j<attempts.length();j++){JSONObject a=attempts.getJSONObject(j);if(rid.equals(a.optString("recording_id"))&&"COMPLETED".equals(a.optString("status")))best=a;}
   if(best!=null)review=s.latest("transcript_reviews","transcription_id",best.getString("id"));
   JSONObject structured=review==null?null:s.latest("structured_logs","review_id",review.getString("id"));
   if(structured!=null&&"CONFIRMED".equals(structured.optString("status")))confirmed=new JSONObject(structured.getString("data_json"));
   JSONObject src=new JSONObject().put("recording_id",rid).put("status",r.getString("status")).put("attempt_id",t==null?JSONObject.NULL:t.getString("id")).put("attempt_status",t==null?"WAITING":t.getString("status")).put("source_attempt_id",best==null?JSONObject.NULL:best.getString("id")).put("review_id",review==null?JSONObject.NULL:review.getString("id")).put("structured_id",structured==null?JSONObject.NULL:structured.getString("id"));c.sources.put(src);
   if(!"COMPLETED".equals(r.optString("status")))continue;c.recordings++;
   if(best!=null){String text=review==null?best.optString("text"):review.optString("text");source.append(text).append('\n');texts.put(rid,text);}
   if(t==null&&event(s,"FIELD_ASR_ATTEMPTED",rid,null,null)==null||t!=null&&"RUNNING".equals(t.optString("status")))c.pending=true;
   if(best==null&&!c.pending)recognitionFailed=true;
  }
  c.signature=c.sources.toString();
  if("WORK".equals(phase)){
   JSONObject merged=null;for(int i=0;i<c.sources.length();i++){JSONObject src=c.sources.getJSONObject(i);String rid=src.getString("recording_id");
    JSONObject recording=s.row("recordings",rid);JSONObject fields;
    if(!src.isNull("source_attempt_id")){JSONObject log=s.latest("structured_logs","transcription_id",src.getString("source_attempt_id"));fields=log==null||"DRAFT".equals(log.optString("status"))&&!GearLog.VERSION.equals(log.optString("extractor_version"))?AnswerFields.extract(s,recording,texts.getOrDefault(rid,"")):new JSONObject(log.getString("data_json"));}
    else fields=AnswerFields.extract(s,recording,"");
    merged=merged==null?fields:GearLog.merge(merged,fields);
   }
   c.data=merged==null?GearLog.extract("",GearLog.type(s,op),GearLog.date(s.operation(op).getLong("started_ms"))):merged;
  }else c.data=extract(source.toString(),phase);
  if(c.recordings==1&&confirmed!=null&&phase.equals(confirmed.optString("phase")))c.data=confirmed;
  JSONObject review=event(s,REVIEW,null,op,phase);
  if(review!=null){JSONObject d=new JSONObject(review.getString("detail"));if(c.signature.equals(d.optString("signature"))){c.data=d.getJSONObject("data");c.reviewed=true;}
   else {
    JSONArray previous=d.getJSONArray("sources");Set<String> oldIds=new HashSet<>();boolean unchanged=true;
    for(int i=0;i<previous.length();i++){JSONObject old=previous.getJSONObject(i);oldIds.add(old.getString("recording_id"));boolean found=false;for(int j=0;j<c.sources.length();j++)if(old.toString().equals(c.sources.getJSONObject(j).toString()))found=true;unchanged&=found;}
    if(unchanged&&c.sources.length()>previous.length()){
     StringBuilder extra=new StringBuilder();for(Map.Entry<String,String> e:texts.entrySet())if(!oldIds.contains(e.getKey()))extra.append(e.getValue()).append('\n');
     if("WORK".equals(phase)){
      c.data=new JSONObject(d.getJSONObject("data").toString());
      for(int j=0;j<c.sources.length();j++){String rid=c.sources.getJSONObject(j).getString("recording_id");if(!oldIds.contains(rid))c.data=GearLog.merge(c.data,AnswerFields.extract(s,s.row("recordings",rid),texts.getOrDefault(rid,"")));}
     }else c.data=merge(d.getJSONObject("data"),extract(extra.toString(),phase));
    }
   }
  }
  c.issues=issues(c.data);
  if(c.recordings==0)c.issues.put("저장된 "+c.title()+" 녹음을 확인해 주세요.");
  if(recognitionFailed&&!c.reviewed)c.issues.put("음성을 확인하지 못했습니다. 직접 입력하거나 다시 확인해 주세요.");
  if(c.pending&&!c.reviewed)c.issues.put("음성 항목 확인 중 · 아직 결과가 없습니다.");
  return c;
 }
 static JSONObject extract(String text,String phase) throws JSONException {
  JSONObject d=StructuredLog.extract(text,phase);
  if(!"END".equals(phase))return d;
  // Explicit overall absence, not a named species' absence or an absent special note.
  if(text.matches("(?s).*(?:이번(?:에|에는)?|오늘(?:은)?)?\\s*잡힌\\s*(?:것이|게|고기가)\\s*없(?:습니다|어요|었다|다).*")||text.matches("(?s).*(?:"+String.join("|",FisheryExtractor.SPECIES)+")(?:도|는|은)\\s*없고\\s*다른\\s*어획물도\\s*없(?:습니다|어요|다).*") ){
   boolean positive=false;JSONArray a=d.getJSONArray("catches");for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);positive|=c.optDouble("quantity",0)>0||c.optDouble("weight_kg",0)>0;}
   d.put("outcome",positive?"UNCONFIRMED":"NO_CATCH");
  }
  JSONArray catches=d.getJSONArray("catches");
  if(!"NO_CATCH".equals(d.optString("outcome"))&&!"NOT_FISHED".equals(d.optString("outcome"))){
   // A named catch without an amount must not disappear from the checklist.
   for(String clause:text.split("[。!?\\n]|(?<![0-9])\\.(?![0-9])")){
    if(clause.matches(".*(?:목표|대상\\s*어종).*"))continue;
    for(String fish:FisheryExtractor.SPECIES){
     if(!java.util.regex.Pattern.compile("(?<![가-힣])"+fish+"(?=$|[\\s0-9,.]|은|는|을|를|이|가|와|하고|도)").matcher(clause).find())continue;
     boolean found=false;for(int i=0;i<catches.length();i++)if(fish.equals(catches.getJSONObject(i).optString("species")))found=true;
     if(!found)catches.put(new JSONObject().put("species",fish).put("quantity",JSONObject.NULL).put("unit",JSONObject.NULL).put("weight_kg",JSONObject.NULL).put("outcome","UNCONFIRMED").put("approximate",false).put("evidence",new JSONArray().put(clause)));
    }
   }
   // A combined weight cannot be allocated to the last species mentioned.
   if(catches.length()>1)for(int i=0;i<catches.length();i++){
    JSONObject row=catches.getJSONObject(i);JSONArray evidence=row.getJSONArray("evidence");
    for(int j=0;j<evidence.length();j++)if(evidence.getString(j).matches(".*(?:총|전체)\\s*(?:무게|중량).*")){
     if(!row.isNull("weight_kg"))d.put("unallocated_total_weight_kg",row.get("weight_kg"));row.put("weight_kg",JSONObject.NULL);break;
    }
   }
  }
  return d;
 }
 static JSONObject merge(JSONObject base,JSONObject extra) throws JSONException {
  JSONObject merged=new JSONObject(base.toString());
  JSONArray warnings=extra.optJSONArray("warnings");if(warnings!=null)for(int i=0;i<warnings.length();i++)if(warnings.optString(i).matches(".*(?:범위·정정|서로 다른|충돌).*"))merged.put("additional_uncertainty",true);
  for(String key:new String[]{"work_type","workers","gear_quantity","gear_unit"}){
   if(extra.isNull(key))continue;
   if(merged.isNull(key))merged.put(key,extra.get(key));
   else if(!StructuredIntegrity.equal(merged.get(key),extra.get(key)))merged.put(key,JSONObject.NULL);
  }
  if("END".equals(base.optString("phase"))){
   String old=base.getString("outcome"),next=extra.getString("outcome");
   if(!"UNCONFIRMED".equals(next))merged.put("outcome","UNCONFIRMED".equals(old)||old.equals(next)?next:"UNCONFIRMED");
   JSONArray target=merged.getJSONArray("catches"),addition=extra.getJSONArray("catches");
   for(int i=0;i<addition.length();i++){
    JSONObject r=addition.getJSONObject(i),match=null;
    for(int j=0;j<target.length();j++)if(!r.isNull("species")&&r.get("species").equals(target.getJSONObject(j).get("species")))match=target.getJSONObject(j);
    if(match==null){target.put(new JSONObject(r.toString()));continue;}
    for(String key:new String[]{"quantity","unit","weight_kg"}){if(r.isNull(key))continue;if(match.isNull(key))match.put(key,r.get(key));else if(!StructuredIntegrity.equal(match.get(key),r.get(key))){match.put(key,JSONObject.NULL);merged.put("outcome","UNCONFIRMED");}}
   }
  }
  return merged;
 }
 static boolean blank(JSONObject d,String key){return d.isNull(key)||Arrays.asList("","미확인","미확인 어종","미상","모름","알 수 없음").contains(d.optString(key).trim());}
 static JSONArray issues(JSONObject d) throws JSONException {
  if("WORK".equals(d.optString("phase")))return GearLog.issues(d);
  JSONArray out=new JSONArray();if(d.optBoolean("additional_uncertainty"))out.put("추가 녹음의 서로 다른 수량을 확인해 주세요.");String phase=d.getString("phase");
  if("START".equals(phase)){
   if(blank(d,"work_type"))out.put("어떤 작업인지 확인해 주세요.");
   if(d.isNull("workers")||d.optDouble("workers",0)<=0)out.put("작업 인원을 확인해 주세요.");
   if(d.isNull("gear_quantity")&&d.has("gear_quantity_candidate"))out.put("말씀하신 어구 수량을 확인해 주세요.");
   if(!d.isNull("gear_quantity")&&blank(d,"gear_unit"))out.put("말씀하신 어구 수량의 단위를 확인해 주세요.");
  }else if("END".equals(phase)){
   String outcome=d.getString("outcome");if("UNCONFIRMED".equals(outcome))out.put("어획이 있었는지 확인해 주세요.");
   if("NO_CATCH".equals(outcome)||"NOT_FISHED".equals(outcome))return out;
   JSONArray catches=d.getJSONArray("catches");if(catches.length()==0)out.put("어종과 수량 또는 무게를 확인해 주세요.");
   for(int i=0;i<catches.length();i++){JSONObject row=catches.getJSONObject(i);if("NO_CATCH".equals(row.optString("outcome")))continue;
    String name=blank(row,"species")?"미확인 어종":row.getString("species");
    if(blank(row,"species")||"미확인 어종".equals(name))out.put("어종 이름을 확인해 주세요.");
    boolean quantity=row.optDouble("quantity",0)>0&&!blank(row,"unit"),weight=row.optDouble("weight_kg",0)>0;
    if(!quantity&&!weight)out.put(name+"의 수량·단위 또는 무게를 확인해 주세요.");
   }
  }return out;
 }
 static void saveReview(Store s,Check expected,JSONObject data) throws JSONException {
  synchronized(s){Check current=assess(s,expected.operation,expected.phase);if(!current.signature.equals(expected.signature))throw new IllegalStateException("새 확인 결과가 도착했습니다. 화면을 다시 열어 주세요.");
   if(!expected.phase.equals(data.getString("phase")))throw new IllegalArgumentException("단계 불일치");JSONObject validation=new JSONObject(data.toString());JSONArray rows=validation.getJSONArray("catches");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(!r.isNull("quantity")&&blank(r,"unit"))r.put("unit","개");}StructuredLog.validate(validation);
   log(s,current,REVIEW,new JSONObject().put("data",data).put("previous_data",current.data).put("issues",issues(data)).put("review_scope","FIELD_ONLY_NOT_PC_FINAL"));
  }
 }
 static void log(Store s,Check c,String type,JSONObject detail) throws JSONException {
  detail.put("version",1).put("phase",c.phase).put("signature",c.signature).put("sources",c.sources);
  JSONObject op=s.operation(c.operation);s.event(op.getString("session_id"),null,c.operation,type,detail.toString());
 }
 static boolean seen(Store s,Check c) throws JSONException {
  for(String type:new String[]{SHOWN,DEFER,REVIEW}){JSONObject e=event(s,type,null,c.operation,c.phase);if(e!=null&&c.signature.equals(new JSONObject(e.getString("detail")).optString("signature")))return true;}return false;
 }
 static String[] phases(Store s,String op) throws JSONException {return GearLog.isWork(s,op)?new String[]{"WORK"}:new String[]{"START","END"};}
 static String fingerprint(Store s,String op) throws JSONException {StringBuilder b=new StringBuilder();for(String phase:phases(s,op)){Check c=assess(s,op,phase);b.append(c.signature).append(c.data.toString()).append("|");}return b.toString();}
 static JSONArray operationIssues(Store s,String op) throws JSONException {JSONArray out=new JSONArray();for(String phase:phases(s,op)){Check c=assess(s,op,phase);for(int i=0;i<c.issues.length();i++)out.put(c.title()+": "+c.issues.getString(i));}return out;}
}
