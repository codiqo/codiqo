package io.codiqo.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.StopWatch;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.LogFactory;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Fetch implements Closeable {
    public static final Path CACHE = FileSystems.getDefault().getPath(System.getProperty("user.home"), ".cache", "fetch");
    static {
        try {
            Files.createDirectories(CACHE);
        } catch (IOException err) {
            throw new ExceptionInInitializerError(err);
        }
    }

    private final OkHttpClient http;

    public Fetch(RunArgs args) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(args.getMaxRequests());
        dispatcher.setMaxRequestsPerHost(args.getMaxRequestsPerHost());

        OkHttpClient.Builder ok = new OkHttpClient.Builder();
        ok.connectTimeout(args.getConnectTimeout());
        ok.readTimeout(args.getReadTimeout());
        ok.dispatcher(dispatcher);
        this.http = ok.build();
    }
    public Path download(LogFactory logFactory, URL url) throws IOException {
        String external = url.toExternalForm();
        String fileName = external.substring(external.lastIndexOf('/') + 1);
        Path cached = CACHE.resolve(fileName);
        Request request = new Request.Builder().url(url).build();

        if (Files.exists(cached)) {
            return cached;
        }

        Path temp = CACHE.resolve(fileName + ".tmp");
        StopWatch stopWatch = StopWatch.createStarted();
        try (Response response = http.newCall(request).execute()) {
            if (response.isSuccessful()) {
                try (InputStream in = response.body().byteStream();OutputStream out = Files.newOutputStream(temp)) {
                    IOUtils.copy(in, out);
                    Files.move(temp, cached, StandardCopyOption.ATOMIC_MOVE);
                    stopWatch.stop();
                }

                logFactory.getLogger(Fetch.class).info("download from: %s to: %s took: %s", url, cached, stopWatch);

                return cached;
            }

            throw new IllegalArgumentException("failed to download from: " + url + " status: " + response.code());
        }
    }
    @Override
    public void close() throws IOException {
        if (Objects.nonNull(http)) {
            try {
                ExecutorService service = http.dispatcher().executorService();
                service.shutdown();
            } finally {
                http.connectionPool().evictAll();
                if (Objects.nonNull(http.cache())) {
                    try (okhttp3.Cache cache = http.cache()) {

                    }
                }
            }
        }
    }
}
