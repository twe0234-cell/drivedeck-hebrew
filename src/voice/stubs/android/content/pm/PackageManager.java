package android.content.pm;
public abstract class PackageManager {
  public abstract android.content.Intent getLaunchIntentForPackage(String p);
  public abstract java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent i, int f);
}
