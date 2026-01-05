package io.codiqo.jdtls;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.JavaVersion;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.FileType;
import org.zeroturnaround.process.JavaProcess;
import org.zeroturnaround.process.Processes;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.Fetch;
import io.codiqo.core.OSDetector;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import okhttp3.HttpUrl;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

public class JdtLspProcess implements Closeable {
    public static final String VERSION = "1.54.0";
    public static final Map<String, String> JDTLS_CONFIG = ImmutableMap.of(
            "osx-x86_64", "config_mac",
            "osx-aarch_64", "config_mac_arm",
            "linux-x86_64", "config_linux",
            "linux-aarch_64", "config_linux_arm",
            "windows-x86_64", "config_win");
    public static final Supplier<HttpUrl.Builder> BASE_URL = new Supplier<HttpUrl.Builder>() {
        @Override
        public HttpUrl.Builder get() {
            return new HttpUrl.Builder().scheme("https").host("download.eclipse.org").addPathSegment("jdtls").addPathSegment("milestones");
        }
    };
    public static final Path CACHE = FileSystems.getDefault().getPath(System.getProperty("user.home"), ".cache", "jdtls");
    static {
        try {
            Files.createDirectories(CACHE);
        } catch (IOException err) {
            throw new ExceptionInInitializerError(err);
        }
    }

    @Delegate
    private final Sinks.Many<Integer> processor = Sinks.many().multicast().directBestEffort();
    private final ExecutorService monitor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setDaemon(true).build());
    private final Log log;
    private final JavaProcess process;

    @SneakyThrows
    public JdtLspProcess(LogFactory logFactory, RunArgs args, Fetch fetch, int port) {
        this.log = logFactory.getLogger(getClass());

        Properties lookup = new Properties();
        OSDetector detector = new OSDetector(logFactory);
        detector.detect(lookup, ImmutableList.of());

        try (InputStream io = BASE_URL
                .get()
                .addPathSegment(VERSION)
                .addPathSegment("latest.txt")
                .build()
                .url()
                .openStream()) {
            String os = JDTLS_CONFIG.get(lookup.getProperty("os.detected.classifier"));
            String latest = StringUtils.trim(IOUtils.toString(io, StandardCharsets.UTF_8));
            Path path = fetch.download(logFactory, BASE_URL.get().addPathSegment(VERSION).addPathSegment(latest).build().url());
            Archiver archiver = ArchiverFactory.createArchiver(FileType.get(path.toFile()));
            Path tempDir = Files.createTempDirectory("jdtls");
            tempDir.toFile().deleteOnExit();

            archiver.extract(path.toFile(), tempDir.toFile());

            Path launcherJar;
            Path config = tempDir.resolve(os);
            Path data = Files.createTempDirectory("data");
            data.toFile().deleteOnExit();

            //
            // ~ well there must be only one equinox launcher jar
            //
            try (Stream<Path> files = Files.list(tempDir.resolve("plugins"))) {
                launcherJar = files
                        .filter(p -> p.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
                        .filter(p -> p.toString().endsWith(".jar"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("could not find 'equinox' launcher jar"));
            }

            List<String> cmd = Lists.newArrayList();
            cmd.add(args.getJavaExecutable());
            cmd.addAll(ImmutableList.of("-server", "-Xlog:disable"));
            cmd.addAll(ImmutableList.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseStringDeduplication"));
            cmd.addAll(
                    ImmutableList.of(
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
                    ImmutableList.of(
                            "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                            "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
                            "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
                            "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
                            "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"));

            URL resource = Resources.getResource("codiqo-dependency-versions.properties");
            try (InputStream stream = resource.openStream()) {
                Properties versions = new Properties();
                versions.load(stream);

                for (Path agent : args.getAgents()) {
                    cmd.add("-javaagent:" + agent.toFile().getAbsolutePath());
                }
            }

            cmd.add("-Djdt.core.sharedIndexLocation=" + CACHE.toFile().getAbsolutePath());
            cmd.addAll(ImmutableList.of("-jar", launcherJar.toString()));
            cmd.addAll(ImmutableList.of("-configuration", config.toString()));
            cmd.addAll(ImmutableList.of("-data", data.toString()));

            //
            // ~ advance JVM options for specific Java versions
            //
            cmd.add(SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_15) ? "-XX:+UseZGC" : "-XX:+UseParallelGC");
            if (SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_16)) {
                cmd.add("--enable-native-access=ALL-UNNAMED");
            }
            if (SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_21)) {
                cmd.add("-XX:+ZGenerational");
            }
            if (SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_23)) {
                cmd.add("--sun-misc-unsafe-memory-access=allow");
            }
            if (SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_24)) {
                cmd.add("-XX:+UseCompactObjectHeaders");
            }

            log.info("starting JDTLS cmd: " + cmd);

            StopWatch stopWatch = StopWatch.createStarted();
            ProcessBuilder builder = new ProcessBuilder(cmd).directory(tempDir.toFile()).inheritIO();
            builder.environment().put("CLIENT_HOST", "localhost");
            builder.environment().put("CLIENT_PORT", String.valueOf(port));
            Process fork = builder.start();
            this.process = Processes.newJavaProcess(fork);

            CompletableFuture.supplyAsync(new Supplier<Integer>() {
                @Override
                public Integer get() {
                    try {
                        fork.waitFor();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return fork.exitValue();
                }
            }, monitor).thenAccept(new Consumer<Integer>() {
                @Override
                public void accept(Integer exitCode) {
                    EmitResult result = processor.tryEmitNext(exitCode);
                    if (result.isSuccess()) {

                    }
                }
            });
            stopWatch.stop();

            log.info("JDTLS process started in %s data: %s, port: %d", stopWatch, data.toUri().toURL().toExternalForm(), port);
        }
    }
    @Override
    public void close() throws IOException {
        try {
            log.info("gracefully shutting down JDT LSP server now ...");
            if (Objects.nonNull(process)) {
                process.destroyGracefully();
                boolean waitFor = process.waitFor(30, TimeUnit.SECONDS);
                if (waitFor) {

                } else {
                    process.destroyForcefully();
                }
            }
        } catch (IOException err) {
            throw err;
        } catch (InterruptedException err) {
            throw new IOException(err.getMessage(), err);
        } finally {
            if (Objects.nonNull(monitor)) {
                monitor.shutdownNow();
            }
        }
    }
}
