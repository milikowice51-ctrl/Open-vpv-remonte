import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Interoperability test: verifies a Python cryptography ECDSA signature using Java. */
public final class VerifyTrusted {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Need key, challenge, signature");
        byte[] der=Base64.getUrlDecoder().decode(args[0]);
        PublicKey pub=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        Signature verifier=Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pub);
        verifier.update(args[1].getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (!verifier.verify(Base64.getUrlDecoder().decode(args[2]))) System.exit(2);
        System.out.println("JAVA_ECDSA_VERIFIED");
    }
}
