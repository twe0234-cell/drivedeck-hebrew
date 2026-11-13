package android.content;
public class ContextWrapper extends Context {
  public Context getApplicationContext(){return null;}
  public SharedPreferences getSharedPreferences(String n,int m){return null;}
  public ContentResolver getContentResolver(){return null;}
  public android.content.res.AssetManager getAssets(){return null;}
  public java.io.File getFilesDir(){return null;}
  public void startActivity(Intent i){}
  public void sendBroadcast(Intent i){}
  public ComponentName startService(Intent i){return null;}
  public ComponentName startForegroundService(Intent i){return null;}
  public Intent registerReceiver(BroadcastReceiver r, IntentFilter f){return null;}
  public void unregisterReceiver(BroadcastReceiver r){}
  public Object getSystemService(String n){return null;}
  public String getPackageName(){return null;}
}
