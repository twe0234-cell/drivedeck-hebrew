// JVM test of HeObdMath: javac -d /tmp/t src/drivedeck/java/ru/drivedeck/launcher/HeObdMath.java test/ObdTest.java && java -cp /tmp/t ObdTest [folder of drivedeck-logs]
import ru.drivedeck.launcher.HeObdMath;
import java.util.*;
import java.nio.file.*;
import java.util.regex.*;
public class ObdTest {
  static void eq(Object a, Object b){ if(!String.valueOf(a).equals(String.valueOf(b))) throw new AssertionError(a+" != "+b); }
  public static void main(String[] a) throws Exception {
    eq(HeObdMath.decode("010D", HeObdMath.parse("7E803410D12  >","010D")), 18.0);
    eq(HeObdMath.decode("010C", HeObdMath.parse("7E804410C1A90  >","010C")), 1700.0);
    eq(HeObdMath.decode("0144", HeObdMath.parse("7E80441448000  >","0144")), 1.0);
    eq(HeObdMath.decode("0106", HeObdMath.parse("7E8 03 41 06 8A","0106")), (0x8A-128)*100.0/128.0);
    eq(HeObdMath.decode("010D", HeObdMath.parse("41 0D 32 \r\r>","010D")), 50.0);
    eq(HeObdMath.parse("NO DATA\r\r>","0106"), null);
    eq(HeObdMath.parse("?\r>","0106"), null);
    // two ECUs: engine (7E8) wins
    eq(HeObdMath.decode("010D", HeObdMath.parse("7E903410D05\r7E803410D30\r>","010D")), 48.0);
    eq(HeObdMath.dtcs("7E806430201710300\r>", '3'), Arrays.asList("P0171","P0300"));
    eq(HeObdMath.dtcs("7E8024300\r>", '3'), Arrays.asList());
    eq(HeObdMath.dtcs("43 01 33 00 00 00 00", '3'), Arrays.asList("P0133"));
    // real log lines
    Pattern p = Pattern.compile("RX (01[0-9A-F]{2}) (.*)$");
    Map<String,double[]> mm = new TreeMap<>(); int n=0, bad=0;
    for (Path f : (Iterable<Path>) Files.walk(Paths.get(a.length > 0 ? a[0] : "."))::iterator) {
      if (!f.toString().endsWith(".log")) continue;
      for (String l : Files.readAllLines(f, java.nio.charset.StandardCharsets.ISO_8859_1)) {
        if (!l.contains("DriveDeckOBD")) continue; Matcher m = p.matcher(l); if(!m.find()) continue;
        String pid=m.group(1); if (pid.equals("0100")||pid.equals("0120")||pid.equals("0140")||pid.equals("0103")||pid.equals("0151")) continue;
        double v = HeObdMath.decode(pid, HeObdMath.parse(m.group(2), pid)); n++;
        if (Double.isNaN(v)) { bad++; continue; }
        double[] r = mm.computeIfAbsent(pid, k->new double[]{1e9,-1e9,0,0});
        r[0]=Math.min(r[0],v); r[1]=Math.max(r[1],v); r[2]+=v; r[3]++;
      }
    }
    System.out.println("decoded "+n+" replies, undecoded "+bad);
    for (Map.Entry<String,double[]> e: mm.entrySet()) System.out.printf("%s min %.2f max %.2f avg %.2f (%d)%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]/e.getValue()[3], (int)e.getValue()[3]);
    // evaluate a synthetic drive: warm idle with lean trims, then cruise
    List<HeObdMath.Sample> s = new ArrayList<>();
    for (int i=0;i<400;i++){ HeObdMath.Sample x=new HeObdMath.Sample(); x.t=i*1500L; boolean idle=i<120;
      x.rpm= i<5?0: idle?720:2400; x.speed=idle?0:70; x.gpsSpeed=idle?0:68; x.coolant=Math.min(90,30+i); x.stft=idle?9:1; x.ltft=idle?6:2;
      x.map=i<5?99:idle?33:55; x.baro=99; x.throttle=idle?4.5:15; x.voltage=i<5?12.5:14.2; s.add(x);}    
    for (HeObdMath.Finding f : HeObdMath.evaluate(s, 0x00, new ArrayList<String>(), 3.0)) System.out.println(f);
    System.out.println("factor "+HeObdMath.speedFactor(s));
  }
}
