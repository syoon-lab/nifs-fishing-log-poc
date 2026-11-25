package kr.go.nifs.fieldrecorder;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.ApiException;
import org.json.*;

/** Explicit initial consent; subsequent uploads use the same approved account. */
public class DriveSettingsActivity extends Activity {
 private static final int AUTHORIZE=910;
 private EditText email;private TextView status;private Button connect;private String requestedEmail="";
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final Runnable refresh=new Runnable(){public void run(){showStatus();handler.postDelayed(this,2000);}};
 @Override public void onCreate(Bundle state){super.onCreate(state);
  ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);scroll.addView(root);setContentView(scroll);
  if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets edge=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(pad+edge.left,pad+edge.top,pad+edge.right,pad+edge.bottom);return insets;});
  text(root,"구글드라이브 자동 업로드",26);text(root,"입항 후 인터넷이 연결되면 저장된 기록과 녹음을 ZIP으로 전송합니다. Wi-Fi와 모바일 데이터를 사용합니다. 전송 파일을 검증한 뒤 완료로 표시하며, 원본은 휴대전화에 그대로 둡니다.",19);
  text(root,"전송 준비 시점의 전체 기록을 함께 보관합니다. 녹음·음성 확인 중에는 기다리며, Android의 절전 정책에 따라 전송이 늦어질 수 있습니다.",17);
  text(root,"백업에는 원음, 전사, 위치·시각, 확인·수정 이력이 포함됩니다. 연결한 본인의 Google Drive에 저장합니다.",17);
  button(root,"개인정보처리방침",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://fishing-log-field-info.rustictarragon32.chatgpt.site/privacy.html"))));
  email=new EditText(this);email.setSingleLine();email.setTextSize(20);email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);email.setHint("Google 계정 이메일");email.setText(DriveAuth.email(this));root.addView(email);
  connect=button(root,"구글 계정 연결 / 다시 연결",()->authorize());status=text(root,"",19);
  button(root,"지금 전송 / 다시 시도",()->{try{DriveSync.requestNow(this);toast("전송을 요청했습니다. 연결되면 자동으로 진행합니다.");showStatus();}catch(Exception e){toast(e.getMessage());}});
  button(root,"드라이브 폴더 열기",()->{String folder=DriveAuth.prefs(this).getString("folder_id","");if(folder.isEmpty()){toast("첫 전송 때 폴더가 만들어집니다.");return;}startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://drive.google.com/drive/folders/"+Uri.encode(folder))));});
  button(root,"자동 업로드 중지",()->{DriveSync.pause(this);toast("자동 업로드를 중지했습니다. 기록과 전송 대기 자료는 보존됩니다.");showStatus();});
  button(root,"돌아가기",this::finish);
 }
 private TextView text(LinearLayout root,String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setPadding(0,12,0,12);root.addView(v);return v;}
 private Button button(LinearLayout root,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(20);root.addView(b);b.setOnClickListener(v->action.run());return b;}
 private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
 @Override protected void onResume(){super.onResume();handler.post(refresh);}
 @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
 private void showStatus(){try(DriveQueue q=new DriveQueue(this)){
  StringBuilder s=new StringBuilder(DriveAuth.enabled(this)?"자동 업로드 켜짐":"자동 업로드 꺼짐");String account=DriveAuth.email(this);if(!account.isEmpty())s.append("\n계정: ").append(account);s.append("\n대기 ").append(q.pending()).append("건 · 완료 ").append(q.completed()).append("건");
  JSONArray rows=q.rows();for(int i=rows.length()-1;i>=0;i--){JSONObject row=rows.getJSONObject(i);if(!"DONE".equals(row.optString("status"))){if("UPLOADING".equals(row.optString("status")))s.append("\n전송 중");String error=row.optString("error","");if(!error.isEmpty())s.append("\n").append(error);break;}}
  if(!DriveSync.message.isEmpty())s.append("\n").append(DriveSync.message);status.setText(s);
 }catch(Exception e){status.setText("전송 상태를 확인하지 못했습니다. 원본은 폰에 보존됩니다.");}}
 private void authorize(){requestedEmail=email.getText().toString().trim();if(!android.util.Patterns.EMAIL_ADDRESS.matcher(requestedEmail).matches()){toast("Google 계정 이메일을 입력해 주세요.");return;}connect.setEnabled(false);
  Identity.getAuthorizationClient(this).authorize(DriveAuth.request(requestedEmail)).addOnSuccessListener(this,this::authorized).addOnFailureListener(this,this::failed);
 }
 private void authorized(AuthorizationResult result){
  if(result.hasResolution()){try{startIntentSenderForResult(result.getPendingIntent().getIntentSender(),AUTHORIZE,null,0,0,0);}catch(Exception e){failed(e);}return;}
  String token=result.getAccessToken();if(token==null||token.isEmpty()||!result.getGrantedScopes().contains(DriveAuth.SCOPE)){failed(new IllegalStateException("앱이 만든 파일을 저장할 권한이 필요합니다."));return;}
  new Thread(()->{try{JSONObject user=new DriveApi(token).account();String actual=user.getString("emailAddress"),owner=user.getString("permissionId");if(!actual.equalsIgnoreCase(requestedEmail))throw new IllegalStateException("선택한 계정이 입력한 이메일과 다릅니다.");
   String old=DriveAuth.owner(this);try(DriveQueue q=new DriveQueue(this)){if(!old.isEmpty()&&!old.equals(owner)&&q.pending()>0)throw new IllegalStateException("기존 계정의 전송 대기 자료가 있습니다. 기존 계정으로 연결해 주세요.");
    SharedPreferences p=DriveAuth.prefs(this);SharedPreferences.Editor edit=p.edit().putString("email",actual).putString("owner",owner).putBoolean("enabled",true);
    if(!p.contains("enabled_since")||!old.equals(owner))edit.putLong("enabled_since",System.currentTimeMillis());if(!old.equals(owner))edit.remove("folder_id");if(!edit.commit())throw new IllegalStateException("계정 설정을 저장하지 못했습니다.");q.retryBlocked(owner);
   }
   DriveSync.message="계정 연결 완료 · 입항 후 자동 전송합니다.";DriveSync.resume(this);runOnUiThread(()->{connect.setEnabled(true);email.setText(actual);showStatus();toast("자동 업로드를 켰습니다.");});
  }catch(Exception e){runOnUiThread(()->failed(e));}},"drive-connect").start();
 }
 private void failed(Exception e){connect.setEnabled(true);String message=e instanceof ApiException?"구글 연결 실패 ("+((ApiException)e).getStatusCode()+"). 네트워크와 앱 인증 설정을 확인해 주세요.":e instanceof IllegalStateException?e.getMessage():"구글 연결을 완료하지 못했습니다. 인터넷 연결 후 다시 시도해 주세요.";toast(message);DriveSync.message=message;showStatus();}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=AUTHORIZE)return;if(result!=RESULT_OK||data==null){connect.setEnabled(true);toast("계정 연결을 취소했습니다.");return;}try{authorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data));}catch(Exception e){failed(e);}}
}
