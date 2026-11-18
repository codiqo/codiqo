package org.junit.codiqo.fake;

import java.lang.reflect.Method;

/**
 * Stands for a test framework in a child JVM: it lives under {@code org.junit.}, which is how the agent recognises one,
 * and invokes the named test method through reflection, as JUnit does.
 */
public final class FakeRunner {
    private FakeRunner() {
    }
    public static void main(String[] args) throws Exception {
        Method test = Class.forName(args[0]).getMethod(args[1]);
        System.out.println("running " + args[0] + "#" + args[1]);
        test.invoke(null);
    }
}
