package kr.go.nifs.fieldrecorder;

import android.app.Application;

public class RecorderApp extends Application {
    private static Store store;
    public static volatile boolean exporting;
    public static volatile String exportResult = "";
    @Override public void onCreate() {
        super.onCreate();
        store = new Store(this);
        store.recoverInterrupted();
        DriveSync.resume(this);
        // Only decoder scratch files from a terminated process, never source recordings.
        java.io.File[] scratch=getCacheDir().listFiles((dir,name)->name.startsWith("asr-")&&name.endsWith(".f32"));
        if(scratch!=null)for(java.io.File file:scratch)if(file.isFile())file.delete();
    }
    public static Store store() { return store; }
}
