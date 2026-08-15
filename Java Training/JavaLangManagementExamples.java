import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;

public class JavaLangManagementExamples {

    public static void main(String[] args) {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        System.out.println("JVM name: " + runtime.getName());
        System.out.println("JVM uptime ms: " + runtime.getUptime());

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        System.out.println("heap used: " + heap.getUsed());
        System.out.println("heap max: " + heap.getMax());

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        System.out.println("thread count: " + threads.getThreadCount());
        System.out.println("deadlocked threads: " + java.util.Arrays.toString(threads.findDeadlockedThreads()));

        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean collector : collectors) {
            System.out.println("GC: " + collector.getName() + ", collections=" + collector.getCollectionCount());
        }
    }
}
