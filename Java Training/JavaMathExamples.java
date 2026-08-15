import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

public class JavaMathExamples {

    public static void main(String[] args) {
        BigDecimal price = new BigDecimal("19.99");
        BigDecimal quantity = new BigDecimal("3");
        BigDecimal total = price.multiply(quantity);
        System.out.println("money-safe total: " + total);

        BigDecimal divided = new BigDecimal("10").divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);
        System.out.println("rounded division: " + divided);

        BigDecimal fromString = new BigDecimal("0.1");
        BigDecimal fromDouble = new BigDecimal(0.1);
        System.out.println("BigDecimal from string: " + fromString);
        System.out.println("BigDecimal from double: " + fromDouble);
        System.out.println("lesson: use strings for decimal constants");

        BigDecimal onePointZero = new BigDecimal("1.0");
        BigDecimal onePointZeroZero = new BigDecimal("1.00");
        System.out.println("equals considers scale: " + onePointZero.equals(onePointZeroZero));
        System.out.println("compareTo ignores scale: " + (onePointZero.compareTo(onePointZeroZero) == 0));

        BigInteger huge = new BigInteger("123456789012345678901234567890");
        System.out.println("huge squared: " + huge.pow(2));
        System.out.println("math context: " + new BigDecimal("12345.6789").round(new MathContext(5)));
    }
}
