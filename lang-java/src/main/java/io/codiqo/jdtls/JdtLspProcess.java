package io.codiqo.jdtls;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.FileType;
import org.zeroturnaround.process.JavaProcess;
import org.zeroturnaround.process.Processes;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.util.Fetch;
import lombok.experimental.Delegate;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

class JdtLspProcess implements Closeable {
    @Delegate
    private final Sinks.Many<Integer> processor = Sinks.many().multicast().directBestEffort();
    private final Log log;
    private final JavaProcess process;

    public JdtLspProcess(LogFactory logFactory, RunArgs args, Fetch fetch, int port) throws IOException {
        this.log = logFactory.getLogger(getClass());

        Properties lookup = new Properties();
        OSDetector detector = new OSDetector(logFactory);
        detector.detect(lookup, ImmutableList.of());

        try (InputStream io = RunArgs.JDTLS_BASE_URL
                .get()
                .addPathSegment(args.getJdtlsVersion())
                .addPathSegment("latest.txt")
                .build()
                .url()
                .openStream()) {
            String os = RunArgs.JDTLS_CONFIG.get(lookup.getProperty("os.detected.classifier"));
            String latest = StringUtils.trim(IOUtils.toString(io, StandardCharsets.UTF_8));
            Path path = fetch.download(logFactory, RunArgs.JDTLS_BASE_URL.get().addPathSegment(args.getJdtlsVersion()).addPathSegment(latest).build().url());
            Archiver archiver = ArchiverFactory.createArchiver(FileType.get(path.toFile()));
            Path tempDir = Files.createTempDirectory("jdtls");
            tempDir.toFile().deleteOnExit();

            archiver.extract(path.toFile(), tempDir.toFile());

            Path launcherJar;
            Path config = tempDir.resolve(os);
            Path data = Files.createTempDirectory("data-" + args.getJdtlsVersion());
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

            String java = SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java";
            if (Objects.nonNull(args.getJavaHome())) {
                java = args.getJavaHome().toPath().normalize().resolve("bin").resolve(java).toFile().getAbsolutePath();
            }

            List<String> cmd = Lists.newArrayList();
            cmd.add(java);
            cmd.addAll(ImmutableList.of("-server", "-Xlog:disable"));
            if (Objects.nonNull(args.getJdtDebugPort())) {
                cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:" + args.getJdtDebugPort());
            }
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

            for (File file : args.getAgents()) {
                cmd.add("-javaagent:" + file.getAbsolutePath());
            }

            if (args.isJdtUseSharedIndex()) {
                cmd.add("-Djdt.core.sharedIndexLocation=" + RunArgs.JDT_SHARED_INDEX.resolve(args.getJdtlsVersion()).toFile().getAbsolutePath());
            }

            cmd.addAll(ImmutableList.of("-jar", launcherJar.toString()));
            cmd.addAll(ImmutableList.of("-configuration", config.toString()));
            cmd.addAll(ImmutableList.of("-data", data.toString()));

            //
            // ~ advance JVM options matched to the spawned JDK, not the current JVM
            //
            Runtime.Version spawnedJavaVersion = detectSpawnedJavaVersion(args.getJavaHome());
            cmd.add(spawnedJavaVersion.feature() >= 15 ? "-XX:+UseZGC" : "-XX:+UseParallelGC");
            if (spawnedJavaVersion.feature() >= 16) {
                cmd.add("--enable-native-access=ALL-UNNAMED");
            }
            if (spawnedJavaVersion.feature() >= 23) {
                cmd.add("--sun-misc-unsafe-memory-access=allow");
            }

            log.info("starting JDTLS cmd: " + cmd);

            StopWatch stopWatch = StopWatch.createStarted();
            ProcessBuilder builder = new ProcessBuilder(cmd).directory(tempDir.toFile()).inheritIO();
            builder.environment().put("CLIENT_HOST", "localhost");
            builder.environment().put("CLIENT_PORT", String.valueOf(port));
            Process fork = builder.start();
            this.process = Processes.newJavaProcess(fork);

            fork.onExit().thenAccept(p -> {
                EmitResult result = processor.tryEmitNext(p.exitValue());
                if (result.isSuccess()) {

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
                boolean waitFor = process.waitFor(BigDecimal.ONE.intValue(), TimeUnit.MINUTES);
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

        }
    }
    private static Runtime.Version detectSpawnedJavaVersion(File javaHome) throws IOException {
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
            super.detect(properties, ImmutableList.of());
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
