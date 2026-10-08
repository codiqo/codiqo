package io.codiqo.llm.review;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.event.Level;
import org.zeroturnaround.process.Processes;
import org.zeroturnaround.process.SystemProcess;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.Getter;
import okhttp3.HttpUrl;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A headless {@code opencode serve} process owned by one review.
 *
 * <p>Everything OpenCode reads or writes lives in a temporary home: its configuration, session database, cache and log.
 * Neither the developer's own OpenCode setup nor the reviewed repository's is read or changed, and several reviews can
 * run side by side; separate homes also matter because concurrent OpenCode processes sharing one session database are
 * reported to fail with SQLITE_BUSY.
 *
 * <p>The password is generated here and handed to the process, so nothing has to be read back from its output.
 * Standard input is closed: a run that waits for an interactive permission answer would otherwise hang for good.
 */
public final class OpenCodeServer implements Closeable {
    private static final int PASSWORD_LENGTH = 32;
    private static final Duration READY_POLL = Duration.ofMillis(250);
    private static final Duration READY_REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static final int SHUTDOWN_SECONDS = 15;
    private static final String OPENCODE_ENV_PREFIX = "OPENCODE_";

    @Getter
    private final String url;

    @Getter
    private final String password;

    private final Process process;
    private final File home;
    private final Log log;

    /**
     * Stops the server when the JVM exits without closing it, as a Ctrl-C during a build does: try-with-resources
     * never runs then, and an orphaned {@code opencode serve} would go on calling the models for nobody.
     */
    private final Thread shutdownHook;

    private OpenCodeServer(String url, String password, Process process, File home, Log log) {
        this.url = url;
        this.password = password;
        this.process = process;
        this.home = home;
        this.log = log;
        this.shutdownHook = new Thread(() -> {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            FileUtils.deleteQuietly(home);
        }, "codiqo-opencode-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }
    @Override
    public void close() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException err) {
            return;
        }

        boolean interrupted = Thread.interrupted();
        List<ProcessHandle> helpers = process.toHandle().descendants().toList();
        try {
            SystemProcess server = Processes.newStandardProcess(process);
            server.destroyGracefully();
            if (BooleanUtils.negate(server.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS))) {
                process.destroyForcibly();
            }
        } catch (IOException err) {
            log.warn("could not stop opencode gracefully, killing it: %s", err.getMessage());
            process.destroyForcibly();
        } catch (InterruptedException err) {
            interrupted = true;
            process.destroyForcibly();
        } finally {
            helpers.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            FileUtils.deleteQuietly(home);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
    public static OpenCodeServer start(RunArgs args, ObjectNode config, Path workTree, Log log) throws IOException {
        int port = freePort();
        String password = RandomStringUtils.secure().nextAlphanumeric(PASSWORD_LENGTH);
        File home = Files.createTempDirectory("codiqo-opencode").toFile();
        File logFile = new File(home, "opencode.log");

        /**
         * Until the server object exists, nothing else deletes the home, and it holds opencode.json with the relay
         * secret or the API key. A missing {@code opencode} executable fails {@code builder.start()} on every run, and
         * each such run used to leave one more codiqo-opencode directory with that configuration in the temp directory.
         */
        Process process;
        try {
            process = launch(args, config, workTree, home, port, password, logFile);
        } catch (IOException | RuntimeException err) {
            FileUtils.deleteQuietly(home);
            throw err;
        }

        String url = RunArgs.loopbackUrl(port).build().toString();
        OpenCodeServer toReturn = new OpenCodeServer(url, password, process, home, log);
        try {
            process.getOutputStream().close();
            awaitReady(toReturn, args.getReviewStartupTimeout(), logFile);
        } catch (IOException err) {
            toReturn.close();
            throw err;
        }
        log.info("opencode serve ready at %s (home %s)", url, home);
        return toReturn;
    }
    private static Process launch(RunArgs args, ObjectNode config, Path workTree, File home, int port, String password, File logFile) throws IOException {
        File configDir = new File(home, "config/opencode");
        FileUtils.forceMkdir(configDir);
        JsonMapper.builder().build().writeValue(new File(configDir, "opencode.json"), config);

        ProcessBuilder builder = new ProcessBuilder(args.getReviewExecutable(), "serve", "--port", Integer.toString(port), "--hostname", RunArgs.LOOPBACK_HOST)
                .directory(workTree.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile);
        /**
         * OpenCode reads configuration from more places than its global directory, and every one of them is closed
         * here, as checked against OpenCode 2.0.20 in a scratch repository. It merges the reviewed repository's own
         * {@code opencode.json} and {@code .opencode/} agents and plugins unless {@code OPENCODE_DISABLE_PROJECT_CONFIG}
         * is set, so reviewing a commit could otherwise run that repository's plugin code or widen the agents' tools;
         * and it reads {@code OPENCODE_CONFIG}, {@code OPENCODE_CONFIG_CONTENT} and their siblings from the environment,
         * which the developer's shell passes on. Only the global directory below, which holds our configuration, is left.
         */
        Map<String, String> env = builder.environment();
        env.keySet().removeIf(name -> Strings.CS.startsWith(name, OPENCODE_ENV_PREFIX));
        env.put("OPENCODE_DISABLE_PROJECT_CONFIG", Boolean.TRUE.toString());
        env.put("OPENCODE_SERVER_PASSWORD", password);
        for (String xdg : List.of("CONFIG", "DATA", "STATE", "CACHE")) {
            env.put("XDG_" + xdg + "_HOME", new File(home, xdg.toLowerCase(Locale.ROOT)).getAbsolutePath());
        }
        return builder.start();
    }
    /**
     * {@code /doc} answers without credentials once the server listens, so readiness is checked there rather than on an
     * API route that would turn every early poll into an authentication failure.
     */
    private static void awaitReady(OpenCodeServer server, Duration timeout, File logFile) throws IOException {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(HttpUrl.get(server.getUrl()).newBuilder().addPathSegment("doc").build().uri()).timeout(READY_REQUEST_TIMEOUT).GET().build();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (server.process.isAlive()) {
                try {
                    if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == HttpResponseStatus.OK.code()) {
                        return;
                    }
                } catch (IOException err) {
                    server.log.log(Level.DEBUG, "opencode not listening yet: %s", err.getMessage());
                } catch (InterruptedException err) {
                    Thread.currentThread().interrupt();
                    throw new IOException(err.getMessage(), err);
                }
                sleep(READY_POLL);
            } else {
                throw new IOException("opencode serve exited with code " + server.process.exitValue() + ": " + startupLog(logFile));
            }
        }
        throw new IOException("opencode serve did not become ready within " + timeout + ": " + startupLog(logFile));
    }
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException(err.getMessage(), err);
        }
    }
    private static String startupLog(File logFile) throws IOException {
        if (logFile.exists()) {
            return FileUtils.readFileToString(logFile, StandardCharsets.UTF_8);
        }
        return StringUtils.EMPTY;
    }
}
