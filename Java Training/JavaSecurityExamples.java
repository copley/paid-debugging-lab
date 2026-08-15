import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;

public class JavaSecurityExamples {

    public static void main(String[] args) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest("important message".getBytes(StandardCharsets.UTF_8));
        System.out.println("SHA-256 length: " + hash.length);
        System.out.println("SHA-256 first bytes: " + Arrays.toString(Arrays.copyOf(hash, 6)));

        SecureRandom secureRandom = new SecureRandom();
        byte[] nonce = new byte[16];
        secureRandom.nextBytes(nonce);
        System.out.println("secure nonce length: " + nonce.length);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, secureRandom);
        KeyPair keyPair = generator.generateKeyPair();

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update("signed payload".getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update("signed payload".getBytes(StandardCharsets.UTF_8));
        System.out.println("signature valid: " + verifier.verify(signature));
    }
}
