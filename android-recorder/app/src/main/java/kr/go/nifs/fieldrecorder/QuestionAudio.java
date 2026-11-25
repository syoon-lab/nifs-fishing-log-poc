package kr.go.nifs.fieldrecorder;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.*;
import android.os.*;

/** Bundled Korean speech: no network, TTS engine or voice download required. */
final class QuestionAudio {
 interface Listener {void state(boolean busy,String message);}
 private static volatile boolean playing;
 private static volatile long quietAfter;
 static boolean isBusy(){return playing||SystemClock.elapsedRealtime()<quietAfter;}
 private final Context context;private final AudioManager manager;private final Handler ui=new Handler(Looper.getMainLooper());
 private MediaPlayer player;private AudioFocusRequest focus;private int generation;
 QuestionAudio(Context c){context=c;manager=c.getSystemService(AudioManager.class);}
 void play(String asset,Listener listener){
  stop();final int token=++generation;playing=true;listener.state(true,"질문을 듣고 계십니다");
  try{
   if(CaptureService.recording||manager.getMode()==AudioManager.MODE_IN_CALL||manager.getMode()==AudioManager.MODE_IN_COMMUNICATION)throw new IllegalStateException("통화·녹음 중에는 질문을 재생하지 않습니다");
   AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
   focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change->{if(change<0&&token==generation)finish(token,listener,"질문 재생이 중단됐습니다 · 다시 듣기");},ui).build();
   if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException("질문을 재생할 수 없습니다 · 화면을 확인해 주세요");
   player=new MediaPlayer();player.setAudioAttributes(attributes);
   try(AssetFileDescriptor fd=context.getAssets().openFd("questions/"+asset+".m4a")){player.setDataSource(fd.getFileDescriptor(),fd.getStartOffset(),fd.getLength());}
   if(Build.VERSION.SDK_INT>=28)for(AudioDeviceInfo output:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(output.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){player.setPreferredDevice(output);break;}
   player.setOnPreparedListener(p->{if(token!=generation)return;if(CaptureService.recording){finish(token,listener,"녹음 중에는 질문을 재생하지 않습니다");return;}p.start();});
   player.setOnCompletionListener(p->finish(token,listener,manager.getStreamVolume(AudioManager.STREAM_MUSIC)==0?"소리 크기가 0입니다 · 음량을 올리고 다시 듣기":"답변 녹음을 눌러 주세요"));
   player.setOnErrorListener((p,w,e)->{finish(token,listener,"음성 재생 실패 · 화면을 보고 답변할 수 있습니다");return true;});player.prepareAsync();
   ui.postDelayed(()->{if(token==generation)finish(token,listener,"질문 재생을 마쳤습니다 · 다시 듣기 가능");},20000);
  }catch(Exception e){finish(token,listener,e.getMessage()==null?"음성 재생 실패 · 화면을 확인해 주세요":e.getMessage());}
 }
 private void finish(int token,Listener listener,String message){
  if(token!=generation)return;release();playing=false;quietAfter=SystemClock.elapsedRealtime()+350;
  ui.removeCallbacksAndMessages(null);ui.postDelayed(()->{if(token==generation)listener.state(false,message);},400);
 }
 private void release(){if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}if(focus!=null){manager.abandonAudioFocusRequest(focus);focus=null;}}
 void stop(){generation++;ui.removeCallbacksAndMessages(null);release();playing=false;quietAfter=SystemClock.elapsedRealtime()+350;}
}
