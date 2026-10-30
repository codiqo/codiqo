package io.codiqo.jdtls;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.FileType;
import org.slf4j.event.Level;
import org.zeroturnaround.process.JavaProcess;
import org.zeroturnaround.process.Processes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.util.Fetch;

public class JdtLspProcess implements Closeable {
    /** the one threshold the fork can still miss: a language server before 1.62.0 starts on 21 and 22 (LanguageServerJdk) */
    private static final int MIN_JDK_SUN_MISC_UNSAFE_FLAG = 23;

    private static final String JAR_EXTENSION = "jar";
    private static final String RUNTIME_NAME_PREFIX = "JavaSE-";
    private static final String EMPTY_SOURCES_ARCHIVE = "jdk-without-sources.zip";
    private static final String EXTENSION_BUNDLE_RESOURCE = "jdtls/codiqo-jdtls-extension.jar";
    private static final String LOG_EXTENSION = "log";
    /** the lines of an OSGi resolution failure that name the bundle and what it lacked */
    private static final String UNRESOLVED_MODULE = "org.osgi.framework.BundleException: Could not resolve module";
    private static final String UNRESOLVED_REQUIREMENT = "Unresolved requirement";
    private static final String EXECUTION_ENVIRONMENT = "osgi.ee";
    private static final int LAUNCH_FAILURE_LINE_LIMIT = 2;
    private static final Joiner LAUNCH_FAILURE_CAUSES = Joiner.on("; ");
    private static final long GRACEFUL_SHUTDOWN_MINUTES = 1L;
    private static final long FORCED_SHUTDOWN_SECONDS = 30L;

    private final CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
    private final Log log;
    private final JavaProcess process;
    private final File dataDir;
    private final Path configDir;
    private final Map<String, Object> runtime;
    private final Path extensionBundle;

