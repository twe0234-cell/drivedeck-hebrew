package android.view;
public class View { public static final int VISIBLE = 0; public static final int GONE = 8;
  public View(android.content.Context c){} public interface OnClickListener { void onClick(View v); }
  public void setOnClickListener(OnClickListener l){} public void setPadding(int a,int b,int c,int d){} public void setLayoutDirection(int d){}
  public void setBackgroundColor(int c){} public void setVisibility(int v){} }
