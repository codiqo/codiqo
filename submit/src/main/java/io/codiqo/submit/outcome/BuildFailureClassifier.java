package io.codiqo.submit.outcome;

import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.Strings;

import com.google.common.base.Splitter;

import io.codiqo.client.model.AnalysisExcludeCategory;
import lombok.experimental.UtilityClass;

/**
 * Whose failure a failed build is, read from its failure text: what Maven's fork printed, or what Gradle's failed tasks
 * threw. A compiler crash is the toolchain's failure and halts the run; a failed download or a dependency that could not
 * be resolved is the environment's, and the commit is excluded without a review; anything else is the author's build.
 * Both build tools apply these rules to their own text, so the same failure ends the same way on either.
 */
@UtilityClass
public class BuildFailureClassifier {
    /**
     * What javac prints when it crashes, what Error Prone prints when one of its checks throws, and what
     * maven-compiler-plugin prints when javac threw instead of reporting the code's errors. The last was seen on a
     * production commit: javac on a newer JDK than the project's failed with "Fatal error compiling:
     * java.lang.ExceptionInInitializerError", which read as the author's broken build, so the commit's review started and
     * the commit was excluded instead of the run failing. Gradle's failed task carries only Error Prone's: javac's own
     * crash reaches the console alone, so on Gradle it stays the author's.
     */
    private static final String[] COMPILER_CRASH_MARKERS = {
            "An exception has occurred in the compiler",
            "An unhandled exception was thrown by the Error Prone static analysis plugin",
            "Fatal error compiling" };
    /**
     * A failed download names a URL, and either the connection failed, an HTML page came back where the content was
     * expected, or the build tool said it could not fetch or resolve what it named. During a download.eclipse.org outage
     * every path answered 200 with a "Temporary shutdown" page; Spotless, fetching the Eclipse formatter, reported "We
     * could not parse the content at https://download.eclipse.org/..." followed by that page, and the commit was scored
     * as a build its author had broken, with the failed build as a blocking finding. Both conditions are needed: a
     * compile error never names a URL, a plugin that rejects the project's own configuration does not print a web page,
     * and a dependency on a local project Gradle cannot match names no repository.
     */
    private static final Pattern REMOTE_URL = Pattern.compile("https?://\\S+");
    private static final String[] TRANSPORT_FAILURES = {
            "UnknownHostException",
            "ConnectException",
            "NoRouteToHostException",
            "SocketTimeoutException",
            "HttpTimeoutException",
            "SSLHandshakeException",
            "Connection reset",
            "Connection refused" };
    private static final String[] HTML_PAGE_MARKERS = { "<!DOCTYPE html", "<html" };
    /**
     * Gradle's words for a dependency it could not fetch, measured on Gradle 9.9: its failed task never shows the page
     * that came back, but says that a resource was not found where it looked, could not be fetched, or did not parse.
     * Only phrases that speak of a fetch: "Could not resolve all" also opens a local project Gradle could not match, and
     * "Could not find" a Maven goal a POM misnames, both the author's.
     */
    private static final String[] FAILED_FETCHES = {
            "Searched in the following locations",
            "Could not GET '",
            "Could not get resource '",
            "Received status code",
            "Could not parse POM http" };

    /** whether one line of a build's output shows the compiler itself crashed */
    public boolean isCompilerCrash(String line) {
        return Strings.CS.containsAny(line, COMPILER_CRASH_MARKERS);
    }
    /** the first line of a failure text that shows the compiler itself crashed */
    public Optional<String> compilerCrash(String failureText) {
        return Splitter.on(CharUtils.LF).trimResults().omitEmptyStrings().splitToStream(failureText).filter(BuildFailureClassifier::isCompilerCrash).findFirst();
    }
    /** {@code DEPENDENCY_RESOLUTION_FAILURE} for a failed download or an unresolved dependency, the author's {@code BUILD_FAILURE} otherwise */
    public AnalysisExcludeCategory category(String failureText) {
        boolean failedFetch = BooleanUtils.or(new boolean[] {
                Strings.CS.containsAny(failureText, TRANSPORT_FAILURES),
                Strings.CI.containsAny(failureText, HTML_PAGE_MARKERS),
                Strings.CS.containsAny(failureText, FAILED_FETCHES) });
        if (BooleanUtils.and(new boolean[] { failedFetch, REMOTE_URL.matcher(failureText).find() })) {
            return AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE;
        }
        return AnalysisExcludeCategory.BUILD_FAILURE;
    }
}
