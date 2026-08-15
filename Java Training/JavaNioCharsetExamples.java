import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class JavaNioCharsetExamples {

    public static void main(String[] args) throws CharacterCodingException {
        String text = "Hello, Java, 😀";
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        System.out.println("UTF-8 bytes: " + Arrays.toString(utf8));
        System.out.println("decoded: " + new String(utf8, StandardCharsets.UTF_8));

        Charset charset = StandardCharsets.UTF_8;
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));
        System.out.println("encoded byte count: " + encoded.remaining());

        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = decoder.decode(encoded);
        System.out.println("decoder output: " + decoded.toString());
    }
}
