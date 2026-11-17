package android.content.pm;
public abstract class PackageManager {
  public abstract android.content.Intent getLaunchIntentForPackage(String p);
  public abstract java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent i, int f);
  public abstract PackageInfo getPackageInfo(String p, int f) throws NameNotFoundException;
  public static final int COMPONENT_ENABLED_STATE_DISABLED = 2;
  public static final int COMPONENT_ENABLED_STATE_DISABLED_USER = 3;
  public abstract int getApplicationEnabledSetting(String p);
  public abstract void setApplicationEnabledSetting(String p, int state, int flags);
  public static class NameNotFoundException extends Exception { }
}
