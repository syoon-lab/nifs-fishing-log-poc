package kr.go.nifs.fieldrecorder;
import org.json.*;
import java.util.*;

/** One bounded retry for uncertain catches or pathological repetition; originals remain saved. */
final class RecognitionPolicy {
 static final String RETRY_ENGINE=OfflineAsr.ENGINE_BASE+";audio_ctx=1500;quality-retry-v2;bounded-segments-v1";
 static boolean repetitive(String text){
  Map<String,Integer> counts=new HashMap<>();int total=0,max=0;
  for(String clause:text.split("[.!?。\\n]+")){String key=clause.replaceAll("\\s+","");if(key.length()<3)continue;total++;int n=counts.getOrDefault(key,0)+1;counts.put(key,n);max=Math.max(max,n);}
  return max>=4&&max*2>total;
 }
 static boolean needsFullContext(JSONObject result,String phase) throws JSONException {
  if(result.optInt("audio_ctx",0)==0)return false;
  if(("WORK".equals(phase)||"END".equals(phase))&&repetitive(result.optString("text")))return true;
  if(!"END".equals(phase))return false;
  JSONObject fields=FisheryExtractor.extract(result.getString("text"),phase);
  boolean uncertain=false;JSONArray warnings=fields.getJSONArray("warnings");
  for(int i=0;i<warnings.length();i++)if(warnings.getString(i).startsWith("범위·정정·불확실한 수량"))uncertain=true;
  if(!uncertain)return false;
  JSONArray catches=fields.getJSONArray("catches");
  for(int i=0;i<catches.length();i++)if(catches.getJSONObject(i).isNull("species"))return true;
  return false;
 }
}
