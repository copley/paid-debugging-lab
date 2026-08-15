import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class JavaUtilFunctionExamples {

    public static void main(String[] args) {
        Predicate<String> isLong = value -> value.length() > 3;
        Function<String, String> normalize = value -> value.trim().toLowerCase();
        Supplier<String> supplier = () -> " supplied value ";
        Consumer<String> printer = value -> System.out.println("consumed: " + value);
        BiFunction<Integer, Integer, Integer> add = (left, right) -> left + right;
        UnaryOperator<String> exclaim = value -> value + "!";
        BinaryOperator<Integer> max = (left, right) -> Math.max(left, right);

        String normalized = normalize.apply(supplier.get());
        printer.accept(normalized);
        System.out.println("is long: " + isLong.test(normalized));
        System.out.println("add: " + add.apply(2, 3));
        System.out.println("exclaim: " + exclaim.apply("Java"));
        System.out.println("max: " + max.apply(10, 7));

        List<String> names = Arrays.asList("Max", "Alice", "Bob");
        names.stream()
                .filter(isLong)
                .map(exclaim)
                .forEach(printer);
    }
}
