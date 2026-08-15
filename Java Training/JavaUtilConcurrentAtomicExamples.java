import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

public class JavaUtilConcurrentAtomicExamples {

    public static void main(String[] args) {
        AtomicInteger counter = new AtomicInteger(0);
        System.out.println("increment: " + counter.incrementAndGet());
        System.out.println("compareAndSet 1 -> 10: " + counter.compareAndSet(1, 10));
        System.out.println("counter: " + counter.get());

        AtomicReference<String> state = new AtomicReference<String>("NEW");
        boolean moved = state.compareAndSet("NEW", "RUNNING");
        System.out.println("state moved: " + moved + ", value: " + state.get());

        LongAdder adder = new LongAdder();
        adder.add(5);
        adder.increment();
        System.out.println("LongAdder sum: " + adder.sum());

        LongAccumulator max = new LongAccumulator(new java.util.function.LongBinaryOperator() {
            @Override
            public long applyAsLong(long left, long right) {
                return Math.max(left, right);
            }
        }, Long.MIN_VALUE);
        max.accumulate(7);
        max.accumulate(3);
        max.accumulate(42);
        System.out.println("LongAccumulator max: " + max.get());
    }
}
