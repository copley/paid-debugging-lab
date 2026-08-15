import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

public class JavaUtilCollectionsExamples {

    public static void main(String[] args) {
        List<String> names = new ArrayList<String>();
        names.add("Max");
        names.add("Alice");
        names.add("Bob");
        Collections.sort(names);
        System.out.println("sorted list: " + names);

        Set<String> unique = new HashSet<String>(names);
        unique.add("Max");
        System.out.println("unique set: " + unique);

        TreeSet<String> ordered = new TreeSet<String>(unique);
        System.out.println("ordered set: " + ordered);

        Map<String, Integer> scores = new LinkedHashMap<String, Integer>();
        scores.put("Max", 95);
        scores.put("Alice", 88);
        scores.put("Bob", 91);
        System.out.println("linked map keeps insertion order: " + scores);

        Map<String, Integer> sortedScores = new TreeMap<String, Integer>(scores);
        System.out.println("tree map sorts keys: " + sortedScores);

        Queue<String> queue = new ArrayDeque<String>();
        queue.add("first");
        queue.add("second");
        System.out.println("queue poll: " + queue.poll());

        Optional<Integer> maxScore = findScore(scores, "Max");
        System.out.println("optional score: " + maxScore.orElse(-1));
        System.out.println("missing score: " + findScore(scores, "Nobody").orElse(-1));

        List<String> fixed = Arrays.asList("a", "b", "c");
        System.out.println("arrays.asList: " + fixed);
        System.out.println("uuid: " + UUID.randomUUID());

        Map<MutableKey, String> brokenMap = new HashMap<MutableKey, String>();
        MutableKey key = new MutableKey("id-1");
        brokenMap.put(key, "value");
        key.value = "id-2";
        System.out.println("mutable key lookup after mutation: " + brokenMap.get(key));
        System.out.println("lesson: do not mutate fields used by hashCode/equals while inside a map");
    }

    private static Optional<Integer> findScore(Map<String, Integer> scores, String name) {
        return Optional.ofNullable(scores.get(name));
    }

    private static final class MutableKey {
        private String value;

        private MutableKey(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MutableKey)) {
                return false;
            }
            return value.equals(((MutableKey) other).value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }
}
