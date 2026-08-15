import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

public class JavaUtilConcurrentLocksExamples {

    public static void main(String[] args) {
        Lock lock = new ReentrantLock();
        lock.lock();
        try {
            System.out.println("ReentrantLock protects a critical section");
        } finally {
            lock.unlock();
        }

        ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
        readWriteLock.writeLock().lock();
        try {
            System.out.println("write lock acquired");
        } finally {
            readWriteLock.writeLock().unlock();
        }

        readWriteLock.readLock().lock();
        try {
            System.out.println("read lock acquired");
        } finally {
            readWriteLock.readLock().unlock();
        }

        StampedLock stampedLock = new StampedLock();
        long stamp = stampedLock.writeLock();
        try {
            System.out.println("StampedLock write stamp: " + stamp);
        } finally {
            stampedLock.unlockWrite(stamp);
        }

        long optimisticStamp = stampedLock.tryOptimisticRead();
        boolean valid = stampedLock.validate(optimisticStamp);
        System.out.println("optimistic read valid: " + valid);
    }
}
