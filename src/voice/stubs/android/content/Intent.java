package android.content;
public class Intent {
  public Intent(){} public Intent(String a){} public Intent(Context c, Class<?> k){}
  public Intent setAction(String a){return this;} public String getAction(){return null;}
  public Intent addCategory(String c){return this;} public Intent addFlags(int f){return this;}
  public Intent setPackage(String p){return this;} public Intent(String a, android.net.Uri u){} public Intent putExtra(String k,String v){return this;} public Intent putExtra(String k,int v){return this;} public Intent putExtra(String k,boolean v){return this;} public java.util.ArrayList<String> getStringArrayListExtra(String k){return null;} public Intent setFlags(int f){return this;} public Intent setClassName(String p,String c){return this;}
}
