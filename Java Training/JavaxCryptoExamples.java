import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

public class JavaxCryptoExamples {

    public static void main(String[] args) throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(128);
        SecretKey key = keyGenerator.generateKey();

        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher encrypt = Cipher.getInstance("AES/CBC/PKCS5Padding");
        encrypt.init(Cipher.ENCRYPT_MODE, key, ivSpec);
        byte[] ciphertext = encrypt.doFinal("secret message".getBytes(StandardCharsets.UTF_8));
        System.out.println("ciphertext bytes: " + Arrays.toString(Arrays.copyOf(ciphertext, 8)) + "...");

        Cipher decrypt = Cipher.getInstance("AES/CBC/PKCS5Padding");
        decrypt.init(Cipher.DECRYPT_MODE, key, ivSpec);
        byte[] plaintext = decrypt.doFinal(ciphertext);
        System.out.println("plaintext: " + new String(plaintext, StandardCharsets.UTF_8));

        System.out.println("lesson: use authenticated encryption such as AES-GCM for real systems");
    }
}
