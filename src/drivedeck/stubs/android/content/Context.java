package android.content;
public abstract class Context {
  public abstract Context getApplicationContext();
  public abstract SharedPreferences getSharedPreferences(String n,int m);
  public abstract ContentResolver getContentResolver();
  public abstract android.content.res.AssetManager getAssets();
  public abstract java.io.File getFilesDir();
  public abstract void startActivity(Intent i);
  public abstract void sendBroadcast(Intent i);
  public abstract ComponentName startService(Intent i);
  public abstract ComponentName startForegroundService(Intent i);
  public abstract Intent registerReceiver(BroadcastReceiver r, IntentFilter f);
  public abstract Object getSystemService(String n);
  public abstract String getPackageName();
  public abstract android.content.pm.PackageManager getPackageManager();
  public abstract java.io.File getCacheDir();
  public abstract java.io.File getNoBackupFilesDir();
}
