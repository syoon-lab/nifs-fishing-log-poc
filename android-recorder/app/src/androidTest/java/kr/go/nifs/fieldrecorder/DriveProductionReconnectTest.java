package kr.go.nifs.fieldrecorder;

import android.accounts.Account;
import android.content.Context;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.tasks.Tasks;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** One-time operator-selected migration probe; ordinary test runs do not revoke grants. */
public class DriveProductionReconnectTest extends InstrumentationTestCase {
 public void testPrepareFreshProductionConsent() throws Exception {
  String requested=((InstrumentationTestRunner)getInstrumentation()).getArguments().getString("production_reconnect_email","");
  if(requested.isEmpty())return;
  Context app=getInstrumentation().getTargetContext();
  assertEquals(requested,DriveAuth.email(app));
  assertTrue(DriveAuth.enabled(app));
  try(DriveQueue q=new DriveQueue(app)){assertEquals("Finish pending uploads before reconnecting",0,q.pending());}
  DriveSync.pause(app);
  Tasks.await(Identity.getAuthorizationClient(app).revokeAccess(RevokeAccessRequest.builder().setAccount(new Account(requested,"com.google")).setScopes(Collections.singletonList(new com.google.android.gms.common.api.Scope(DriveAuth.SCOPE))).build()),45,TimeUnit.SECONDS);
  AuthorizationResult result=Tasks.await(Identity.getAuthorizationClient(app).authorize(DriveAuth.request(requested)),45,TimeUnit.SECONDS);
  assertTrue("A fresh consent screen is required",result.hasResolution());
  assertFalse(DriveAuth.enabled(app));
 }
}
