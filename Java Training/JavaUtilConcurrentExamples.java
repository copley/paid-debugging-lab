import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class JavaUtilConcurrentExamples {

    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> future = executor.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    return 21 * 2;
                }
            });
            System.out.println("future result: " + future.get(2, TimeUnit.SECONDS));

            List<Callable<String>> tasks = new ArrayList<Callable<String>>();
            tasks.add(namedTask("alpha"));
            tasks.add(namedTask("beta"));
            for (Future<String> result : executor.invokeAll(tasks)) {
                System.out.println("invokeAll result: " + result.get());
            }

            CompletableFuture<String> chain = CompletableFuture
                    .supplyAsync(new java.util.function.Supplier<String>() {
                        @Override
                        public String get() {
                            return "java";
                        }
                    }, executor)
                    .thenApply(new java.util.function.Function<String, String>() {
                        @Override
                        public String apply(String value) {
                            return value.toUpperCase();
                        }
                    });
            System.out.println("completable future: " + chain.get());

            CountDownLatch latch = new CountDownLatch(2);
            executor.submit(countingTask(latch, "worker-1"));
            executor.submit(countingTask(latch, "worker-2"));
            boolean completed = latch.await(2, TimeUnit.SECONDS);
            System.out.println("latch completed: " + completed);
        } finally {
            shutdownGracefully(executor);
        }
    }

    private static Callable<String> namedTask(final String name) {
        return new Callable<String>() {
            @Override
            public String call() {
                return "task-" + name;
            }
        };
    }

    private static Runnable countingTask(final CountDownLatch latch, final String name) {
        return new Runnable() {
            @Override
            public void run() {
                System.out.println(name + " finished");
                latch.countDown();
            }
        };
    }

    private static void shutdownGracefully(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
