package android.content.pm;
public abstract class PackageManager {
  public abstract android.content.Intent getLaunchIntentForPackage(String p);
  public abstract java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent i, int f);
  public abstract PackageInfo getPackageInfo(String p, int f) throws NameNotFoundException;
  public static class NameNotFoundException extends Exception { }
}
