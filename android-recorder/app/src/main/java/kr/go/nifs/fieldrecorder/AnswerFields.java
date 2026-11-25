package kr.go.nifs.fieldrecorder;

import org.json.*;
import java.util.regex.*;

/** Raw answer text is never rewritten. Only narrow short replies use saved question context. */
final class AnswerFields {
 static JSONObject extract(Store s,JSONObject r,String text) throws JSONException {
  if(RecognitionPolicy.repetitive(text))return GearLog.extract(s,r,"").put("recognition_uncertain",true);
  JSONObject result=GearLog.extract(s,r,text),event=FieldChecks.event(s,MissingQuestions.ANSWER,r.getString("id"),null,null);if(event==null)return result;
  JSONObject context=new JSONObject(event.getString("detail"));String key=context.getString("key"),type=result.getString("gear_type"),unit=GearLog.unit(type);
  String shortText=text.trim().replaceAll("[.!?。]+$","").trim();boolean applied=false;
  if(key.equals("haul_quantity")||key.equals("redeploy_quantity")){
   // A gear-count answer is not an unnamed catch. Preserve explicit fish/catch statements.
   boolean mentionsCatch=shortText.matches("(?s).*(?:어획|잡|마리|킬로|키로|kg|그램).*");for(String fish:FisheryExtractor.SPECIES)mentionsCatch|=shortText.contains(fish);
   if(!mentionsCatch)result.put("catches",new JSONArray()).put("outcome","UNCONFIRMED");
   else {
    JSONArray rows=result.getJSONArray("catches"),kept=new JSONArray();
    for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);boolean unnamedGearCount=row.isNull("species")&&unit.equals(row.optString("unit"))&&!row.getJSONArray("evidence").toString().matches("(?s).*(?:어획|잡았|잡힌|잡혔).*");if(!unnamedGearCount)kept.put(row);}
    result.put("catches",kept);if(kept.length()==0)result.put("outcome","UNCONFIRMED");
   }
   String action=key.equals("redeploy_quantity")?"(?:(?:다시\\s*)?(?:투망한|설치한|놓은|넣은)|재투망한|재설치한)":"(?:걷은|거둔|양망한|회수한)";
   Matcher m=Pattern.compile("^(?:(?:이번에|오늘)\\s*)?(?:"+action+"\\s*)?(?:"+Pattern.quote(GearLog.name(type))+"(?:은|는|이|가)?\\s*)?("+KoreanNumber.PATTERN+")\\s*(개|폭)?\\s*(?:입니다|예요|이에요|이요|요)?$").matcher(shortText);
   if(m.matches()&&(m.group(2)==null||unit.equals(m.group(2)))){Double count=KoreanNumber.parse(m.group(1));if(count!=null&&count>0&&count==Math.rint(count)&&count<=1_000_000){result.put(key,count.longValue());if(key.equals("redeploy_quantity"))result.put("disposition","REDEPLOYED");applied=true;}}
  }else if(key.equals("deployment_date")){
   // The question already identifies the date's role. Read only an explicit date; never fix raw words.
   shortText=shortText.replaceFirst("(?:날)?(?:에)?\\s*(?:투망|도망|설치)(?:했습니다|했어요)$","").trim();
   if(shortText.matches("(?:[0-9영일이삼사오육칠팔구십백천년월일\\s]+|오늘|어제|그제|그저께|사흘\\s*전|이틀\\s*전|나흘\\s*전)(?:입니다|예요|이에요|이요|요)?")){
    String date=shortText.replaceFirst("(?:입니다|예요|이에요|이요|요)$","");JSONObject candidate=GearLog.extract(date+"에 설치한 "+GearLog.name(type),type,result.getString("recorded_date"));
    result.put("deployment_date_candidate",candidate.get("deployment_date_candidate")).put("date_status",candidate.get("date_status"));applied=!candidate.isNull("deployment_date_candidate");
   }
  }else if(key.equals("disposition")){
   if(shortText.matches("(?:다시\\s*(?:놓|넣)았습니다|다시\\s*(?:설치|투망)했습니다|재투망했습니다|재설치했습니다)")){result.put("disposition","REDEPLOYED");applied=true;}
   else if(shortText.matches("(?:전부|모두|최종)\\s*철거(?:했습니다|했어요)")){result.put("disposition","REMOVED");applied=true;}
  }else if(key.equals("outcome")){
   if(shortText.matches("(?:네|예|있습니다|있어요|잡았습니다)")){result.put("outcome","CAUGHT");applied=true;}
   else if(shortText.matches("(?:없습니다|없어요|못\\s*잡았습니다|어획이\\s*없습니다)")){result.put("outcome","NO_CATCH");applied=true;}
  }else if(key.startsWith("catch_amount:")&&!context.isNull("species")){
   String species=context.getString("species"),number=KoreanNumber.PATTERN;
   if(shortText.matches("(?:"+number+"\\s*(?:마리|상자|킬로그램|키로그램|킬로|키로|kg|그램|g)[,\\s]*(?:이고|이며|에|고)?\\s*){1,2}(?:입니다|예요|이에요|이요|요)?")){
    JSONObject parsed=FieldChecks.extract(species+" "+shortText,"END");result.put("catches",parsed.getJSONArray("catches")).put("outcome",parsed.getString("outcome"));applied=true;
   }
  }
  result.put("answer_context",new JSONObject().put("key",key).put("question",context.getString("text")).put("species",context.opt("species")).put("short_reply_interpreted",applied).put("context_event_id",event.getString("id")));
  if(applied)result.getJSONObject("evidence").put("question_answer",new JSONArray().put(context.getString("text")).put(text));
  return result;
 }
}
