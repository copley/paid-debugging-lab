import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public class JavaLangExamples {

    public static void main(String[] args) {
        String literal = "abc";
        char[] chars = {'a', 'b', 'c'};
        String fromChars = new String(chars);
        byte[] bytes = {74, 97, 118, 97};
        String fromBytes = new String(bytes, StandardCharsets.UTF_8);

        System.out.println("literal: " + literal);
        System.out.println("fromChars: " + fromChars);
        System.out.println("fromBytes: " + fromBytes);

        String original = "hello";
        String upper = original.toUpperCase(Locale.ROOT);
        System.out.println("original is unchanged: " + original);
        System.out.println("upper copy: " + upper);

        String text = "Java is cool";
        System.out.println("length: " + text.length());
        System.out.println("charAt(0): " + text.charAt(0));
        System.out.println("contains cool: " + text.contains("cool"));
        System.out.println("substring(5, 7): " + text.substring(5, 7));

        String a = "Java";
        String b = "java";
        System.out.println("equals: " + a.equals(b));
        System.out.println("equalsIgnoreCase: " + a.equalsIgnoreCase(b));
        System.out.println("compareToIgnoreCase: " + a.compareToIgnoreCase(b));

        String sentence = "one fish, two fish, red fish, blue fish";
        System.out.println("first fish: " + sentence.indexOf("fish"));
        System.out.println("last fish: " + sentence.lastIndexOf("fish"));
        System.out.println("replace: " + sentence.replace("fish", "cat"));

        String[] fruits = "apple,banana,orange".split(",");
        System.out.println("split: " + Arrays.toString(fruits));
        System.out.println("join: " + String.join(" - ", fruits));
        System.out.println("trim: [" + "   hello world   ".trim() + "]");
        System.out.println(String.format("%s scored %d%%", "Max", 95));

        String emoji = "A\uD83D\uDE00B";
        System.out.println("emoji: " + emoji);
        System.out.println("char units: " + emoji.length());
        System.out.println("code points: " + emoji.codePointCount(0, emoji.length()));

        StringBuilder builder = new StringBuilder();
        builder.append("Java").append(" ").append("StringBuilder");
        System.out.println("built: " + builder.toString());

        String x = new String("hello");
        String y = "hello";
        System.out.println("x == y: " + (x == y));
        System.out.println("x.intern() == y: " + (x.intern() == y));

        try {
            requireNonEmpty("");
        } catch (IllegalArgumentException ex) {
            System.out.println("caught expected exception: " + ex.getMessage());
        }
    }

    private static void requireNonEmpty(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("value must not be empty");
        }
    }
}
