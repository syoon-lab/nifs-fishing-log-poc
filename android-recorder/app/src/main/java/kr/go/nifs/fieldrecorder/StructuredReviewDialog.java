package kr.go.nifs.fieldrecorder;
import android.app.*;
import android.graphics.Color;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Missing values stay blank. Source, automatic drafts and user edits are stored separately. */
final class StructuredReviewDialog {
 private final Activity activity;private final Store store;private final String tid,source;private final JSONObject draft;private final Runnable done;
 private final Map<String,EditText> fields=new LinkedHashMap<>();private final ArrayList<CatchEditor> catches=new ArrayList<>();private LinearLayout content,catchPanel;private Spinner outcome;private AlertDialog dialog;
 private static final String[] OUTCOMES={"UNCONFIRMED","CAUGHT","NO_CATCH","NOT_FISHED"},LABELS={"결과 미확인","어획 있음","전체 무어획","조업 안 함"};
 private class CatchEditor {JSONObject original;LinearLayout view;EditText species,quantity,unit,weight;CheckBox approx;}
 static void show(Activity a,Store s,String tid,String source,Runnable done){try{
  JSONObject recording=s.row("recordings",s.row("transcriptions",tid).getString("recording_id"));
  if("WORK".equals(recording.optString("phase"))){JSONObject data=s.extractRecording(recording,source),latest=s.latest("structured_logs","transcription_id",tid);
   if(latest!=null&&"CONFIRMED".equals(latest.optString("status"))&&source.equals(latest.optString("source_text")))data=new JSONObject(latest.getString("data_json"));
   GearReviewDialog.show(a,data,d->s.reviewFields(tid,source,d),done);return;
  }
  new StructuredReviewDialog(a,s,tid,source,done).show();}catch(Exception e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}}
 private StructuredReviewDialog(Activity a,Store s,String t,String text,Runnable callback) throws Exception {
  activity=a;store=s;tid=t;source=text;done=callback;JSONObject recording=s.row("recordings",s.row("transcriptions",t).getString("recording_id"));draft=StructuredLog.extract(text,recording.getString("phase"));
  JSONObject latest=s.latest("structured_logs","transcription_id",t);
  if(latest!=null&&"CONFIRMED".equals(latest.optString("status"))&&text.equals(latest.optString("source_text"))){JSONObject previous=new JSONObject(latest.getString("data_json"));for(String key:new String[]{"work_type","target_species","gear_quantity","gear_unit","workers","outcome","catches","notes_state","notes_text"})draft.put(key,previous.get(key));}
 }
 private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
 private void label(LinearLayout parent,String s){TextView t=new TextView(activity);t.setText(s);t.setTextSize(22);t.setTextColor(Color.rgb(23,107,96));parent.addView(t);}
 private EditText input(LinearLayout parent,String title,Object value,boolean number){label(parent,title);EditText e=new EditText(activity);e.setText(value==null||value==JSONObject.NULL?"":value instanceof Number?StructuredLog.format(((Number)value).doubleValue()):value.toString());e.setSingleLine(number);if(!number)e.setMaxLines(3);e.setTextSize(26);e.setMinHeight(dp(64));if(number)e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setHint("미확인");parent.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
 private void show() throws Exception {
  content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));String phase=draft.getString("phase");
  if(!phase.equals("NOTE")){
   if(!phase.equals("END")){
    String[][] keys={{"work_type","작업"},{"target_species","목표 어종"},{"gear_quantity","어구 수량"},{"gear_unit","어구 단위"},{"workers","작업 인원"}};
    for(String[] key:keys){EditText field=input(content,key[1],draft.get(key[0]),key[0].equals("gear_quantity")||key[0].equals("workers"));fields.put(key[0],field);candidate(content,field,draft,key[0]);}
   }
   if(!phase.equals("START")){
    label(content,"전체 어획 결과");outcome=new Spinner(activity);outcome.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,LABELS));outcome.setSelection(Arrays.asList(OUTCOMES).indexOf(draft.getString("outcome")));content.addView(outcome);
    catchPanel=new LinearLayout(activity);catchPanel.setOrientation(LinearLayout.VERTICAL);content.addView(catchPanel);JSONArray rows=draft.getJSONArray("catches");for(int i=0;i<rows.length();i++)addCatch(rows.getJSONObject(i));
    Button add=new Button(activity);add.setText("어종 추가");add.setOnClickListener(v->{try{addCatch(new JSONObject().put("species",JSONObject.NULL).put("quantity",JSONObject.NULL).put("unit",JSONObject.NULL).put("weight_kg",JSONObject.NULL).put("outcome","UNCONFIRMED").put("approximate",false).put("evidence",new JSONArray()));}catch(Exception e){error(e);}});content.addView(add);
   }
  }
  Button evidence=new Button(activity);evidence.setText("원문 · 확인할 항목");evidence.setOnClickListener(v->{StringBuilder s=new StringBuilder(source);JSONArray a=draft.optJSONArray("warnings");for(int i=0;i<a.length();i++)s.append("\n• ").append(a.optString(i));new ReadableDialog.Builder(activity).setTitle("추출 근거").setMessage(s).setPositiveButton("닫기",null).show();});content.addView(evidence);
  if(draft.getJSONArray("warnings").length()>0)label(content,"미확인 항목이 있습니다. 빈칸으로 보관할 수 있습니다.");
  ScrollView scroll=new ScrollView(activity);scroll.addView(content);dialog=new ReadableDialog.Builder(activity).setTitle("일지 항목 확인").setView(scroll).setPositiveButton("일지에 저장",null).setNegativeButton("취소",null).create();dialog.setOnShowListener(v->{ReadableDialog.style(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->save());});dialog.show();
 }
 private void addCatch(JSONObject original) throws Exception {
  CatchEditor e=new CatchEditor();e.original=new JSONObject(original.toString());e.view=new LinearLayout(activity);e.view.setOrientation(LinearLayout.VERTICAL);e.view.setPadding(0,dp(12),0,dp(12));catchPanel.addView(e.view);e.species=input(e.view,"어종",original.get("species"),false);
  e.quantity=input(e.view,"수량",original.get("quantity"),true);e.unit=input(e.view,"단위 (마리·상자 등)",original.get("unit"),false);e.weight=input(e.view,"무게 (kg)",original.get("weight_kg"),true);
  candidate(e.view,e.species,original,"species");candidate(e.view,e.quantity,original,"quantity");
  e.approx=new CheckBox(activity);e.approx.setText("대략적인 수량·무게");e.approx.setChecked(original.getBoolean("approximate"));e.view.addView(e.approx);Button remove=new Button(activity);remove.setText("이 항목 빼기");remove.setOnClickListener(v->{catchPanel.removeView(e.view);catches.remove(e);});e.view.addView(remove);catches.add(e);
 }
 private void candidate(LinearLayout parent,EditText field,JSONObject data,String key){
  if(!data.has(key+"_candidate")||!data.isNull(key))return;
  if(key.equals("gear_unit")&&data.has("gear_quantity_candidate"))return;
  String value=data.opt(key+"_candidate").toString();Button b=new Button(activity);
  if(key.equals("gear_quantity")&&data.has("gear_unit_candidate")){
   String u=data.optString("gear_unit_candidate");b.setText(value+u+" 후보 적용");b.setOnClickListener(v->{field.setText(value);fields.get("gear_unit").setText(u);});
  }else{b.setText((data.has("candidate_text")?"‘"+data.optString("candidate_text")+"’ → ":"")+value+" 후보 적용");b.setOnClickListener(v->field.setText(value));}
  parent.addView(b);
 }
 private Object value(EditText e,boolean numeric){String s=e.getText().toString().trim();if(s.isEmpty())return JSONObject.NULL;if(!numeric)return s;Double n=KoreanNumber.parse(s);if(n==null)throw new IllegalArgumentException("숫자를 확인하세요: "+s);return n;}
 private void edit(JSONObject obj,String key,Object value,String path,JSONArray edits) throws Exception {Object previous=obj.opt(key);if(!StructuredIntegrity.equal(previous,value))edits.put(new JSONObject().put("field",path).put("before",previous==null?JSONObject.NULL:previous).put("after",value));obj.put(key,value);}
 private void save(){
  if(RecorderApp.exporting||TranscriptionService.running){error(new Exception("진행 중인 작업을 마친 뒤 저장하세요."));return;}
  try{
   JSONObject result=new JSONObject(draft.toString());JSONArray edits=new JSONArray();for(Map.Entry<String,EditText> f:fields.entrySet())edit(result,f.getKey(),value(f.getValue(),f.getKey().equals("gear_quantity")||f.getKey().equals("workers")),f.getKey(),edits);
   if(outcome!=null){edit(result,"outcome",OUTCOMES[outcome.getSelectedItemPosition()],"outcome",edits);JSONArray rows=new JSONArray();
    for(int i=0;i<catches.size();i++){
     CatchEditor e=catches.get(i);JSONObject row=new JSONObject(e.original.toString());String prefix="catches["+i+"].";edit(row,"species",value(e.species,false),prefix+"species",edits);edit(row,"quantity",value(e.quantity,true),prefix+"quantity",edits);edit(row,"unit",value(e.unit,false),prefix+"unit",edits);edit(row,"weight_kg",value(e.weight,true),prefix+"weight_kg",edits);edit(row,"approximate",e.approx.isChecked(),prefix+"approximate",edits);
     row.put("outcome",row.optDouble("quantity",0)>0||row.optDouble("weight_kg",0)>0?row.isNull("species")?"UNCONFIRMED":"CAUGHT":!row.isNull("quantity")&&row.getDouble("quantity")==0?"NO_CATCH":"UNCONFIRMED");rows.put(row);
    }
    if(!rows.toString().equals(draft.getJSONArray("catches").toString()))edits.put(new JSONObject().put("field","catch_rows").put("before",draft.getJSONArray("catches")).put("after",rows));result.put("catches",rows);
   }
   result.put("user_edits",edits);store.reviewFields(tid,source,result);dialog.dismiss();done.run();Toast.makeText(activity,"어종·수량과 함께 일지에 저장했습니다.",Toast.LENGTH_LONG).show();
  }catch(Exception e){error(e);}
 }
 private void error(Exception e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();}
}
