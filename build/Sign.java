import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.*; import java.nio.file.*; import java.security.*; import java.security.cert.*; import java.security.spec.*; import java.util.*;
public class Sign {
  public static void main(String[] a) throws Exception {
    PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Paths.get(a[0]))));
    X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new FileInputStream(a[1]));
    ApkSigner.SignerConfig sc=new ApkSigner.SignerConfig.Builder("PLATFORM",key,Collections.singletonList(cert)).build();
    new ApkSigner.Builder(Collections.singletonList(sc)).setInputApk(new File(a[2])).setOutputApk(new File(a[3]))
      .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).setMinSdkVersion(26).build().sign();
    ApkVerifier.Result r=new ApkVerifier.Builder(new File(a[3])).build().verify();
    System.out.println("verified="+r.isVerified()+" v1="+r.isVerifiedUsingV1Scheme()+" v2="+r.isVerifiedUsingV2Scheme()+" v3="+r.isVerifiedUsingV3Scheme());
    for (ApkVerifier.IssueWithParams e: r.getErrors()) System.out.println("ERR "+e);
    for (X509Certificate c: r.getSignerCertificates()) System.out.println("signer "+c.getSubjectX500Principal());
  }
}
