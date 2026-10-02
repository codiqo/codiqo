package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.junit.jupiter.api.Test;

class ForkMavenOptsTest {
    /**
     * unset, the request carries no MAVEN_OPTS of its own and the invoker hands the fork this JVM's environment, so the
     * analysis heap and the build heap stay one setting, as they always were
     */
    @Test
    void unsetLeavesTheForkOnTheInheritedEnvironment() {
        InvocationRequest request = new DefaultInvocationRequest();

        AbstractAnalyzeMojo.applyForkMavenOpts(request, null);
        AbstractAnalyzeMojo.applyForkMavenOpts(request, "   ");

        assertNull(request.getMavenOpts());
    }
    /**
     * jetty's analysis needs 10 GB while its build keeps 8 GB and must still see the build-cache switch, which only
     * reaches the fork as a system property
     */
    @Test
    void setReplacesTheForksMavenOptsWhole() {
        InvocationRequest request = new DefaultInvocationRequest();

        AbstractAnalyzeMojo.applyForkMavenOpts(request, " -Xmx8g -Dmaven.build.cache.enabled=false ");

        assertEquals("-Xmx8g -Dmaven.build.cache.enabled=false", request.getMavenOpts());
    }
}
