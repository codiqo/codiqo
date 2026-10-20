package io.codiqo.jdtls;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.FileType;
import org.slf4j.event.Level;
import org.zeroturnaround.process.JavaProcess;
import org.zeroturnaround.process.Processes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.util.Fetch;

public class JdtLspProcess implements Closeable {
    private static final int MIN_JDK_LANGUAGE_SERVER = 21;

    /** the one threshold the fork can still miss: requireLanguageServerJdk admits 21 and 22 */
    private static final int MIN_JDK_SUN_MISC_UNSAFE_FLAG = 23;

    private static final String JAR_EXTENSION = "jar";
    private static final String RUNTIME_NAME_PREFIX = "JavaSE-";
    private static final String EMPTY_SOURCES_ARCHIVE = "jdk-without-sources.zip";
    private static final String EXTENSION_BUNDLE_RESOURCE = "jdtls/codiqo-jdtls-extension.jar";
    private static final long GRACEFUL_SHUTDOWN_MINUTES = 1L;
    private static final long FORCED_SHUTDOWN_SECONDS = 30L;

    private final CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
    private final Log log;
    private final JavaProcess process;
    private final File dataDir;
    private final Map<String, Object> runtime;
    private final Path extensionBundle;

    public JdtLspProcess(LogFactory logFactory, RunArgs args, Fetch fetch, int port) throws IOException {
        this.log = logFactory.getLogger(getClass());

        Runtime.Version spawnedJavaVersion = requireLanguageServerJdk(args);

        Properties lookup = new Properties();
        OSDetector detector = new OSDetector(logFactory);
        detector.detect(lookup, List.of());

        String os = RunArgs.JDTLS_CONFIG.get(lookup.getProperty("os.detected.classifier"));
        String latest = args.resolveJdtlsArchiveName();
        Path path = fetch.download(logFactory, args.jdtlsBaseUrl().addPathSegment(latest).build().url());
        Archiver archiver = ArchiverFactory.createArchiver(FileType.get(path.toFile()));
        Path tempDir = Files.createTempDirectory("jdtls");
        tempDir.toFile().deleteOnExit();

        archiver.extract(path.toFile(), tempDir.toFile());

        Path launcherJar;
        Path config = tempDir.resolve(os);
        Path data = Files.createTempDirectory("data-" + args.effectiveJdtlsVersion());
        this.dataDir = data.toFile();
        this.dataDir.deleteOnExit();

        /**
         * the JDK the version check above read: codiqo.javaHome, else the one running codiqo. The server runs on exactly
         * this JDK so that the runtime registered in its settings names the JDK it already knows about (see
         * runtimeWithoutSources).
         */
        File languageServerJdk = Optional.ofNullable(args.getJavaHome()).orElseGet(SystemUtils::getJavaHome);
        this.runtime = runtimeWithoutSources(languageServerJdk, spawnedJavaVersion, data);
        this.extensionBundle = extractExtensionBundle(data);

        try (Stream<Path> files = Files.list(tempDir.resolve("plugins"))) {
            launcherJar = files
                    .filter(p -> p.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
                    .filter(p -> JAR_EXTENSION.equals(FilenameUtils.getExtension(p.toString())))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("could not find 'equinox' launcher jar"));
        }

        String java = languageServerJdk.toPath().normalize().resolve("bin").resolve(SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java").toFile().getAbsolutePath();

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

        if (spawnedJavaVersion.feature() >= MIN_JDK_SUN_MISC_UNSAFE_FLAG) {
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
    public static Runtime.Version requireLanguageServerJdk(RunArgs args) throws IOException {
        Runtime.Version toReturn = detectSpawnedJavaVersion(args.getJavaHome());
        if (toReturn.feature() < MIN_JDK_LANGUAGE_SERVER) {
            throw new IOException(String.format(Locale.ROOT,
                    "the Eclipse JDT language server needs Java %d or newer, but the analysis fork would run on Java %s (%s)"
                            + " — point codiqo.javaHome at a Java %d+ JDK; the analysed project keeps its own release level",
                    MIN_JDK_LANGUAGE_SERVER,
                    toReturn,
                    Objects.nonNull(args.getJavaHome()) ? args.getJavaHome().getAbsolutePath() : "the JDK running codiqo",
                    MIN_JDK_LANGUAGE_SERVER));
        }
        return toReturn;
    }
    public static Runtime.Version detectSpawnedJavaVersion(File javaHome) throws IOException {
        if (Objects.nonNull(javaHome)) {
            File releaseFile = new File(javaHome, "release");
            if (releaseFile.isFile()) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(releaseFile.toPath())) {
                    props.load(in);
                }
                String version = StringUtils.strip(props.getProperty("JAVA_VERSION"), "\"");
                if (StringUtils.isNotBlank(version)) {
                    return Runtime.Version.parse(version);
                }
            }
        }
        return Runtime.version();
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
