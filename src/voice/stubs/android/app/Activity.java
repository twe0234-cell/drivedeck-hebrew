package android.app;
public class Activity extends android.content.ContextWrapper {
  protected void onCreate(android.os.Bundle b){}
  protected void onActivityResult(int req,int res,android.content.Intent data){}
  public void startActivityForResult(android.content.Intent i,int req){}
  public void finish(){}
  public void setContentView(android.view.View v){}
  public void setTitle(CharSequence t){}
  public android.content.pm.PackageManager getPackageManager(){return null;}
  public void requestPermissions(String[] p,int c){}
  public int checkSelfPermission(String p){return 0;}
  protected void onDestroy(){}
  public void onRequestPermissionsResult(int r,String[] p,int[] g){}
}
