package kr.go.nifs.fieldrecorder;

import org.json.*;
import java.util.*;

/** Stable question identities tie a short answer to exactly one missing field. */
final class MissingQuestions {
 static final String ANSWER="FIELD_ANSWER_CONTEXT",DEFER="FIELD_QUESTION_DEFERRED";
 static final class Question {
  final String key,text,audio,species;
  Question(String k,String t,String a,String s){key=k;text=t;audio=a;species=s;}
  JSONObject json() throws JSONException {return new JSONObject().put("key",key).put("text",text).put("audio",audio).put("species",species==null?JSONObject.NULL:species);}
 }
 static String particle(String name){char c=name.charAt(name.length()-1);return c>='가'&&c<='힣'&&(c-'가')%28!=0?"은":"는";}
 static List<Question> all(FieldChecks.Check c) throws JSONException {
  ArrayList<Question> out=new ArrayList<>();if(!"WORK".equals(c.phase)||c.pending)return out;JSONObject d=c.data;boolean pot="POT".equals(d.optString("gear_type"));String gear=pot?"통발":"자망",unit=pot?"개":"폭";
  if(d.isNull("deployment_date"))out.add(new Question("deployment_date","이번에 걷은 "+gear+"은 언제 놓았습니까?",pot?"date_pot":"date_net",null));
  if(d.isNull("haul_quantity"))out.add(new Question("haul_quantity","이번에 걷은 "+gear+"은 몇 "+unit+"입니까?",pot?"haul_pot":"haul_net",null));
  if("UNCONFIRMED".equals(d.optString("disposition")))out.add(new Question("disposition","어구를 다시 놓았습니까, 전부 철거했습니까?","disposition",null));
  if("REDEPLOYED".equals(d.optString("disposition"))&&d.isNull("redeploy_quantity"))out.add(new Question("redeploy_quantity","다시 놓은 "+gear+"은 몇 "+unit+"입니까?",pot?"reset_pot":"reset_net",null));
  if("UNCONFIRMED".equals(d.optString("outcome")))out.add(new Question("outcome","이번에 잡은 어획물이 있습니까?","outcome",null));
  if(!Arrays.asList("NO_CATCH","NOT_FISHED").contains(d.optString("outcome"))){
   JSONArray catches=d.getJSONArray("catches");if(catches.length()==0)out.add(new Question("catches","어떤 어종을 몇 마리 또는 몇 킬로그램 잡았습니까?","catches",null));
   Set<String> added=new HashSet<>();
   for(int i=0;i<catches.length();i++){JSONObject row=catches.getJSONObject(i);if("NO_CATCH".equals(row.optString("outcome")))continue;
    String species=FieldChecks.blank(row,"species")?null:row.optString("species");
    if(species==null){if(added.add("unknown"))out.add(new Question("catch_species","어종 이름과 어획량을 함께 말씀해 주세요.","catch_species",null));continue;}
    boolean quantity=row.optDouble("quantity",0)>0&&!FieldChecks.blank(row,"unit"),weight=row.optDouble("weight_kg",0)>0;
    if(!quantity&&!weight&&added.add(species)){
     int index=Arrays.asList(FisheryExtractor.SPECIES).indexOf(species);
     out.add(new Question("catch_amount:"+species,index<0?"이 어종은 몇 마리 또는 몇 킬로그램입니까?":species+particle(species)+" 몇 마리 또는 몇 킬로그램입니까?",index<0?"catch_amount":"catch_"+index,species));
    }
   }
  }
  if(out.isEmpty()&&c.issues.length()>0)out.add(new Question("review","확인이 필요한 내용이 있습니다. 기록을 다시 확인해 주세요.","review",null));
  return out;
 }
 static String fingerprint(FieldChecks.Check c,Question q) throws JSONException {
  JSONObject d=c.data;JSONArray a=new JSONArray().put(q.key);
  if(q.key.startsWith("catch_amount:")){JSONArray rows=d.getJSONArray("catches");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(q.species.equals(r.optString("species")))a.put(r);}a.put(d.get("outcome"));}
  else if(q.key.startsWith("catch"))a.put(d.get("catches")).put(d.get("outcome"));
  else if(q.key.equals("deployment_date"))a.put(d.opt("deployment_date")).put(d.opt("deployment_date_candidate")).put(d.opt("date_status"));
  else if(q.key.equals("review"))a.put(c.issues);
  else a.put(d.opt(q.key));
  return a.toString();
 }
 static boolean deferred(Store s,FieldChecks.Check c,Question q) throws JSONException {
  JSONArray rows=s.rows("events");for(int i=rows.length()-1;i>=0;i--){JSONObject e=rows.getJSONObject(i);if(!DEFER.equals(e.optString("type"))||!c.operation.equals(e.optString("operation_id")))continue;JSONObject detail=new JSONObject(e.getString("detail"));if(q.key.equals(detail.optString("key")))return fingerprint(c,q).equals(detail.optString("field_fingerprint"));}return false;
 }
 static List<Question> available(Store s,FieldChecks.Check c,boolean includeDeferred) throws JSONException {
  ArrayList<Question> result=new ArrayList<>();for(Question q:all(c))if(includeDeferred||!deferred(s,c,q))result.add(q);return result;
 }
 static Question current(Store s,String op,JSONObject request) throws JSONException {
  FieldChecks.Check c=FieldChecks.assess(s,op,"WORK");for(Question q:all(c))if(q.key.equals(request.optString("key"))){if(!fingerprint(c,q).equals(request.optString("field_fingerprint")))break;return q;}
  throw new IllegalStateException("확인 내용이 바뀌었습니다. 질문을 다시 열어 주세요.");
 }
 static JSONObject request(FieldChecks.Check c,Question q) throws JSONException {return q.json().put("version",1).put("phase","WORK").put("field_fingerprint",fingerprint(c,q)).put("sources",c.sources);}
 static void defer(Store s,FieldChecks.Check c,Question q) throws JSONException {FieldChecks.log(s,c,DEFER,request(c,q).put("reason","UNKNOWN_OR_LATER"));}
}
