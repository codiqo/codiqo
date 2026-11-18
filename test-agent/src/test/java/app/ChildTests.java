package app;

import java.util.concurrent.CountDownLatch;

/** the test methods FakeRunner runs in the child JVM */
public final class ChildTests {
    private ChildTests() {
    }
    public static void hangs() throws InterruptedException {
        new CountDownLatch(1).await();
    }
    public static void finishesAfterTwoRecords() throws InterruptedException {
        Thread.sleep(2_500);
    }
}
