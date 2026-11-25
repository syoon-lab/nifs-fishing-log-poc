package kr.go.nifs.fieldrecorder;

import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Offline, conservative common-field parser. Full source and per-field evidence survive every normalization. */
public final class FisheryExtractor {
 public static final String VERSION="common-fields-ko/1.2";
 static final String[] SPECIES={"붉은대게","갑오징어","살오징어","참조기","말쥐치","참가자미","붕장어","먹장어","민꽃게","참돔","감성돔","돌돔","벵에돔","대구","명태","갈치","고등어","삼치","전갱이","멸치","꽁치","청어","정어리","전어","넙치","가자미","농어","숭어","조피볼락","볼락","우럭","꽃게","대게","홍게","오징어","낙지","문어","주꾸미","쭈꾸미","새우","대하","꼬막","바지락","전복","해삼","성게","소라","가리비","방어","부시리","가다랑어","참다랑어","도루묵","임연수어","복어","장어","조기"};
 private static final String SPEC="(?:"+String.join("|",SPECIES)+")";
 private static final String UNIT="(?:킬로그램|키로그램|킬로|키로|kg|그램|그람|g|마리|상자|박스|개|폭|틀|통|명|줄|고리)";
 private static final Pattern QUANTITY=Pattern.compile("(?<![0-9.,])("+KoreanNumber.PATTERN+")\\s*("+UNIT+")",Pattern.CASE_INSENSITIVE);
 private static final Pattern FISH=Pattern.compile("(?<![가-힣])"+SPEC+"(?=$|[\\s0-9,.]|은|는|을|를|이|가|와|하고|["+KoreanNumber.WORDS+"])");
 private static final Pattern SPLIT_GEAR=Pattern.compile("(?:통발|그물|어구|장비|자망|연승)(?:은|는|을|를|이|가)?\\s*,?\\s*(아흔|여든|일흔|예순|쉰|마흔|서른|스물|스무|열)\\s*,\\s*(하나|한|둘|두|셋|세|넷|네|다섯|여섯|일곱|여덟|아홉)\\s*,?\\s*(개|폭|줄|통)(?=$|\\s|[,.]|입니다|이|를|을|만|쯤|정도)");
 private static final Pattern NONE=Pattern.compile("(?:잡(?:지\\s*못|히지\\s*않|지\\s*않)|못\\s*잡|안\\s*잡|없(?:습니다|어요|었|음|다)|없고|없었)");
 private static String unit(String s){return switch(s.toLowerCase(Locale.ROOT)){case "킬로그램","키로그램","킬로","키로","kg"->"kg";case "그램","그람","g"->"g";case "박스"->"상자";default->s;};}
 private static JSONObject emptyRow(String species) throws JSONException {return new JSONObject().put("species",species==null?JSONObject.NULL:species).put("quantity",JSONObject.NULL).put("unit",JSONObject.NULL).put("weight_kg",JSONObject.NULL).put("outcome","UNCONFIRMED").put("approximate",false).put("evidence",new JSONArray());}
 private static void evidence(JSONObject row,String source) throws JSONException {JSONArray a=row.getJSONArray("evidence");for(int i=0;i<a.length();i++)if(source.equals(a.getString(i)))return;a.put(source);}
 private static void set(JSONObject row,String key,Object value,JSONArray warnings) throws JSONException {
  if(row.optBoolean("conflict_"+key))return;
  if(row.isNull(key))row.put(key,value);else if(!row.get(key).equals(value)){row.put(key,JSONObject.NULL);warnings.put("서로 다른 "+label(key)+" 값 · 원음 확인");row.put("conflict_"+key,true);}
 }
 private static void scalar(JSONObject result,String key,Object value,String evidence,JSONArray warnings) throws JSONException {
  JSONObject ev=result.getJSONObject("evidence");if(!result.isNull(key)&&!result.get(key).equals(value)){result.put(key,JSONObject.NULL);warnings.put("서로 다른 "+label(key)+" 값 · 확인 필요");result.put("conflict_"+key,true);}
  else if(!result.optBoolean("conflict_"+key))result.put(key,value);
  JSONArray a=ev.optJSONArray(key);if(a==null){a=new JSONArray();ev.put(key,a);}a.put(evidence);
 }
 static String label(String key){return switch(key){case "work_type"->"작업 종류";case "target_species"->"목표 어종";case "gear_quantity"->"어구 수량";case "gear_unit"->"어구 단위";case "workers"->"작업 인원";case "quantity"->"수량";case "weight_kg"->"중량";default->key;};}
 public static JSONObject extract(String text,String phase) throws JSONException {
  JSONObject result=new JSONObject().put("version",VERSION).put("phase",phase).put("work_type",JSONObject.NULL).put("target_species",JSONObject.NULL).put("gear_quantity",JSONObject.NULL).put("gear_unit",JSONObject.NULL).put("gear_approximate",false).put("workers",JSONObject.NULL).put("outcome","UNCONFIRMED").put("notes_state","NOT_MENTIONED").put("notes_text",JSONObject.NULL).put("evidence",new JSONObject());
  JSONArray warnings=new JSONArray(),catches=new JSONArray();result.put("warnings",warnings).put("catches",catches);
  boolean start="START".equals(phase),note="NOTE".equals(phase),legacy="LEGACY".equals(phase),allNone=false,notFished=false;
  if(note){result.put("notes_state",text.matches(".*특이\\s*사항.*없.*")?"EXPLICIT_NONE":"REPORTED").put("notes_text",text);result.getJSONObject("evidence").put("notes_text",new JSONArray().put(text));return result;}
  if(legacy)warnings.put("이전 녹음 · 시작/마무리 단계 미분류");
  String previousSpecies=null;
  for(String clause:text.split("[。!?\\n]|(?<![0-9])\\.(?![0-9])")){
   String source=clause.trim();if(source.isEmpty())continue;String s=source.replaceAll("\\s+"," ");
   if(s.matches(".*(?:조업|작업)(?:을|은)?\\s*(?:하지\\s*않|안\\s*했|못\\s*했).*")){notFished=true;result.getJSONObject("evidence").put("not_fished",new JSONArray().put(source));}
   boolean no=NONE.matcher(s).find();
   boolean all=(no&&s.matches(".*(?:어떤\\s*어종도|모든\\s*어종|아무것도).*"))||s.matches("^(?:오늘(?:은)?\\s*)?(?:(?:전체|총)\\s*)?(?:어획(?:량|물)?(?:은|이|가)?\\s*없.*|무어획.*)");
   if(all){allNone=true;result.getJSONObject("evidence").put("overall_no_catch",new JSONArray().put(source));}
   if(s.matches(".*특이\\s*사항.*(?:없|않).*")){result.put("notes_state","EXPLICIT_NONE");result.getJSONObject("evidence").put("notes_state",new JSONArray().put(source));}
   Matcher target=Pattern.compile("(?:목표(?:로\\s*한)?(?:\\s*어종)?|대상\\s*어종)(?:은|는|이|가)?\\s*("+SPEC+")").matcher(s);
   if(target.find())scalar(result,"target_species",target.group(1),source,warnings);
   else if(start&&s.matches(".*(?:목표|대상)\\s*어종.*"))warnings.put("목표 어종 이름 인식 확인: "+source);
   if(s.matches(".*(?:통발|그물|자망|연승|주낙|낚시|채낚기|저인망|어구).*(?:회수|걷|양망).*"))scalar(result,"work_type","회수",source,warnings);
   else if(s.matches(".*(?:투망|투승|투하).*"))scalar(result,"work_type","투입",source,warnings);
   else if(s.matches(".*채낚기.*"))scalar(result,"work_type","채낚기",source,warnings);
   boolean approximate=s.matches(".*(?:약|대략|쯤|정도|가량).*"),ambiguous=s.matches(".*(?:~|∼|[0-9]\\s*[-–—]\\s*[0-9]|아니|정정|인가|인지|모르|확실하지|것 같).*"),uncertain=ambiguous||s.matches(".*"+KoreanNumber.PATTERN+"\\s*(?:에서|부터)\\s*"+KoreanNumber.PATTERN+".*");
   // Corrections/ranges are not silently reduced to the last number.
   if(uncertain&&QUANTITY.matcher(s).find())warnings.put("범위·정정·불확실한 수량: "+source);
   ArrayList<String> species=new ArrayList<>();Matcher fm=FISH.matcher(s);while(fm.find())if(!species.contains(fm.group()))species.add(fm.group());
   ArrayList<JSONObject> quantities=new ArrayList<>();Matcher qm=QUANTITY.matcher(s);
   while(qm.find()){
    if(qm.start()>0){char prior=s.charAt(qm.start()-1);String prefix=s.substring(0,qm.start());
     if((prior=='-'||prior=='−')&&!prefix.matches(".*[0-9]\\s*[-−]$")){warnings.put("음수 수량 확인: "+source);continue;}
     if(qm.group(1).charAt(0)>='가'&&prior>='가'&&prior<='힣'&&"은는이가약".indexOf(prior)<0){boolean fishPrefix=false;for(String fish:SPECIES)if(prefix.endsWith(fish))fishPrefix=true;if(!fishPrefix)continue;}
    }
    Double number=KoreanNumber.parse(qm.group(1));if(number==null)continue;
    quantities.add(new JSONObject().put("value",number).put("unit",unit(qm.group(2))).put("start",qm.start()).put("end",qm.end()));
   }
   ArrayList<int[]> splitSpans=new ArrayList<>();if(start){Matcher split=SPLIT_GEAR.matcher(s);while(split.find())splitSpans.add(new int[]{split.start(),split.end()});}
   boolean gearSentence=start||s.matches(".*(?:통발|그물|어구|장비|자망).*")||species.isEmpty()&&s.matches(".*(?:투망|투승|양망|재설치|철거).*");
   for(JSONObject q:quantities){String u=q.getString("unit");if(u.equals("명")){if(!uncertain&&q.getDouble("value")==Math.rint(q.getDouble("value")))scalar(result,"workers",q.getDouble("value"),source,warnings);}
    else if(gearSentence&&Arrays.asList("개","폭","틀","통","줄","고리").contains(u)){
     boolean splitPart=false;for(int[] span:splitSpans)if(q.getInt("start")>=span[0]&&q.getInt("start")<span[1])splitPart=true;if(splitPart)continue;
     if(!uncertain){scalar(result,"gear_quantity",q.getDouble("value"),source,warnings);scalar(result,"gear_unit",u,source,warnings);result.put("gear_approximate",approximate);}
    }
   }
   if(s.matches(".*(?:둘이|셋이|넷이|혼자).*작업.*")){Double workers=s.contains("둘이")?2d:s.contains("셋이")?3d:s.contains("넷이")?4d:1d;scalar(result,"workers",workers,source,warnings);}
   Matcher homonym=Pattern.compile("(?<![가-힣])([새내])\\s*(마리|개|상자|박스|통|명)").matcher(s);ArrayList<JSONObject> candidates=new ArrayList<>();
   while(homonym.find()){
    String u=unit(homonym.group(2));int candidate=homonym.group(1).equals("새")?3:4;
    candidates.add(new JSONObject().put("start",homonym.start()).put("value",candidate).put("unit",u).put("text",homonym.group()));
    warnings.put("‘"+homonym.group()+"’ → "+candidate+u+" 후보 · 원음 확인");
    if(start){String key=u.equals("명")?"workers":Arrays.asList("개","통").contains(u)?"gear_quantity":null;if(key!=null&&result.isNull(key)){result.put(key+"_candidate",candidate);result.getJSONObject("evidence").put(key,new JSONArray().put(source));if(key.equals("gear_quantity"))result.put("gear_unit",u);}}
   }
   if(start){
    if(!uncertain){gearVowelCandidate(result,s,source,warnings);Matcher split=SPLIT_GEAR.matcher(s);while(split.find()){Double n=KoreanNumber.parse(split.group(1)+split.group(2));if(n!=null)gearCandidate(result,n.intValue(),split.group(3),split.group(),source,warnings);}}
    if(result.isNull("target_species")&&s.matches(".*(?:목표|대상).*꽃[개게].*")){result.put("target_species_candidate","꽃게");warnings.put("목표 어종 ‘꽃게’ 후보 · 원음 확인");}continue;
   }
   // Separate clauses per fish name so a later species' quantity cannot attach to an earlier one.
   Matcher sm=FISH.matcher(s);ArrayList<Integer> positions=new ArrayList<>();ArrayList<String> names=new ArrayList<>();while(sm.find()){positions.add(sm.start());names.add(sm.group());}
   if(names.isEmpty()&&(!quantities.isEmpty()||!candidates.isEmpty())&&!gearSentence){names.add(s.matches(".*(?:무게|중량).* ".trim())?previousSpecies:null);positions.add(0);}
   for(int i=0;i<names.size();i++){
    String fish=names.get(i);int begin=positions.get(i),end=i+1<positions.size()?positions.get(i+1):s.length();String part=s.substring(begin,end);
    if(part.matches(".*(?:목표|어종은).*"))continue;
    boolean negative=part.matches(".*(?:잡지\\s*못|잡히지\\s*않|잡지\\s*않|못\\s*잡|안\\s*잡|한\\s*마리도\\s*없).* ".trim());negative|=part.matches("^"+SPEC+"(?:은|는|이|가)?\\s*(?:어획(?:량|물)?(?:은|이|가)?\\s*)?없.*");boolean noWeight=part.matches(".*(?:무게|중량).*(?:재지|모르|미측정|안\\s*잰).*"),saw=false;
    JSONObject row=null;for(int j=0;j<catches.length();j++){JSONObject c=catches.getJSONObject(j);if(Objects.equals(c.opt("species"),fish==null?JSONObject.NULL:fish)){row=c;break;}}
    for(JSONObject q:quantities){int pos=q.getInt("start");String u=q.getString("unit");if(!Arrays.asList("kg","g","마리","상자","개","통").contains(u)||pos<begin||pos>=end||u.equals("명")||gearSentence&&Arrays.asList("개","폭","틀","통","줄","고리").contains(u))continue;
     if(row==null){row=emptyRow(fish);catches.put(row);}saw=true;evidence(row,source);
     if(uncertain)continue;
     if(u.equals("kg")||u.equals("g")){if(!noWeight){set(row,"weight_kg",q.getDouble("value")/(u.equals("g")?1000:1),warnings);}}
     else {set(row,"quantity",negative?0d:q.getDouble("value"),warnings);set(row,"unit",u,warnings);}
     row.put("approximate",approximate||row.getBoolean("approximate"));
    }
    for(JSONObject candidate:candidates){if(candidate.getInt("start")<begin||candidate.getInt("start")>=end||candidate.getString("unit").equals("명"))continue;
     if(row==null){row=emptyRow(fish);catches.put(row);}row.put("quantity_candidate",candidate.getInt("value"));row.put("candidate_text",candidate.getString("text"));if(row.isNull("unit"))row.put("unit",candidate.getString("unit"));evidence(row,source);saw=true;
    }
    if(row!=null&&row.isNull("species")&&s.matches(".*(?:꽃개|꿋게).*")){row.put("species_candidate","꽃게");warnings.put("‘꽃게’ 후보 · 원음 확인");}
    if(negative&&!noWeight&&!all&&!part.matches(".*(?:특이\\s*사항|다른\\s*어종).*")){
     if(row==null){row=emptyRow(fish);catches.put(row);}if(!uncertain){set(row,"quantity",0d,warnings);set(row,"unit","마리",warnings);}evidence(row,source);saw=true;
    }
    if(noWeight&&row!=null){row.put("weight_kg",JSONObject.NULL);row.put("weight_unmeasured",true);evidence(row,source);}
    if(row!=null&&saw&&fish==null)warnings.put("어종이 없는 수량 · 어종 확인 필요");
    if(fish!=null&&!all)previousSpecies=fish;
   }
  }
  boolean caught=false;
  for(int i=0;i<catches.length();i++){
   JSONObject c=catches.getJSONObject(i);if(c.optBoolean("conflict_quantity")||c.optBoolean("conflict_weight_kg")){c.put("outcome","UNCONFIRMED");warnings.put("어획 수량 충돌 · 원음 확인");continue;}
   boolean positive=(!c.isNull("quantity")&&c.getDouble("quantity")>0)||(!c.isNull("weight_kg")&&c.getDouble("weight_kg")>0);
   if(positive&&!c.isNull("species")){c.put("outcome","CAUGHT");caught=true;}
   else if(!c.isNull("quantity")&&c.getDouble("quantity")==0)c.put("outcome","NO_CATCH");
   if(c.optBoolean("conflict_quantity")||c.optBoolean("conflict_weight_kg")){c.put("outcome","UNCONFIRMED");warnings.put("어획 수량 충돌 · 원음 확인");}
  }
  if((allNone||notFished)&&caught){warnings.put("무어획/미조업 진술과 어획량 충돌");result.put("outcome","UNCONFIRMED");}
  else result.put("outcome",notFished?"NOT_FISHED":allNone?"NO_CATCH":caught?"CAUGHT":"UNCONFIRMED");
  if(start){for(String key:new String[]{"work_type","target_species","gear_quantity","gear_unit","workers"})if(result.isNull(key))warnings.put(label(key)+" 미확인");}
  else if(!note&&"UNCONFIRMED".equals(result.getString("outcome")))warnings.put("어획 결과 확인 필요");
  return result;
 }
 private static void gearVowelCandidate(JSONObject result,String s,String source,JSONArray warnings) throws JSONException {
  if(!result.isNull("gear_quantity")||result.optBoolean("conflict_gear_quantity")||result.optBoolean("conflict_gear_candidate"))return;
  Matcher m=Pattern.compile("(?:통발|그물|어구|장비|자망|연승)(?:은|는|을|를|이|가)?\\s*(?:수량(?:은|이)?\\s*)?([가-힣]{1,6})\\s*(개|계|게|폭|줄|통)(?=$|\\s|[,.]|입니다|이|를|을|만|쯤|정도)").matcher(s);
  while(m.find()){
   String heard=m.group(1),u=m.group(2);Integer candidate=KoreanNumber.vowelCandidate(heard);
   if(candidate==null&&(u.equals("계")||u.equals("게"))){Double exact=KoreanNumber.parse(heard);if(exact!=null&&exact==Math.rint(exact)&&exact>0&&exact<100)candidate=exact.intValue();}
   if(candidate==null)continue;
   String correctedUnit=u.equals("계")||u.equals("게")?"개":u;
   gearCandidate(result,candidate,correctedUnit,heard+u,source,warnings);
  }
 }
 private static void gearCandidate(JSONObject result,int candidate,String correctedUnit,String heard,String source,JSONArray warnings) throws JSONException {
  if(!result.isNull("gear_quantity")||result.optBoolean("conflict_gear_quantity")||result.optBoolean("conflict_gear_candidate"))return;
  if(result.has("gear_quantity_candidate")&&(result.optInt("gear_quantity_candidate")!=candidate||!correctedUnit.equals(result.optString("gear_unit_candidate",result.optString("gear_unit"))))){
   result.remove("gear_quantity_candidate");result.remove("gear_unit_candidate");result.remove("gear_candidate_text");result.put("conflict_gear_candidate",true);warnings.put("어구 수량 후보가 서로 다릅니다 · 원음 확인");return;
  }
  result.put("gear_quantity_candidate",candidate).put("gear_unit_candidate",correctedUnit).put("gear_candidate_text",heard);
  result.getJSONObject("evidence").put("gear_quantity_candidate",new JSONArray().put(source));
  warnings.put("‘"+heard+"’ → "+candidate+correctedUnit+" 후보 · 직접 확인 후 적용");
 }
}
