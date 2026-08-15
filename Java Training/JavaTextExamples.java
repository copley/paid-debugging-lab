import java.text.BreakIterator;
import java.text.Collator;
import java.text.DecimalFormat;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class JavaTextExamples {

    public static void main(String[] args) {
        NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.US);
        System.out.println("currency: " + currency.format(1234.56));

        DecimalFormat decimal = new DecimalFormat("#,##0.00");
        System.out.println("decimal: " + decimal.format(98765.4321));

        String message = MessageFormat.format("Hello {0}, you have {1} tasks", "Max", 3);
        System.out.println("message: " + message);

        Collator collator = Collator.getInstance(Locale.ENGLISH);
        String[] names = {"Åke", "Zoey", "Alice"};
        Arrays.sort(names, collator);
        System.out.println("locale sort: " + Arrays.toString(names));

        BreakIterator words = BreakIterator.getWordInstance(Locale.ENGLISH);
        String sentence = "Java text APIs handle words.";
        words.setText(sentence);
        int start = words.first();
        for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
            String word = sentence.substring(start, end).trim();
            if (!word.isEmpty()) {
                System.out.println("word: " + word);
            }
        }

        SimpleDateFormat legacyFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        System.out.println("legacy formatted date: " + legacyFormat.format(new Date()));
        System.out.println("note: SimpleDateFormat is mutable and not thread-safe");
    }
}
