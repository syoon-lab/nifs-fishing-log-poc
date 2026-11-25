package kr.go.nifs.fieldrecorder;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Gillnet/pot work only. Spoken dates are candidates until explicitly reviewed.
 * No inferred deployment links, no quantity sums, no worker requirement. */
final class GearLog {
 static final String VERSION="gillnet-pot/1.2",CONTEXT="GEAR_WORK_CONTEXT";
 static final String[] COLUMNS={"gear_type","deployment_date","deployment_date_candidate","date_status","haul_quantity","redeploy_quantity","gear_unit","disposition","recorded_date"};
 static boolean supportsVersion(String version){return "gillnet-pot/1.0".equals(version)||"gillnet-pot/1.1".equals(version)||VERSION.equals(version);}
 static boolean supported(String type){return "GILLNET".equals(type)||"POT".equals(type);}
 static String name(String type){return "GILLNET".equals(type)?"자망":"POT".equals(type)?"통발":"어구 미확인";}
 static String unit(String type){return "GILLNET".equals(type)?"폭":"POT".equals(type)?"개":"";}
 static String date(long ms){return Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();}
 static JSONObject context(Store store,String op) throws JSONException {return FieldChecks.event(store,CONTEXT,null,op,null);}
 static String type(Store store,String op) throws JSONException {JSONObject event=context(store,op);return event==null?null:new JSONObject(event.getString("detail")).getString("gear_type");}
 static boolean isWork(Store store,String op) throws JSONException {return op!=null&&context(store,op)!=null;}
 static JSONObject extract(Store store,JSONObject recording,String text) throws JSONException {
  return extract(text,type(store,recording.getString("operation_id")),date(recording.getLong("started_ms")));
 }
 static void schema(SQLiteDatabase db){db.execSQL("CREATE TABLE gear_details(id TEXT PRIMARY KEY,structured_log_id TEXT NOT NULL UNIQUE REFERENCES structured_logs(id),gear_type TEXT,deployment_date TEXT,deployment_date_candidate TEXT,date_status TEXT NOT NULL,haul_quantity INTEGER,redeploy_quantity INTEGER,gear_unit TEXT,disposition TEXT NOT NULL,recorded_date TEXT NOT NULL)");}
 static void insert(SQLiteDatabase db,String log,JSONObject data) throws JSONException {
  if(!"WORK".equals(data.optString("phase")))return;
  ContentValues v=new ContentValues();v.put("id",Store.id());v.put("structured_log_id",log);
  for(String key:COLUMNS){if(data.isNull(key))v.putNull(key);else if(data.get(key) instanceof Number)v.put(key,data.getLong(key));else v.put(key,data.getString(key));}db.insertOrThrow("gear_details",null,v);
 }
 static JSONObject extract(String source,String type,String recordedDate) throws JSONException {
  if(!supported(type))throw new IllegalArgumentException("자망 또는 통발을 선택하세요.");
  // Interviewer questions are not statements of dates or quantities. Raw source stays in the log.
  source=source.replaceAll("[^.!?。\\n]*(?:습니까|인가요|몇\\s*개.*까요)\\s*[?？]", " ");
  LocalDate reference=LocalDate.parse(recordedDate);
  JSONObject d=FieldChecks.extract(source,"END");
  d.put("version",VERSION).put("phase","WORK").put("workers",JSONObject.NULL).put("target_species",JSONObject.NULL).put("gear_quantity",JSONObject.NULL).put("gear_unit",unit(type)).put("gear_type",type).put("work_type","자망·통발 작업");
  for(String key:new String[]{"deployment_date","deployment_date_candidate","haul_quantity","redeploy_quantity"})d.put(key,JSONObject.NULL);
  d.put("date_status","MISSING").put("disposition","UNCONFIRMED").put("recorded_date",recordedDate);
  d.put("warnings",new JSONArray());
  if(source.contains("자망")&&"POT".equals(type)||source.contains("통발")&&"GILLNET".equals(type))d.put("gear_conflict",true);
  extractDates(source,reference,d);
  extractQuantities(source,d);
  if(d.optBoolean("multiple_deployments"))d.put("haul_quantity",JSONObject.NULL);
  boolean reset=Pattern.compile("재\\s*(?:투망|설치)|다시\\s*(?:(?:자망|통발|어구)(?:을|를)?\\s*)?(?:투망|설치|놓|넣)").matcher(source).find();
  boolean removed=Pattern.compile("(?:최종|전부|모두|전량)\\s*(?:어구를\\s*)?(?:철거|회수)|다시\\s*(?:투망|설치)하지\\s*않고\\s*(?:전부\\s*)?철거").matcher(source).find();
  boolean noReset=source.matches("(?s).*(?:재투망|재설치|다시\\s*(?:투망|설치|놓|넣)).{0,12}(?:않|안\\s*했|못|예정|계획|(?:할|을)\\s*(?:겁|거)|하겠).*");
  if(noReset){reset=false;d.put("redeploy_quantity",JSONObject.NULL);}
  if(reset&&removed){d.put("disposition","UNCONFIRMED").put("disposition_conflict",true);}
  else if(removed)d.put("disposition","REMOVED");else if(reset||!d.isNull("redeploy_quantity"))d.put("disposition","REDEPLOYED");
  // A species named with an affirmative catch statement resolves existence, not its missing amount.
  if("UNCONFIRMED".equals(d.optString("outcome"))&&d.getJSONArray("catches").length()>0&&source.matches("(?s).*(?:잡았습니다|잡았어요|잡았고|잡혔습니다|어획했습니다).*")&&!source.matches("(?s).*(?:못\\s*잡|안\\s*잡|잡지\\s*않|없).*"))d.put("outcome","CAUGHT");
  return d;
 }
 private static final String NUM="(?:[0-9]+|[영일이삼사오육칠팔구십백천]+)";
 private static final Pattern DATE=Pattern.compile("(?:("+NUM+")\\s*년\\s*)?(?:("+NUM+")\\s*월\\s*)?("+NUM+")\\s*일(?=$|[\\s,.]|에|날|전)|오늘|어제|그제|그저께|(?:사흘|이틀|나흘|"+NUM+")\\s*(?:일\\s*)?전");
 private static int integer(String s){Double n=KoreanNumber.parse(s);if(n==null||n!=Math.rint(n))throw new IllegalArgumentException("날짜 숫자 확인 필요");return n.intValue();}
 private static void extractDates(String source,LocalDate ref,JSONObject d) throws JSONException {
  Set<String> candidates=new LinkedHashSet<>();JSONArray evidence=new JSONArray();boolean invalid=false;
  Matcher m=DATE.matcher(source);
  while(m.find()){
   String after=source.substring(m.end(),Math.min(source.length(),m.end()+45));
   Matcher verb=Pattern.compile("투망|설치|놓|넣|투하").matcher(after);
   if(!verb.find()||after.substring(0,verb.start()).matches("(?s).*(?:다시|재|[.!?\\n]).*"))continue;
   // '오늘 55개 투망했습니다' describes today's deployment, not the date of gear being hauled.
   // Retrospective '오늘 놓은/설치한 통발' remains a valid previous-deployment candidate.
   if(m.group().equals("오늘")&&after.substring(verb.start()).matches("(?s)^(?:투망|설치|투하)(?:했|하였|합니다).*"))continue;
   String actionTail=after.substring(verb.end());if(actionTail.matches("(?s).{0,12}(?:않|안\\s*했|못|예정|계획).*"))continue;
   String before=source.substring(Math.max(0,m.start()-8),m.start());if(before.matches("(?s).*(?:다시|재투망|재설치).*"))continue;
   evidence.put(m.group()+after.substring(0,Math.min(after.length(),verb.end()+8)));
   try{
    LocalDate found;String raw=m.group().replaceAll("\\s+","");
    if(raw.equals("오늘"))found=ref;else if(raw.equals("어제"))found=ref.minusDays(1);else if(raw.equals("그제")||raw.equals("그저께"))found=ref.minusDays(2);
    else if(raw.endsWith("전")){String n=raw.substring(0,raw.length()-1);int days=n.equals("사흘")?3:n.equals("이틀")?2:n.equals("나흘")?4:integer(n.endsWith("일")?n.substring(0,n.length()-1):n);found=ref.minusDays(days);}
    else {int year=m.group(1)==null?ref.getYear():integer(m.group(1)),month=m.group(2)==null?ref.getMonthValue():integer(m.group(2));found=LocalDate.of(year,month,integer(m.group(3)));}
    if(found.isAfter(ref)||found.getYear()<1900)throw new IllegalArgumentException("투망일 범위 확인");candidates.add(found.toString());
   }catch(Exception e){invalid=true;}
  }
  d.getJSONObject("evidence").put("deployment_date",evidence);
  if(candidates.size()==1&&!invalid){d.put("deployment_date_candidate",candidates.iterator().next()).put("date_status","CANDIDATE");}
  else if(candidates.size()>1||invalid){if(candidates.size()>1)d.put("multiple_deployments",true);d.put("date_status","UNCONFIRMED");d.getJSONArray("warnings").put("투망 날짜가 여러 개이거나 불명확합니다. 나누어 기록하거나 직접 확인해 주세요.");}
 }
 private static final Pattern COUNT=Pattern.compile("(?<![가-힣0-9.])("+KoreanNumber.PATTERN+")\\s*(폭|개)(?=$|[\\s,.]|를|을|는|가|로|만|씩|정도|쯤|걷|다시|입니다|이에요|예요|이요|요)");
 private static void extractQuantities(String source,JSONObject d) throws JSONException {
  ArrayList<int[]> spans=new ArrayList<>();ArrayList<String[]> values=new ArrayList<>();Matcher m=COUNT.matcher(source);
  while(m.find()){spans.add(new int[]{m.start(),m.end()});values.add(new String[]{m.group(1),m.group(2)});}
  Set<Double> haul=new LinkedHashSet<>(),reset=new LinkedHashSet<>();boolean badHaul=false,badReset=false;
  for(int i=0;i<spans.size();i++){
   int start=spans.get(i)[0],end=spans.get(i)[1],limit=i+1<spans.size()?spans.get(i+1)[0]:source.length();
   String tail=source.substring(end,limit).split("[.!?\\n]",2)[0];
   int boundary=Math.max(source.lastIndexOf('.',start-1),Math.max(source.lastIndexOf('\n',start-1),source.lastIndexOf(',',start-1)));
   int previousEnd=i==0?0:spans.get(i-1)[1];String head=source.substring(Math.max(boundary+1,previousEnd),start);
   boolean re=tail.matches("(?s).*?(?:다시|재투망|재설치).*")||head.matches("(?s).*(?:다시|재투망|재설치)\\s*(?:자망|통발|어구)?\\s*$");
   Matcher action=Pattern.compile("양망|회수|걷|거두|거둬|거뒀|건져|건졌|인양|철거|투망|설치|놓|넣").matcher(tail);
   String verb=action.find()?action.group():"";
   boolean isHaul=Arrays.asList("양망","회수","걷","거두","거둬","거뒀","건져","건졌","인양","철거").contains(verb);
   boolean isReset=!isHaul&&re&&Arrays.asList("투망","설치","놓","넣").contains(verb);
   if(verb.isEmpty()&&head.matches("(?s).*(?:양망(?:한)?|회수(?:한)?|걷은|거둔|거둬들인|건진|인양한)(?:\\s*(?:통발|자망|어구))?(?:\\s*수량)?(?:은|는|이|가)?\\s*$"))isHaul=true;
   if(verb.isEmpty()&&head.matches("(?s).*(?:재투망(?:한)?|재설치(?:한)?|다시\\s*(?:놓은|넣은|설치한|투망한))(?:\\s*(?:통발|자망|어구))?(?:\\s*수량)?(?:은|는|이|가)?\\s*$"))isReset=true;
   if("철거".equals(verb)&&!haul.isEmpty()&&head.matches("(?s).*(?:나머지|남은|일부).*"))continue;
   if(!isHaul&&!isReset){d.getJSONArray("warnings").put("어구 수량의 양망·재투망 구분을 확인해 주세요.");continue;}
   boolean fishHead=false;for(String fish:FisheryExtractor.SPECIES)if(head.contains(fish)&&!head.matches("(?s).*(?:통발|자망|어구).*"))fishHead=true;if(fishHead)continue;
   String evidence=head+values.get(i)[0]+values.get(i)[1]+tail;String key=isHaul?"haul_quantity":"redeploy_quantity";
   JSONArray ev=d.getJSONObject("evidence").optJSONArray(key);if(ev==null)ev=new JSONArray();ev.put(evidence);d.getJSONObject("evidence").put(key,ev);
   Double n=KoreanNumber.parse(values.get(i)[0]);
   boolean uncertain=start>0&&(source.charAt(start-1)=='-'||source.charAt(start-1)=='−')||!unit(d.optString("gear_type")).equals(values.get(i)[1])||n==null||n<=0||n!=Math.rint(n);
   uncertain|=evidence.matches("(?s).*(?:~|∼|[0-9]\\s*[-–]\\s*[0-9]|에서|부터|아니|아닙|아닌|정정|인가|인지|모르|정도|쯤|대략|약\\s|않|안\\s*(?:했|걷|거두|거둬|건져|양망|회수)|못|예정|계획|하겠|(?:할|놓을|넣을)\\s*겁).*" );
   if(uncertain){if(isHaul)badHaul=true;else badReset=true;}else if(isHaul)haul.add(n);else reset.add(n);
  }
  if(haul.size()==1&&!badHaul)d.put("haul_quantity",haul.iterator().next().longValue());
  if(reset.size()==1&&!badReset)d.put("redeploy_quantity",reset.iterator().next().longValue());
  if(haul.size()>1||reset.size()>1||badHaul||badReset)d.put("quantity_uncertainty",true);
 }
 static JSONArray issues(JSONObject d) throws JSONException {
  JSONArray out=new JSONArray();
  if(d.optBoolean("recognition_uncertain"))out.put("같은 문장이 과도하게 반복되어 항목에 넣지 않았습니다. 전사·원음을 확인하고 직접 입력해 주세요.");
  if(d.optBoolean("multiple_deployments"))out.put("투망일이 다른 어구는 나누어 기록해 주세요.");
  if(!supported(d.optString("gear_type"))||d.optBoolean("gear_conflict"))out.put("자망·통발 구분 확인 필요");
  if(d.isNull("deployment_date"))out.put(d.isNull("deployment_date_candidate")?"이번에 걷은 어구의 투망일 확인 필요":"투망일 후보 확인 필요 · "+d.getString("deployment_date_candidate"));
  if(d.isNull("haul_quantity"))out.put("양망한 어구 수 확인 필요 · "+unit(d.optString("gear_type")));
  if("UNCONFIRMED".equals(d.optString("disposition"))||d.optBoolean("disposition_conflict"))out.put("재투망 또는 최종 철거 확인 필요");
  if("REDEPLOYED".equals(d.optString("disposition"))&&d.isNull("redeploy_quantity"))out.put("다시 투망한 어구 수 확인 필요 · "+unit(d.optString("gear_type")));
  if(d.optBoolean("quantity_uncertainty"))out.put("어구 수량의 불확실한 표현 확인 필요");
  if(d.optBoolean("additional_uncertainty"))out.put("추가 녹음의 서로 다른 값을 확인해 주세요.");
  JSONObject catchData=new JSONObject(d.toString()).put("phase","END");JSONArray catches=FieldChecks.issues(catchData);for(int i=0;i<catches.length();i++)out.put(catches.get(i));
  return out;
 }
 static void confirmDeploymentDate(JSONObject d,String date) throws JSONException {
  d.put("deployment_date",date).put("date_status","CONFIRMED");
  if(d.optBoolean("multiple_deployments"))d.put("multiple_deployments_acknowledged",true);
  d.remove("multiple_deployments");
 }
 static void validate(JSONObject d) throws JSONException {
  if(!supported(d.optString("gear_type"))||!unit(d.getString("gear_type")).equals(d.getString("gear_unit")))throw new IllegalArgumentException("자망은 폭, 통발은 개로 기록하세요.");
  LocalDate ref=LocalDate.parse(d.getString("recorded_date"));
  for(String key:new String[]{"deployment_date","deployment_date_candidate"})if(!d.isNull(key))try{LocalDate dt=LocalDate.parse(d.getString(key));if(dt.isAfter(ref)||dt.getYear()<1900)throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("투망일은 녹음일 이전 날짜를 YYYY-MM-DD로 입력하세요.");}
  String status=d.getString("date_status");if(!Arrays.asList("MISSING","CANDIDATE","UNCONFIRMED","CONFIRMED").contains(status)||!d.isNull("deployment_date")&&!"CONFIRMED".equals(status)||"CONFIRMED".equals(status)&&d.isNull("deployment_date"))throw new IllegalArgumentException("투망일 확인 상태 오류");
  for(String key:new String[]{"haul_quantity","redeploy_quantity"})if(!d.isNull(key)){double n=d.getDouble(key);if(!Double.isFinite(n)||n<=0||n!=Math.rint(n)||n>1_000_000)throw new IllegalArgumentException("어구 수는 1 이상의 정수로 입력하세요.");}
  if(!Arrays.asList("UNCONFIRMED","REDEPLOYED","REMOVED").contains(d.getString("disposition")))throw new IllegalArgumentException("어구 처리 상태 오류");
  if("REMOVED".equals(d.getString("disposition"))&&!d.isNull("redeploy_quantity"))throw new IllegalArgumentException("최종 철거와 재투망 수량을 함께 확정할 수 없습니다.");
  if(!d.isNull("workers")||!d.isNull("gear_quantity"))throw new IllegalArgumentException("자망·통발 기록은 작업 인원을 사용하지 않고 양망·재투망 수량을 구분합니다.");
 }
 static String summary(JSONObject d) {
  String unit=unit(d.optString("gear_type"));String date=d.isNull("deployment_date")?(d.isNull("deployment_date_candidate")?"미확인":d.optString("deployment_date_candidate")+" · 후보"):d.optString("deployment_date");
  return name(d.optString("gear_type"))+"\n투망일 "+date+"\n양망 "+(d.isNull("haul_quantity")?"미확인":d.optString("haul_quantity")+unit)+"\n"+("REMOVED".equals(d.optString("disposition"))?"최종 철거":"REDEPLOYED".equals(d.optString("disposition"))?"재투망 "+(d.isNull("redeploy_quantity")?"수량 미확인":d.optString("redeploy_quantity")+unit):"재투망·철거 미확인");
 }
 static JSONObject merge(JSONObject base,JSONObject extra) throws JSONException {
  JSONObject d=FieldChecks.merge(new JSONObject(base.toString()).put("phase","END"),new JSONObject(extra.toString()).put("phase","END"));d.put("phase","WORK");
  for(String key:new String[]{"haul_quantity","redeploy_quantity","deployment_date_candidate"}){
   if(extra.isNull(key))continue;
   if(d.isNull(key))d.put(key,extra.get(key));else if(!StructuredIntegrity.equal(d.get(key),extra.get(key))){d.put(key,JSONObject.NULL).put("additional_uncertainty",true);}
  }
  if(!extra.isNull("deployment_date_candidate")){
   if(!d.isNull("deployment_date")&&!extra.get("deployment_date_candidate").equals(d.get("deployment_date")))d.put("deployment_date",JSONObject.NULL).put("additional_uncertainty",true);
   if(d.isNull("deployment_date"))d.put("date_status",d.isNull("deployment_date_candidate")?"UNCONFIRMED":"CANDIDATE");
  }
  String next=extra.optString("disposition"),old=d.optString("disposition");if(!"UNCONFIRMED".equals(next))d.put("disposition","UNCONFIRMED".equals(old)||old.equals(next)?next:"UNCONFIRMED");
  for(String key:new String[]{"quantity_uncertainty","gear_conflict","disposition_conflict","multiple_deployments","recognition_uncertain"})if(extra.optBoolean(key))d.put(key,true);
  return d;
 }
}
