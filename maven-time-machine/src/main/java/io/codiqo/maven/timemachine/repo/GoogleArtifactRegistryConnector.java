package io.codiqo.maven.timemachine.repo;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.sisu.Priority;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import com.google.api.client.googleapis.mtls.MtlsProvider;
import com.google.api.client.googleapis.mtls.MtlsUtils;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.artifactregistry.v1.ArtifactRegistry;
import com.google.api.services.artifactregistry.v1.model.GoogleDevtoolsArtifactregistryV1File;
import com.google.api.services.artifactregistry.v1.model.ListFilesResponse;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.cloud.artifactregistry.auth.CommandExecutor;
import com.google.cloud.artifactregistry.auth.CommandExecutorResult;
import com.google.cloud.artifactregistry.auth.CredentialProvider;
import com.google.cloud.artifactregistry.auth.DefaultCredentialProvider;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import io.codiqo.maven.timemachine.TimeMachineConfig;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Named
@Singleton
@Priority(GoogleArtifactRegistryConnector.PRIORITY)
@Slf4j
public class GoogleArtifactRegistryConnector implements SnapshotConnector, Closeable {
    public static final String SCHEME = "artifactregistry";
    public static final int PRIORITY = 10;

    private static final String HOST_SUFFIX = "-maven.pkg.dev";
    private static final String APPLICATION_NAME = "codiqo-time-machine";
    private static final int PAGE_SIZE = 1000;
    private static final char RESOURCE_SEPARATOR = '/';
    private static final Joiner RESOURCE_JOINER = Joiner.on(RESOURCE_SEPARATOR);
    private static final Splitter URL_PATH_SPLITTER = Splitter.on(RESOURCE_SEPARATOR).omitEmptyStrings();
    private static final String FILTER_NAME_STARTS_WITH = "name=\"%s*\"";

    /**
     * Regex matching a Maven snapshot filename, given the {@code Pattern.quote}'d {@code <artifactId>-} prefix.
     * Filename form: {@code <baseVersion>-<yyyyMMdd>.<HHmmss>-<buildNumber>[-<classifier>].<extension>}.
     * Captures: (1) full timestamped version, (2) optional classifier, (3) extension.
     * Files that don't match (e.g. {@code .jar.md5} / {@code .jar.sha1} checksums) are filtered out.
     */
    private static final String SNAPSHOT_FILENAME_PATTERN = "^%s([^/]+?-\\d{8}\\.\\d{6}-\\d+)(?:-([^.]+))?\\.([a-zA-Z0-9]+)$";

    private volatile ArtifactRegistry artifactRegistry;
    private volatile HttpTransport transport;
    private final Map<String, List<SnapshotVersion>> deploysByResource = new ConcurrentHashMap<>();

