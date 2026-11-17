// JVM test of HeObdUds: javac -encoding UTF-8 -d /tmp/t src/drivedeck/java/ru/drivedeck/launcher/HeObdMath.java src/drivedeck/java/ru/drivedeck/launcher/HeObdUds.java test/UdsTest.java && java -cp /tmp/t UdsTest
import ru.drivedeck.launcher.*;
import java.util.*;
public class UdsTest {
  static void eq(Object a, Object b){ if(!String.valueOf(a).equals(String.valueOf(b))) throw new AssertionError(a+" != "+b); }
  static int[] p(String raw, String id) throws Exception { return (int[]) call("payload", new Class[]{String.class,String.class}, raw, id); }
  static Object call(String m, Class[] t, Object... a) throws Exception {
    java.lang.reflect.Method x = HeObdUds.class.getDeclaredMethod(m, t); x.setAccessible(true); return x.invoke(null, a); }
  public static void main(String[] a) throws Exception {
    // single frame, as DriveDeck logs it (spaces off, headers on, CR turned to spaces)
    eq(Arrays.toString(p("77D05621800012C  >", "77D")), "[98, 24, 0, 1, 44]");
    // multi frame
    int[] m = p("77D101A621821010000 77D2100001234000012 77D22000000AAAAAAAA 77D2300000003000001 >", "77D");
    eq(m.length, 26); eq(m[0], 0x62); eq(m[3], 1);
    eq(p("NO DATA  >", "77D"), null);
    eq(p("7F2231", "7E8"), null);
    // values: wheel raw 300, tpms record
    Map<?,?> v = (Map<?,?>) call("values", new Class[]{String.class,String.class,int[].class}, "713", "221800", p("77D05621800012C", "77D"));
    eq(v.get("wRaw0"), 300.0);
    StringBuilder rec = new StringBuilder("6218210100004E20");      // avail=1, cal0 = 20000
    rec.append("00004650000032C80000283C");                          // cal1..cal3
    rec.append("00005208000000000000000000000000");                  // warn0 = 21000
    rec.append("00020000").append("00000001");                       // diff (FR=2), flat (RR=1)
    String hex = rec.toString(); int len = hex.length()/2;
    String ff = String.format("77D1%03X%s", len, hex.substring(0, 12));
    StringBuilder fr = new StringBuilder(ff); int seq = 1;
    for (int i = 12; i < hex.length(); i += 14, seq++) fr.append(" 77D2").append(Integer.toHexString(seq & 0xF).toUpperCase()).append(hex.substring(i, Math.min(hex.length(), i + 14)));
    int[] tp = p(fr.toString(), "77D");
    Map<?,?> t = (Map<?,?>) call("values", new Class[]{String.class,String.class,int[].class}, "713", "221821", tp);
    eq(t.get("tpmsCal0"), 20000.0); eq(t.get("tpmsWarn0"), 21000.0); eq(t.get("tpmsDiff1"), 2.0); eq(t.get("tpmsFlat3"), 1.0);
    // engine values
    Map<?,?> e = (Map<?,?>) call("values", new Class[]{String.class,String.class,int[].class}, "7E0", "2211BD", p("7E80562 11BD0E10".replace(" ",""), "7E8"));
    eq(String.format(Locale.US,"%.1f",(Double)e.get("oilT")), "86.9");
    // codes
    List<?> d = (List<?>) call("dtcs", new Class[]{int[].class}, p("7E8075902FF03010009", "7E8"));
    eq(d, "[P0301 00 ✱ (פעיל)]");
    eq(call("wheelScale", new Class[]{double.class,double.class}, 700.0, 70.0), 0.1);
    eq(call("gear", new Class[]{double.class}, 6.0), 4.0);
    // a drive: FR tyre soft (+0.8%), misfires on cyl 2 in 4th gear, 4th gear slipping
    List<HeObdMath.Sample> s = new ArrayList<>(); Random r = new Random(1);
    double mis = 10;
    for (int i = 0; i < 300; i++) {
      HeObdMath.Sample x = new HeObdMath.Sample(); x.t = i * 1500L;
      int g = 3 + (i / 60) % 3; x.gear = g; x.speed = 60; x.rpm = 60 * (g == 3 ? 45 : g == 4 ? 34 : 27) * (g == 4 ? 1 + (r.nextDouble() - 0.5) * 0.12 : 1 + (r.nextDouble() - 0.5) * 0.01);
      x.wheel = new double[]{60, 60 * 1.008, 60, 60}; x.steer = 2;
      if (g == 4 && i % 7 == 0) mis += 2; x.misfire = new double[]{5, mis, 5, 5};
      x.knock = new double[]{0.75, 0.75, 0, 0.75}; x.corr = new double[]{1.0, 1.0, 1.0, 1.0};
      x.clutch1T = 90; x.clutch2T = 110; s.add(x);
    }
    Map<String, Double> st = new HashMap<>(); st.put("tpmsCal0", 20000.0); st.put("tpmsWarn0", 21000.0); st.put("odo", 195000.0); st.put("tpmsDiff1", 2.0);
    st.put("c1Ok", 40.0); st.put("c1Fail", 1.0); st.put("c2Ok", 30.0); st.put("c2Fail", 5.0);
    Map<String, List<String>> codes = new LinkedHashMap<>(); codes.put("713", new ArrayList<>()); codes.put("7E1", Arrays.asList("P189F 00 ✱"));
    List<HeObdMath.Finding> f = HeObdUds.evaluate(s, st, codes, Arrays.asList("ABS"));
    for (HeObdMath.Finding x : f) System.out.println(x);
    String all = f.toString();
    if (!all.contains("גלגל קדמי ימני")) throw new AssertionError("wheel");
    if (!all.contains("רובן בצילינדר 2") || !all.contains("הילוך 4")) throw new AssertionError("misfire");
    if (!all.contains("החלקה בהילוך 4")) throw new AssertionError("slip");
    if (!all.contains("הכיול לא החזיק")) throw new AssertionError("tpms");
    // a bad ABS sensor: RL drops out now and then
    List<HeObdMath.Sample> b = new ArrayList<>();
    for (int i = 0; i < 200; i++) { HeObdMath.Sample x = new HeObdMath.Sample(); x.speed = 50; x.wheel = new double[]{50, 50, i % 20 == 0 ? 0 : 50, 50}; b.add(x); }
    String bad = HeObdUds.evaluate(b, null, null, null).toString();
    if (!bad.contains("חיישן ABS אחורי שמאלי")) throw new AssertionError("sensor " + bad);
    System.out.println("UdsTest OK");
  }
}
