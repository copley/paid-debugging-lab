import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JavaUtilStreamExamples {

    public static void main(String[] args) {
        List<String> words = Arrays.asList("java", "stream", "api", "java", "senior");

        List<String> upperLongWords = words.stream()
                .filter(word -> word.length() > 3)
                .map(String::toUpperCase)
                .distinct()
                .collect(Collectors.toList());
        System.out.println("upper long words: " + upperLongWords);

        Map<Integer, List<String>> byLength = words.stream()
                .collect(Collectors.groupingBy(String::length));
        System.out.println("grouped by length: " + byLength);

        String joined = words.stream()
                .sorted()
                .collect(Collectors.joining(", "));
        System.out.println("joined: " + joined);

        int totalLength = words.stream()
                .mapToInt(String::length)
                .sum();
        System.out.println("total length: " + totalLength);

        boolean anyShort = words.stream().anyMatch(word -> word.length() <= 3);
        System.out.println("any short word: " + anyShort);
    }
}