    @Inject
    @SneakyThrows
    public GoogleArtifactRegistryConnector() {
        GsonFactory jsonFactory = GsonFactory.getDefaultInstance();
        GcloudAwareRequestInitializer httpRequestInitializer = new GcloudAwareRequestInitializer();

        MtlsProvider mtlsProvider = MtlsUtils.getDefaultMtlsProvider();
        NetHttpTransport.Builder transportBuilder = new NetHttpTransport.Builder();
        if (mtlsProvider.useMtlsClientCertificate()) {
            transportBuilder.setSslSocketFactory(mtlsSocketFactory(mtlsProvider));
        }
        transport = transportBuilder.build();

        artifactRegistry = new ArtifactRegistry.Builder(transport, jsonFactory, httpRequestInitializer).setApplicationName(APPLICATION_NAME).build();
    }
    GoogleArtifactRegistryConnector(ArtifactRegistry artifactRegistry) {
        this.artifactRegistry = Objects.requireNonNull(artifactRegistry);
        this.transport = artifactRegistry.getRequestFactory().getTransport();
    }
    @Override
    public boolean supports(RemoteRepository repo) {
        return SCHEME.equals(repo.getProtocol());
    }
    @Override
    public List<SnapshotVersion> listDeploys(RepositorySystemSession session, Artifact artifact, RemoteRepository repo) {
        GoogleArtifactRegistryLocation location = GoogleArtifactRegistryLocation.parse(repo);
        String resourcePrefix = snapshotFolderResourcePrefix(location, artifact);
        return deploysByResource.computeIfAbsent(resourcePrefix, key -> fetchDeploys(location, artifact, repo, key));
    }
    @Override
    @PreDestroy
    public void close() {
        HttpTransport local = transport;
        if (Objects.nonNull(local)) {
            try {
                local.shutdown();
            } catch (IOException err) {
                log.warn("failed to shut down HTTP transport: {}", err.getMessage());
            }
        }
    }
    @SneakyThrows
    private List<SnapshotVersion> fetchDeploys(GoogleArtifactRegistryLocation location, Artifact artifact, RemoteRepository repo, String resourcePrefix) {
        Pattern filenamePattern = snapshotFilenamePattern(artifact);
        List<SnapshotVersion> toReturn = Lists.newArrayList();
        String pageToken = null;
        while (true) {
            ListFilesResponse page = listFilesPage(location, resourcePrefix, pageToken);
            if (Objects.nonNull(page.getFiles())) {
                for (GoogleDevtoolsArtifactregistryV1File file : page.getFiles()) {
                    parseDeploy(file, filenamePattern).ifPresent(toReturn::add);
                }
            }
            pageToken = page.getNextPageToken();
            if (Objects.isNull(pageToken) || pageToken.isEmpty()) {
                return toReturn;
            }
        }
    }
    private ListFilesResponse listFilesPage(GoogleArtifactRegistryLocation location, String resourcePrefix, String pageToken) throws IOException {
        return artifactRegistry.projects().locations().repositories().files()
                .list(location.parentResource())
                .setFilter(String.format(FILTER_NAME_STARTS_WITH, resourcePrefix))
                .setPageSize(PAGE_SIZE)
                .setPageToken(pageToken)
                .execute();
    }
    private static SSLSocketFactory mtlsSocketFactory(MtlsProvider mtlsProvider) throws GeneralSecurityException, IOException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(mtlsProvider.getKeyStore(), mtlsProvider.getKeyStorePassword().toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return sslContext.getSocketFactory();
    }
    private static String snapshotFolderResourcePrefix(GoogleArtifactRegistryLocation location, Artifact artifact) {
        return RESOURCE_JOINER.join(
                location.parentResource(),
                "files",
                artifact.getGroupId().replace('.', RESOURCE_SEPARATOR),
                artifact.getArtifactId(),
                artifact.getBaseVersion()) + RESOURCE_SEPARATOR;
    }
    private static Pattern snapshotFilenamePattern(Artifact artifact) {
        return Pattern.compile(String.format(SNAPSHOT_FILENAME_PATTERN, Pattern.quote(artifact.getArtifactId() + "-")));
    }
    private static Optional<SnapshotVersion> parseDeploy(GoogleDevtoolsArtifactregistryV1File file, Pattern filenamePattern) {
        if (Objects.isNull(file.getName()) || Objects.isNull(file.getCreateTime())) {
            return Optional.empty();
        }
        String resourceName = URLDecoder.decode(file.getName(), StandardCharsets.UTF_8);
        int lastSlash = resourceName.lastIndexOf(RESOURCE_SEPARATOR);
        if (lastSlash < 0) {
            return Optional.empty();
        }
        Matcher matcher = filenamePattern.matcher(resourceName.substring(lastSlash + 1));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        SnapshotVersion sv = new SnapshotVersion();
        sv.setVersion(matcher.group(1));
        sv.setClassifier(Optional.ofNullable(matcher.group(2)).orElse(StringUtils.EMPTY));
        sv.setExtension(matcher.group(3));
        sv.setUpdated(SnapshotConnector.format(Instant.parse(file.getCreateTime())));
        return Optional.of(sv);
    }

    private static final class GcloudAwareRequestInitializer implements HttpRequestInitializer {
        private final CredentialProvider credentialProvider = DefaultCredentialProvider.getInstance();
        private final CommandExecutor commandExecutor = new ZtCommandExecutor();

        @Override
        public void initialize(HttpRequest request) throws IOException {
            Credentials credentials = credentialProvider.getCredential(commandExecutor);
            new HttpCredentialsAdapter(credentials).initialize(request);
            request.setReadTimeout((int) Duration.ofSeconds(TimeMachineConfig.httpTimeoutSeconds()).toMillis());
        }
    }

    private static final class ZtCommandExecutor implements CommandExecutor {
        @Override
        public CommandExecutorResult executeCommand(String command, String... args) throws IOException {
            List<String> argList = Lists.newArrayList();
            argList.add(command);
            argList.addAll(List.of(args));

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try {
                ProcessResult result = new ProcessExecutor()
                        .command(argList)
                        .redirectOutput(stdout)
                        .redirectError(stderr)
                        .exitValueAny()
                        .execute();
                return new CommandExecutorResult(
                        result.getExitValue(),
                        stdout.toString(StandardCharsets.UTF_8),
                        stderr.toString(StandardCharsets.UTF_8));
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
                throw new IOException(err);
            } catch (TimeoutException err) {
                throw new IOException(err);
            }
        }
    }

    @Value
    private static class GoogleArtifactRegistryLocation {
        String project;
        String locationId;
        String repository;

        static GoogleArtifactRegistryLocation parse(RemoteRepository repo) {
            URI uri = URI.create(repo.getUrl());
            String host = uri.getHost();
            if (Objects.isNull(host) || !host.endsWith(HOST_SUFFIX)) {
                throw new IllegalArgumentException(invalidUrlMessage(repo));
            }
            List<String> segments = URL_PATH_SPLITTER.splitToList(Optional.ofNullable(uri.getPath()).orElse(StringUtils.EMPTY));
            if (segments.size() < 2) {
                throw new IllegalArgumentException(invalidUrlMessage(repo));
            }
            String locationId = host.substring(0, host.length() - HOST_SUFFIX.length());
            return new GoogleArtifactRegistryLocation(segments.get(0), locationId, segments.get(1));
        }
        String parentResource() {
            return RESOURCE_JOINER.join(
                    "projects", project,
                    "locations", locationId,
                    "repositories", repository);
        }
        private static String invalidUrlMessage(RemoteRepository repo) {
            return "expected artifactregistry://<location>" + HOST_SUFFIX + "/<project>/<repo>, got: " + repo.getUrl();
        }
    }
}
