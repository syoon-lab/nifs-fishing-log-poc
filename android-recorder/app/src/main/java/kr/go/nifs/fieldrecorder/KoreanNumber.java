package kr.go.nifs.fieldrecorder;
import java.util.*;
/** Exact written numbers only. Never guesses a number from a phonetic ASR error. */
final class KoreanNumber {
 static final String WORDS="영공일이삼사오육칠팔구십백천만한하나두둘세셋네넷다섯여섯일곱여덟아홉열스무스물서른마흔쉰예순일흔여든아흔점";
 private static final String ONES="(?:하나|한|둘|두|셋|세|넷|네|다섯|여섯|일곱|여덟|아홉)";
 private static final String INTEGER="(?:(?:아흔|여든|일흔|예순|쉰|마흔|서른|스물|스무|열)(?:\\s*"+ONES+")?|"+ONES+"|[영공일이삼사오육칠팔구십백천만]+(?:\\s+[영공일이삼사오육칠팔구십백천만]+)*)";
 static final String PATTERN="(?:[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?|"+INTEGER+"(?:\\s*점\\s*[영공일이삼사오육칠팔구]+)?)";
 private static final Map<String,Integer> nativeNumbers=new LinkedHashMap<>();
 static {
  String[] units={"","한","두","세","네","다섯","여섯","일곱","여덟","아홉"};
  String[] tens={"","열","스물","서른","마흔","쉰","예순","일흔","여든","아흔"};
  for(int n=1;n<100;n++)nativeNumbers.put(tens[n/10]+units[n%10],n);
  nativeNumbers.put("하나",1);nativeNumbers.put("둘",2);nativeNumbers.put("셋",3);nativeNumbers.put("넷",4);nativeNumbers.put("스무",20);
 }
 static Double parse(String raw){
  if(raw==null)return null;String s=raw.replaceAll("\\s+","").replace(",","");
  try{if(s.matches("[0-9]+(?:\\.[0-9]+)?")){double n=Double.parseDouble(s);return Double.isFinite(n)&&n<=1_000_000_000?n:null;}}catch(NumberFormatException ignored){return null;}
  if(s.startsWith("스무")&&s.length()>2)s="스물"+s.substring(2);
  if(s.length()>2){for(String[] alias:new String[][]{{"하나","한"},{"둘","두"},{"셋","세"},{"넷","네"}})if(s.endsWith(alias[0]))s=s.substring(0,s.length()-alias[0].length())+alias[1];}
  if(nativeNumbers.containsKey(s))return nativeNumbers.get(s).doubleValue();
  if(s.contains("점")){String[] p=s.split("점",-1);if(p.length!=2||p[1].isEmpty())return null;Double whole=parse(p[0]);if(whole==null)return null;double frac=0,base=10;for(char c:p[1].toCharArray()){int d="영일이삼사오육칠팔구".indexOf(c);if(d<0)return null;frac+=d/base;base*=10;}return whole+frac;}
  if(s.equals("영")||s.equals("공"))return 0d;
  if(!s.matches("[일이삼사오육칠팔구십백천만]+"))return null;
  int total=0,section=0,digit=0,last=10000;boolean previousDigit=false;
  for(char c:s.toCharArray()){
   int d="영일이삼사오육칠팔구".indexOf(c);if(d>0){if(previousDigit)return null;digit=d;previousDigit=true;continue;}
   int unit=c=='십'?10:c=='백'?100:c=='천'?1000:10000;
   if(unit==10000){if(total!=0)return null;total=(section+digit==0?1:section+digit)*10000;section=0;digit=0;last=10000;}
   else{if(unit>=last)return null;section+=(digit==0?1:digit)*unit;digit=0;last=unit;}
   previousDigit=false;
  }
  return (double)(total+section+digit);
 }
 /** A review suggestion only: casual ㅟ→ㅣ vowel merging, never an exact parse. */
 static Integer vowelCandidate(String raw){
  if(raw==null||parse(raw)!=null)return null;
  Integer result=null;
  for(Map.Entry<String,Integer> entry:nativeNumbers.entrySet()){
   String written=entry.getKey();if(written.length()!=raw.length())continue;int changes=0;boolean matches=true;
   for(int i=0;i<raw.length();i++){
    char a=raw.charAt(i),b=written.charAt(i);if(a==b)continue;
    if(a<'가'||a>'힣'||b<'가'||b>'힣'){matches=false;break;}
    int x=a-'가',y=b-'가';
    if(x/588!=y/588||x%28!=y%28||(x/28)%21!=20||(y/28)%21!=16){matches=false;break;}
    changes++;
   }
   if(matches&&changes==1){if(result!=null&&!result.equals(entry.getValue()))return null;result=entry.getValue();}
  }
  return result;
 }
}
