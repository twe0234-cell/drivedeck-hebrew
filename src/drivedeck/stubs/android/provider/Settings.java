package android.provider;
import android.content.ContentResolver;
public final class Settings {
  public static final class System { public static boolean putInt(ContentResolver r,String n,int v){return false;} public static String getString(ContentResolver r,String n){return null;} }
  public static final class Secure { public static boolean putInt(ContentResolver r,String n,int v){return false;} public static boolean putString(ContentResolver r,String n,String v){return false;} public static String getString(ContentResolver r,String n){return null;} }
  public static final class Global { public static int getInt(ContentResolver r,String n,int d){return 0;} }
}
