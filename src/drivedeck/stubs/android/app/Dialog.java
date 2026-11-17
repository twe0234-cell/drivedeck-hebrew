package android.app;
public class Dialog implements android.content.DialogInterface {
  public Dialog(android.content.Context c, int theme){}
  public void setContentView(android.view.View v){}
  public void show(){} public void dismiss(){} public boolean isShowing(){return false;}
  public void setOnDismissListener(android.content.DialogInterface.OnDismissListener l){}
  public android.view.Window getWindow(){return null;}
  public void setCancelable(boolean b){}
  public final boolean requestWindowFeature(int f){return true;}
}
