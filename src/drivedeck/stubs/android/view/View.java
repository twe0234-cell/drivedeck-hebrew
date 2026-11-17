package android.view;
public class View {
  public View(android.content.Context c){}
  public void setOnClickListener(OnClickListener l){} public void setPadding(int l,int t,int r,int b){}
  public void setLayoutDirection(int d){} public void setBackgroundColor(int c){} public void setEnabled(boolean e){}
  public void setVisibility(int v){} public void setLayoutParams(ViewGroup.LayoutParams p){} public boolean post(Runnable r){return true;}
  public interface OnClickListener { void onClick(View v); }
}
