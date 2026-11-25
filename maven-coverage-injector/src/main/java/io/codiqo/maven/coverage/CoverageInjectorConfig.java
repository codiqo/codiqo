package io.codiqo.maven.coverage;

import lombok.experimental.UtilityClass;

@UtilityClass
public class CoverageInjectorConfig {
    public static final String PROP_AGENT_JAR = "codiqo.jacoco.agentJar";
    /** the whole {@code -javaagent} argument of codiqo's test agent (TestRecords), put on the test JVM ahead of JaCoCo's */
    public static final String PROP_TEST_AGENT = "codiqo.testAgent";
    /** the file {@link UninstrumentedModuleGuard} names a module in before it fails the build, read by the codiqo plugin */
    public static final String PROP_UNINSTRUMENTED_REPORT = "codiqo.jacoco.uninstrumentedReport";
}