    public JdtLspProcess(LogFactory logFactory, RunArgs args, Fetch fetch, int port) throws IOException {
        this.log = logFactory.getLogger(getClass());

        LanguageServerJdk.ServerJdk serverJdk = LanguageServerJdk.require(args);

        Properties lookup = new Properties();
        OSDetector detector = new OSDetector(logFactory);
        detector.detect(lookup, List.of());

        String os = RunArgs.JDTLS_CONFIG.get(lookup.getProperty("os.detected.classifier"));
        Path path = fetch.downloadJdtls(logFactory, args);
        Archiver archiver = ArchiverFactory.createArchiver(FileType.get(path.toFile()));
        Path tempDir = Files.createTempDirectory("jdtls");
        tempDir.toFile().deleteOnExit();

        archiver.extract(path.toFile(), tempDir.toFile());

        Path launcherJar;
        Path config = tempDir.resolve(os);
        this.configDir = config;
        Path data = Files.createTempDirectory("data-" + args.effectiveJdtlsVersion());
        this.dataDir = data.toFile();
        this.dataDir.deleteOnExit();

        /**
         * The server runs on exactly the JDK home the version check above read (codiqo.javaHome, else the one running
         * codiqo, resolved past a link to the JDK itself), so that the runtime registered in its settings names the JDK it
         * already knows about (see runtimeWithoutSources). A Homebrew keg root such as opt/openjdk@25 holds no lib
         * directory of its own, so registering it would leave every project without a usable JRE container.
         */
        this.runtime = runtimeWithoutSources(serverJdk.getHome(), serverJdk.getVersion(), data);
        this.extensionBundle = extractExtensionBundle(data);

        try (Stream<Path> files = Files.list(tempDir.resolve("plugins"))) {
            launcherJar = files
                    .filter(p -> p.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
                    .filter(p -> JAR_EXTENSION.equals(FilenameUtils.getExtension(p.toString())))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("could not find 'equinox' launcher jar"));
        }

        String java = serverJdk.getHome().toPath().normalize().resolve("bin").resolve(SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java").toFile().getAbsolutePath();

        List<String> cmd = Lists.newArrayList();
        cmd.add(java);
        cmd.addAll(List.of("-server", "-Xlog:disable"));
        if (Objects.nonNull(args.getJdtDebugPort())) {
            cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:" + args.getJdtDebugPort());
        }
        cmd.addAll(List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseStringDeduplication"));
        cmd.addAll(
                List.of(
                        "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                        "-Dosgi.bundles.defaultStartLevel=4",
                        "-Declipse.product=org.eclipse.jdt.ls.core.product",
                        "-Djava.import.generatesMetadataFilesAtProjectRoot=false",
                        "-Dlog.level=ALL",
                        "-Djdk.xml.maxGeneralEntitySizeLimit=0",
                        "-Djdk.xml.totalEntitySizeLimit=0",
                        "-Dsun.net.inetaddr.ttl=0",
                        "-Dsun.zip.disableMemoryMapping=true",
                        "-Dio.netty.tryReflectionSetAccessible=true",
                        "-Djava.lsp.joinOnCompletion=true",
                        "-Djava.net.preferIPv4Stack=true",
                        "-Djava.awt.headless=true",
                        "-Dfile.encoding=UTF-8"));

        /**
         * the embedded m2e resolves each module's parent POM before it can read that POM's own
         * <repositories>, so it sees only the repositories declared in settings.xml. Maven's enhanced
         * local repository refuses to serve an already-downloaded artifact whose recording repository id
         * in _remote.repositories is not in scope ("present, but unavailable"), which fails every project
         * import and leaves the workspace without a Java model — jdt.ls then answers null to every
         * call-hierarchy query and the whole analysis reports zero callers. The fork build has already
         * populated the local repository by this point, so ignoring the tracking metadata is safe and
         * keeps the call graph working regardless of how the host settings.xml names its repositories.
         */
        cmd.add("-Dmaven.legacyLocalRepo=true");

        /**
         * JDT launching scans the machine for further JDKs (/usr/lib/jvm, JAVA_HOME_* and friends) and binds each project
         * to the one matching its release, unless the CI variable is true: GitHub's runners skipped the scan and bound
         * every project to the server's JDK, while a workstation bound a Java 8 project to an installed JDK 8. A detected
         * JDK also brings its own source attachment back (see runtimeWithoutSources). Off, every machine resolves the
         * projects against the one runtime codiqo registers.
         */
        cmd.add("-DDetectVMInstallationsJob.disabled=true");

        cmd.add("--enable-native-access=ALL-UNNAMED");
        for (String pkg : new String[] {
                "api",
                "file",
                "main",
                "model",
                "parser",
                "processing",
                "tree",
                "util" }) {
            cmd.add("--add-exports=jdk.compiler/com.sun.tools.javac." + pkg + "=ALL-UNNAMED");
        }

        for (String pkg : new String[] {
                "lang",
                "lang.invoke",
                "lang.reflect",
                "io",
                "net",
                "nio",
                "util",
                "util.concurrent",
                "util.concurrent.atomic" }) {
            cmd.add("--add-opens=java.base/java." + pkg + "=ALL-UNNAMED");
        }
        cmd.addAll(
                List.of(
                        "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                        "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
                        "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
                        "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
                        "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"));

        for (File file : args.getAgents()) {
            cmd.add("-javaagent:" + file.getAbsolutePath());
        }

        if (args.isJdtUseSharedIndex()) {
            cmd.add("-Djdt.core.sharedIndexLocation=" + args.sharedIndexDir().toFile().getAbsolutePath());
        }

        cmd.addAll(JvmOptionsFilter.keepMemory(System.getenv("MAVEN_OPTS")));

        cmd.addAll(List.of("-jar", launcherJar.toString()));
        cmd.addAll(List.of("-configuration", config.toString()));
        cmd.addAll(List.of("-data", data.toString()));

        cmd.add("-XX:+UseZGC");

        if (serverJdk.getVersion().feature() >= MIN_JDK_SUN_MISC_UNSAFE_FLAG) {
            cmd.add("--sun-misc-unsafe-memory-access=allow");
        }

        log.info("starting JDTLS cmd: " + cmd);

        StopWatch stopWatch = StopWatch.createStarted();
        ProcessBuilder builder = new ProcessBuilder(cmd).directory(tempDir.toFile()).inheritIO();
        builder.environment().put("CLIENT_HOST", "localhost");
        builder.environment().put("CLIENT_PORT", String.valueOf(port));
        Process fork = builder.start();
        this.process = Processes.newJavaProcess(fork);

        fork.onExit().thenAccept(p -> exitFuture.complete(p.exitValue()));
        stopWatch.stop();

        log.info("JDTLS process started in %s data: %s, port: %d", stopWatch, data.toUri().toURL().toExternalForm(), port);
    }
    public CompletableFuture<Integer> onExit() {
        return exitFuture;
    }
    public Map<String, Object> getRuntime() {
        return runtime;
    }
    public Path getExtensionBundle() {
        return extensionBundle;
    }
    /**
     * Why the Eclipse launcher refused to start, as the OSGi framework wrote it to the configuration directory's log:
     * on exit code 13 the process prints only "An error has occurred. See the log file", and the log names the bundle
     * that could not be resolved and the requirement it missed, such as a newer Java than the one the server runs on.
     */
    public Optional<String> launchFailure() {
        try {
            return launchFailure(configDir);
        } catch (IOException err) {
            log.warn("could not read the language server's configuration log: " + err.getMessage());
            return Optional.empty();
        }
    }
    @Override
    public void close() throws IOException {
        try {
            log.info("gracefully shutting down JDT LSP server now ...");
            if (Objects.nonNull(process)) {
                process.destroyGracefully();
                if (process.waitFor(GRACEFUL_SHUTDOWN_MINUTES, TimeUnit.MINUTES)) {
                    log.log(Level.DEBUG, "JDT LSP server exited gracefully");
                } else {
                    /**
                     * destroyForcefully only signals, so the workspace delete below has to wait for the process to
                     * be gone: a language server still writing into it leaves a directory the delete cannot empty
                     */
                    process.destroyForcefully();
                    if (BooleanUtils.negate(process.waitFor(FORCED_SHUTDOWN_SECONDS, TimeUnit.SECONDS))) {
                        log.warn("JDT LSP server did not exit within %ds of being killed", FORCED_SHUTDOWN_SECONDS);
                    }
                }
            }
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException(err.getMessage(), err);
        } finally {
            if (dataDir.exists()) {
                try {
                    FileUtils.forceDelete(dataDir);
                } catch (IOException err) {
                    log.warn("could not delete the JDT LSP workspace %s: %s", dataDir.getAbsolutePath(), err.getMessage());
                }
            }
        }
    }
    /**
     * Writes the codiqo-jdtls-extension bundle that codiqo-lang-java carries as a resource (see its pom) into the
     * server's data directory: the server installs extension bundles from files named in its initialization options,
     * and taking the jar from this classpath, rather than resolving it from a repository, works wherever codiqo runs.
     * A jar without the resource is a broken build of codiqo, and the analysis stops here instead of searching
     * libraries again.
     */
    @VisibleForTesting
    public static Path extractExtensionBundle(Path dir) throws IOException {
        Path toReturn = dir.resolve(FilenameUtils.getName(EXTENSION_BUNDLE_RESOURCE));
        try (InputStream in = JdtLspProcess.class.getClassLoader().getResourceAsStream(EXTENSION_BUNDLE_RESOURCE)) {
            Files.copy(Objects.requireNonNull(in, EXTENSION_BUNDLE_RESOURCE + " is missing from the codiqo-lang-java jar"), toReturn);
        }
        return toReturn;
    }
    /**
     * The server's JDK as its one Java runtime (the java.configuration.runtimes entry), with an empty archive as its
     * source attachment. Caller searches cover the JRE, and when the JDK ships lib/src.zip JDT parses and resolves the
     * JDK's own source of every class calling a method of the searched name, under the analysed project's release:
     * JDK 25's jdk.jpackage LinuxFromParams, which calls copyInto(a, b) and initialises fields with method references,
     * resolved at Java 11 left a method reference without a descriptor, and the unguarded dereference JDT added for
     * eclipse-jdt/eclipse.jdt.core#5214 threw "rExpression.descriptor is null", failing the run. GitHub's runner JDKs
     * ship src.zip and the eclipse-temurin images do not, so the same commit failed on one and passed on the other.
     * Without sources JDT skips those class files; a caller inside the JDK is outside every analysed project anyway.
     *
     * <p>The path is the real one because that is how JDT records the JDK the server runs on: another spelling (the
     * runner's tool-cache symlink) registers a second runtime and leaves the first, sources and all, in place.
     */
    @VisibleForTesting
    public static Map<String, Object> runtimeWithoutSources(File jdkHome, Runtime.Version version, Path dir) throws IOException {
        Path emptySources = dir.resolve(EMPTY_SOURCES_ARCHIVE);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(emptySources))) {
            zip.finish();
        }
        return Map.of(
                "name", RUNTIME_NAME_PREFIX + version.feature(),
                "path", jdkHome.toPath().toRealPath().toString(),
                "sources", emptySources.toString(),
                "default", true);
    }
    /**
     * The newest log's requirement on a Java version (osgi.ee), with the bundle that missed it, else its first two
     * resolution lines. One bundle the platform cannot resolve leaves every bundle that requires it unresolved as well,
     * and the framework may log one of those first: its "Require-Bundle" line names a consequence, while the Java
     * version is the cause, and the one thing the message needs to say.
     */
    @VisibleForTesting
    public static Optional<String> launchFailure(Path configDir) throws IOException {
        Optional<Path> newest;
        try (Stream<Path> logs = Files.list(configDir)) {
            newest = logs
                    .filter(file -> LOG_EXTENSION.equals(FilenameUtils.getExtension(file.toString())))
                    .max(Comparator.comparing(file -> file.toFile().lastModified()));
        }
        if (newest.isPresent()) {
            List<String> lines = Files.readAllLines(newest.get(), StandardCharsets.UTF_8).stream().map(String::strip).toList();

            Optional<String> module = Optional.empty();
            for (String line : lines) {
                if (Strings.CS.startsWith(line, UNRESOLVED_MODULE)) {
                    module = Optional.of(line);
                } else if (BooleanUtils.and(new boolean[] { Strings.CS.startsWith(line, UNRESOLVED_REQUIREMENT), Strings.CS.contains(line, EXECUTION_ENVIRONMENT) })) {
                    return Optional.of(module.map(bundle -> LAUNCH_FAILURE_CAUSES.join(bundle, line)).orElse(line));
                }
            }

            List<String> causes = lines.stream()
                    .filter(line -> Strings.CS.startsWithAny(line, UNRESOLVED_MODULE, UNRESOLVED_REQUIREMENT))
                    .distinct()
                    .limit(LAUNCH_FAILURE_LINE_LIMIT)
                    .toList();
            if (CollectionUtils.isNotEmpty(causes)) {
                return Optional.of(LAUNCH_FAILURE_CAUSES.join(causes));
            }
        }
        return Optional.empty();
    }
    private static class OSDetector extends kr.motd.maven.os.Detector {
        private final Log log;
        private final Properties properties = new Properties();

        public OSDetector(LogFactory logFactory) {
            this.log = logFactory.getLogger(getClass());
            super.detect(properties, List.of());
        }
        @Override
        protected void log(String message) {
            log.info(message);
        }
        @Override
        protected void logProperty(String name, String value) {
            log.info(name + ": " + value);
        }
        @Override
        public void detect(Properties props, List<String> classifierWithLikes) {
            props.putAll(properties);
        }
    }
}
