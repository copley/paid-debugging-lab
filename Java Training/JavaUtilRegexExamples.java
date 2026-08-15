import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class JavaUtilRegexExamples {

    public static void main(String[] args) {
        Pattern emailPattern = Pattern.compile("([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+)");
        Matcher matcher = emailPattern.matcher("Contact max@example.com or admin@test.org");
        while (matcher.find()) {
            System.out.println("email: " + matcher.group(0));
            System.out.println("domain: " + matcher.group(2));
        }

        String input = "order-123, order-456";
        System.out.println("replace numbers: " + input.replaceAll("\\d+", "#"));
        System.out.println("split: " + java.util.Arrays.toString("a:b:c".split(":")));

        try {
            Pattern.compile("[");
        } catch (PatternSyntaxException ex) {
            System.out.println("bad regex: " + ex.getDescription());
        }
    }
}
