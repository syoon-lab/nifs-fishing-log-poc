package kr.go.nifs.fieldrecorder;

import android.accounts.Account;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Tokens stay in Play Services/in memory, never in preferences, URLs, logs or backups. */
final class DriveAuth {
 static final String SCOPE="https://www.googleapis.com/auth/drive.file";
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("drive-sync",Context.MODE_PRIVATE);}
 static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false);}
 static String owner(Context c){return prefs(c).getString("owner","");}
 static String email(Context c){return prefs(c).getString("email","");}
 static AuthorizationRequest request(String email){return AuthorizationRequest.builder().setRequestedScopes(Collections.singletonList(new Scope(SCOPE))).setAccount(new Account(email,"com.google")).build();}
 static String token(Context c,String email) throws Exception {
  AuthorizationResult r=Tasks.await(Identity.getAuthorizationClient(c).authorize(request(email)),45,TimeUnit.SECONDS);
  if(r.hasResolution()||r.getAccessToken()==null||r.getAccessToken().isEmpty()||!r.getGrantedScopes().contains(SCOPE))throw new Required();
  return r.getAccessToken();
 }
 static void invalidate(Context c,String token){Identity.getAuthorizationClient(c).clearToken(ClearTokenRequest.builder().setToken(token).build());}
 static final class Required extends Exception {Required(){super("구글 계정 연결을 다시 확인해 주세요.");}}
}
