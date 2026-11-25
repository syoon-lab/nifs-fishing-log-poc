package kr.go.nifs.fieldrecorder;

import android.app.*;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.util.*;

/** One editor shared by field confirmation and append-only diary confirmation. */
final class GearReviewDialog {
 interface Save {void apply(JSONObject data) throws Exception;}
 static void show(Activity a,JSONObject data,Save save,Runnable done){try{new GearReviewDialog(a,data,save,done).open();}catch(Exception e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();done.run();}}
 private final Activity a;private final JSONObject original;private final Save save;private final Runnable done;
 private LinearLayout panel,catchPanel;private AlertDialog dialog;private EditText date,haul,reset;private Spinner type,disposition,outcome;
 private final ArrayList<EditText[]> rows=new ArrayList<>();private final Map<EditText[],JSONObject> originals=new IdentityHashMap<>();
 private static final String[] DISPOSITIONS={"UNCONFIRMED","REDEPLOYED","REMOVED"},OUTCOMES={"UNCONFIRMED","CAUGHT","NO_CATCH","NOT_FISHED"};
 GearReviewDialog(Activity a,JSONObject d,Save s,Runnable done) throws JSONException {this.a=a;original=new JSONObject(d.toString());save=s;this.done=done;}
 private void label(LinearLayout parent,String text){TextView t=new TextView(a);t.setText(text);t.setTextSize(22);parent.addView(t);}
 private EditText input(LinearLayout parent,String title,Object value,boolean number){label(parent,title);EditText e=new EditText(a);e.setContentDescription(title);e.setTextSize(26);e.setMinHeight(Math.round(64*a.getResources().getDisplayMetrics().density));e.setHint("미확인");e.setSingleLine();e.setInputType(number?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT);if(value!=null&&value!=JSONObject.NULL)e.setText(value instanceof Number?StructuredLog.format(((Number)value).doubleValue()):value.toString());parent.addView(e);return e;}
 private void button(LinearLayout parent,String title,Runnable click){Button b=new Button(a);b.setText(title);b.setTextSize(24);b.setAllCaps(false);parent.addView(b);b.setOnClickListener(v->click.run());}
 private Spinner choose(String label,String[] labels,int selected){label(panel,label);Spinner s=new Spinner(a);s.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,labels));s.setSelection(Math.max(0,selected));panel.addView(s);return s;}
 private void open() throws Exception {
  panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);int pad=Math.round(20*a.getResources().getDisplayMetrics().density);panel.setPadding(pad,0,pad,0);
  type=choose("어구 종류",new String[]{"자망 · 폭","통발 · 개"},"POT".equals(original.optString("gear_type"))?1:0);
  date=input(panel,"이번에 걷은 어구의 투망일",original.opt("deployment_date"),false);date.setHint("예: 2026-11-08");
  if(!original.isNull("deployment_date_candidate")){String candidate=original.getString("deployment_date_candidate");button(panel,candidate+" 투망일 적용",()->date.setText(candidate));}
  label(panel,"녹음일 "+original.getString("recorded_date")+" · 날짜 후보는 확인 후 적용하세요");
  haul=input(panel,"양망한 어구 수",original.opt("haul_quantity"),true);
  disposition=choose("작업 후 어구",new String[]{"재투망·철거 미확인","다시 투망함","전부 최종 철거함"},Arrays.asList(DISPOSITIONS).indexOf(original.optString("disposition")));
  reset=input(panel,"다시 투망한 어구 수",original.opt("redeploy_quantity"),true);
  outcome=choose("어획 결과",new String[]{"어획 여부 미확인","어획 있음","어획 없음","조업 안 함"},Arrays.asList(OUTCOMES).indexOf(original.optString("outcome")));
  catchPanel=new LinearLayout(a);catchPanel.setOrientation(LinearLayout.VERTICAL);panel.addView(catchPanel);
  JSONArray catches=original.getJSONArray("catches");for(int i=0;i<catches.length();i++)add(catches.getJSONObject(i));
  button(panel,"어종 추가",()->{try{add(new JSONObject());}catch(Exception e){error(e);}});
  button(panel,"인식 근거 보기",()->new ReadableDialog.Builder(a).setTitle("인식 근거").setMessage(evidenceText()).setPositiveButton("닫기",null).show());
  label(panel,"모르면 빈칸으로 저장하세요.\n특이사항은 선택입니다.");
  ScrollView scroll=new ScrollView(a);scroll.addView(panel);
  dialog=new ReadableDialog.Builder(a).setTitle("자망·통발 기록 확인").setView(scroll).setPositiveButton("확인 내용 저장",null).setNegativeButton("취소",null).create();
  dialog.setOnShowListener(v->{ReadableDialog.style(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->commit());});dialog.setOnDismissListener(v->done.run());dialog.show();
 }
 private String evidenceText(){
  StringBuilder b=new StringBuilder();JSONObject evidence=original.optJSONObject("evidence");
  for(String[] pair:new String[][]{{"deployment_date","투망일"},{"haul_quantity","양망 수량"},{"redeploy_quantity","재투망 수량"}}){JSONArray list=evidence.optJSONArray(pair[0]);if(list!=null)for(int i=0;i<list.length();i++)b.append(pair[1]).append(": ").append(list.optString(i)).append('\n');}
  JSONArray catches=original.optJSONArray("catches");for(int i=0;i<catches.length();i++){JSONArray list=catches.optJSONObject(i).optJSONArray("evidence");if(list!=null)for(int j=0;j<list.length();j++)b.append(list.optString(j)).append('\n');}
  JSONArray warnings=original.optJSONArray("warnings");for(int i=0;i<warnings.length();i++)b.append("• ").append(warnings.optString(i)).append('\n');
  return b.length()==0?"인식한 근거 문장이 없습니다. 원음을 확인해 주세요.":b.toString();
 }
 private void add(JSONObject base) throws JSONException {
  LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);catchPanel.addView(box);
  EditText[] row={input(box,"어종",base.opt("species"),false),input(box,"마릿수·수량 (선택)",base.opt("quantity"),true),input(box,"단위 (마리·상자 등)",base.opt("unit"),false),input(box,"무게 kg (선택)",base.opt("weight_kg"),true)};
  rows.add(row);originals.put(row,new JSONObject(base.toString()));button(box,"이 어종 빼기",()->{rows.remove(row);originals.remove(row);catchPanel.removeView(box);});
 }
 private Object value(EditText e,boolean number){String s=e.getText().toString().trim();if(s.isEmpty())return JSONObject.NULL;if(!number)return s;Double v=KoreanNumber.parse(s);if(v==null)throw new IllegalArgumentException("숫자를 확인해 주세요.");return v;}
 private void commit(){try{
  if(RecorderApp.exporting||CaptureService.recording||TranscriptionService.running)throw new IllegalStateException("진행 중인 작업을 마친 뒤 확인하세요.");
  JSONObject d=new JSONObject(original.toString());String selected=type.getSelectedItemPosition()==0?"GILLNET":"POT";
  d.put("gear_type",selected).put("gear_unit",GearLog.unit(selected)).put("deployment_date",value(date,false));
  d.put("date_status",d.isNull("deployment_date")?(d.isNull("deployment_date_candidate")?"MISSING":"CANDIDATE"):"CONFIRMED");
  if(!d.isNull("deployment_date"))GearLog.confirmDeploymentDate(d,d.getString("deployment_date"));
  d.put("haul_quantity",value(haul,true)).put("redeploy_quantity",value(reset,true)).put("disposition",DISPOSITIONS[disposition.getSelectedItemPosition()]).put("outcome",OUTCOMES[outcome.getSelectedItemPosition()]);
  for(String flag:new String[]{"gear_conflict","quantity_uncertainty","disposition_conflict","additional_uncertainty","recognition_uncertain"})d.remove(flag);
  JSONArray catches=new JSONArray();for(EditText[] r:rows){JSONObject row=new JSONObject(originals.get(r).toString());row.put("species",value(r[0],false)).put("quantity",value(r[1],true)).put("unit",value(r[2],false)).put("weight_kg",value(r[3],true));row.put("outcome",row.optDouble("quantity",0)>0||row.optDouble("weight_kg",0)>0?"CAUGHT":"UNCONFIRMED").put("approximate",row.optBoolean("approximate",false));JSONArray evidence=row.optJSONArray("evidence");if(evidence==null)evidence=new JSONArray();row.put("evidence",evidence.put("사용자 직접 확인"));catches.put(row);}d.put("catches",catches);
  JSONArray edits=new JSONArray();for(Iterator<String> keys=d.keys();keys.hasNext();){String key=keys.next();if(!Objects.equals(String.valueOf(original.opt(key)),String.valueOf(d.opt(key))))edits.put(new JSONObject().put("field",key).put("before",original.opt(key)).put("after",d.opt(key)));}d.put("user_edits",edits);
  StructuredLog.validate(d);save.apply(d);dialog.dismiss();Toast.makeText(a,GearLog.issues(d).length()==0?"기록 확인 완료":"미확인 항목을 남기고 저장했습니다.",Toast.LENGTH_LONG).show();
 }catch(Exception e){error(e);}}
 private void error(Exception e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}
}
