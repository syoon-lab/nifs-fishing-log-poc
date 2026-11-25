package kr.go.nifs.fieldrecorder;

import android.app.*;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Small field checklist; never edits the transcript, source audio or older confirmations. */
final class FieldReviewDialog {
 static void show(Activity a,Store store,FieldChecks.Check check,Runnable done){
  try{if("WORK".equals(check.phase)){GearReviewDialog.show(a,check.data,d->FieldChecks.saveReview(store,check,d),done);return;}new FieldReviewDialog(a,store,check,done).open();}catch(Exception e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}
 }
 private final Activity activity;private final Store store;private final FieldChecks.Check check;private final Runnable done;
 private final ArrayList<EditText[]> rows=new ArrayList<>();private final Map<EditText[],JSONObject> originals=new IdentityHashMap<>();private LinearLayout content,catchPanel;private EditText work,workers,gear,unit;private Spinner outcome;private AlertDialog dialog;
 private static final String[] VALUES={"UNCONFIRMED","CAUGHT","NO_CATCH","NOT_FISHED"};
 private FieldReviewDialog(Activity a,Store s,FieldChecks.Check c,Runnable d){activity=a;store=s;check=c;done=d;}
 private EditText input(LinearLayout parent,String label,Object value,boolean number){
  TextView t=new TextView(activity);t.setText(label);t.setTextSize(22);parent.addView(t);EditText e=new EditText(activity);e.setTextSize(26);e.setMinHeight(Math.round(64*activity.getResources().getDisplayMetrics().density));e.setHint("미확인");e.setText(value==null||value==JSONObject.NULL?"":value instanceof Number?StructuredLog.format(((Number)value).doubleValue()):value.toString());
  e.setInputType(number?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT);parent.addView(e);return e;
 }
 private void button(LinearLayout parent,String name,Runnable run){Button b=new Button(activity);b.setText(name);b.setTextSize(24);b.setAllCaps(false);parent.addView(b);b.setOnClickListener(v->run.run());}
 private void open() throws Exception {
  content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);int pad=Math.round(20*activity.getResources().getDisplayMetrics().density);content.setPadding(pad,0,pad,0);JSONObject d=check.data;
  if("START".equals(check.phase)){
   work=input(content,"어떤 작업인가요?",d.opt("work_type"),false);workers=input(content,"작업 인원 (명)",d.opt("workers"),true);
   if(!d.isNull("gear_quantity")){gear=input(content,"어구 수량 (말한 경우)",d.opt("gear_quantity"),true);unit=input(content,"어구 단위",d.opt("gear_unit"),false);}
  }else{
   outcome=new Spinner(activity);outcome.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"어획 여부 미확인","어획 있음","어획 없음","조업 안 함"}));outcome.setSelection(Arrays.asList(VALUES).indexOf(d.getString("outcome")));content.addView(outcome);
   catchPanel=new LinearLayout(activity);catchPanel.setOrientation(LinearLayout.VERTICAL);content.addView(catchPanel);
   JSONArray catches=d.getJSONArray("catches");for(int i=0;i<catches.length();i++)addRow(catches.getJSONObject(i));
   button(content,"어종 추가",()->{try{addRow(new JSONObject());}catch(Exception e){error(e);}});
  }
  TextView note=new TextView(activity);note.setText("모르는 항목은 빈칸으로 남겨도 됩니다.\n시각은 자동 기록 · 특이사항은 선택");note.setTextSize(22);content.addView(note);
  ScrollView scroll=new ScrollView(activity);scroll.addView(content);dialog=new ReadableDialog.Builder(activity).setTitle(check.title()+" · 직접 확인").setView(scroll).setPositiveButton("확인 내용 저장",null).setNegativeButton("취소",null).create();
  dialog.setOnShowListener(v->{ReadableDialog.style(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->save());});dialog.setOnDismissListener(v->done.run());dialog.show();
 }
 private void addRow(JSONObject original) throws JSONException {
  LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);catchPanel.addView(box);
  EditText[] r={input(box,"어종",original.opt("species"),false),input(box,"수량 (선택)",original.opt("quantity"),true),input(box,"단위 (마리·상자 등)",original.opt("unit"),false),input(box,"무게 kg (선택)",original.opt("weight_kg"),true)};rows.add(r);originals.put(r,new JSONObject(original.toString()));
  button(box,"이 어종 빼기",()->{rows.remove(r);originals.remove(r);catchPanel.removeView(box);});
 }
 private Object value(EditText e,boolean number){String t=e.getText().toString().trim();if(t.isEmpty())return JSONObject.NULL;if(!number)return t;Double n=KoreanNumber.parse(t);if(n==null)throw new IllegalArgumentException("숫자를 확인해 주세요.");return n;}
 private void save(){try{
  if(RecorderApp.exporting||CaptureService.recording)throw new IllegalStateException("녹음·내보내기를 마친 뒤 저장하세요.");
  JSONObject d=new JSONObject(check.data.toString());d.remove("additional_uncertainty");
  if(work!=null){d.put("work_type",value(work,false)).put("workers",value(workers,true));if(gear!=null)d.put("gear_quantity",value(gear,true)).put("gear_unit",value(unit,false));}
  else{String o=VALUES[outcome.getSelectedItemPosition()];d.put("outcome",o);JSONArray catches=new JSONArray();
   for(EditText[] r:rows){JSONObject base=originals.get(r);JSONObject row=new JSONObject(base.toString()).put("species",value(r[0],false)).put("quantity",value(r[1],true)).put("unit",value(r[2],false)).put("weight_kg",value(r[3],true)).put("approximate",base.optBoolean("approximate",false)).put("evidence",base.optJSONArray("evidence")==null?new JSONArray().put("사용자 현장 직접 확인"):new JSONArray(base.getJSONArray("evidence").toString()).put("사용자 현장 직접 확인"));
    row.put("outcome",row.optDouble("quantity",0)>0||row.optDouble("weight_kg",0)>0?"CAUGHT":"UNCONFIRMED");catches.put(row);
   }d.put("catches",catches);
  }
  FieldChecks.saveReview(store,check,d);dialog.dismiss();Toast.makeText(activity,FieldChecks.issues(d).length()==0?"현장 확인 저장 완료":"미확인 항목을 남기고 저장했습니다.",Toast.LENGTH_LONG).show();
 }catch(Exception e){error(e);}}
 private void error(Exception e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();}
}
