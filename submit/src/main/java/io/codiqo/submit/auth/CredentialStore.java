package io.codiqo.submit.auth;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.io.function.IORunnable;
import org.apache.commons.io.function.IOSupplier;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;

/**
 * What a browser login leaves behind, in {@code ~/.codiqo/credentials-<host>}, beside where gh, aws and gcloud keep
 * theirs, so a developer knows where to look and what to delete. The Maven and Gradle plugins share the file, so one
 * login serves both.
 *
 * <p>The host in the file name is the auth host that issued the login, which makes one file per server rather than one
 * file with entries inside it: a login against a local server is worthless against production, and a developer who
 * wants to forget one of them deletes a file.
 *
 * <p>Written {@code rw-------}, and a file others can read is refused rather than used: a token silently shared with
 * every account on the machine is worse than no stored login at all. A refresh rewrites it, so it is replaced
 * atomically, and a process reading it concurrently sees either the old login or the new one.
 *
 * <p>Every change happens under {@link #locked}, which serialises the threads of this JVM and, through a file lock
 * beside the credentials, every other process sharing the login.
 *
 * <p>Logins before OAuth stored an API key here; such a file keeps working until the key expires.
 */
public class CredentialStore {
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(OWNER_READ, OWNER_WRITE);
    /** A directory also needs the execute bit, or its own owner cannot reach the credentials file inside it. */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = EnumSet.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    /**
     * One lock per credentials file for the threads of this JVM. A {@link FileLock} is held by the whole JVM, and a
     * second thread asking for it gets {@code OverlappingFileLockException} rather than waiting, so the threads have to
     * queue here before one of them takes the file lock for the process.
     */
    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = Maps.newConcurrentMap();

    public static final String HOME_PROPERTY = "codiqo.home";

    private static final String KEY = "key";
    private static final String KEY_EXPIRES_AT = "expiresAt";
    private static final String CLIENT_ID = "clientId";
    private static final String ACCESS_TOKEN = "accessToken";
    private static final String ACCESS_TOKEN_EXPIRES_AT = "accessTokenExpiresAt";
    private static final String REFRESH_TOKEN = "refreshToken";

    private final Path file;
    private final Path lockFile;

    /** {@code -Dcodiqo.home} moves the directory, for an agent whose home is not where its builds keep state */
    public CredentialStore(String authUrl) {
        this(Paths.get(System.getProperty(HOME_PROPERTY, Paths.get(System.getProperty("user.home"), ".codiqo").toString())), authUrl);
    }
    public CredentialStore(Path directory, String authUrl) {
        this.file = directory.resolve("credentials-" + host(authUrl));
        this.lockFile = file.resolveSibling(file.getFileName() + ".lock");
    }
    public Optional<OAuthTokens> findOAuth() throws IOException {
        Properties stored = read();

        String refreshToken = stored.getProperty(REFRESH_TOKEN);
        if (StringUtils.isBlank(refreshToken)) {
            return Optional.empty();
        }

        /** An unreadable expiry only costs a refresh, which the server answers either way. */
        Instant accessTokenExpiresAt = Instant.EPOCH;
        try {
            accessTokenExpiresAt = Instant.parse(stored.getProperty(ACCESS_TOKEN_EXPIRES_AT, StringUtils.EMPTY));
        } catch (DateTimeParseException err) {
            accessTokenExpiresAt = Instant.EPOCH;
        }
        return Optional.of(new OAuthTokens(
                stored.getProperty(CLIENT_ID, StringUtils.EMPTY),
                stored.getProperty(ACCESS_TOKEN, StringUtils.EMPTY),
                accessTokenExpiresAt,
                refreshToken));
    }
    /** a key a login stored before OAuth, while it lasts */
    public Optional<String> findApiKey() throws IOException {
        Properties stored = read();

        String key = stored.getProperty(KEY);
        if (StringUtils.isBlank(key) || isExpired(stored.getProperty(KEY_EXPIRES_AT))) {
            return Optional.empty();
        }
        return Optional.of(key);
    }
    /** replaces whatever was stored, a legacy key included: the login it came from is over */
    public void store(OAuthTokens tokens) throws IOException {
        Properties stored = new Properties();
        stored.setProperty(CLIENT_ID, tokens.getClientId());
        stored.setProperty(ACCESS_TOKEN, tokens.getAccessToken());
        stored.setProperty(ACCESS_TOKEN_EXPIRES_AT, tokens.getAccessTokenExpiresAt().toString());
        stored.setProperty(REFRESH_TOKEN, tokens.getRefreshToken());
        locked(() -> write(stored));
    }
    /** for a test or a migration: a login stored before OAuth */
    public void storeApiKey(String key, String expiresAt) throws IOException {
        Properties stored = new Properties();
        stored.setProperty(KEY, key);
        stored.setProperty(KEY_EXPIRES_AT, StringUtils.defaultString(expiresAt));
        locked(() -> write(stored));
    }
    /**
     * Runs {@code action} while no other thread or process sharing this login can change it. A refresh has to read the
     * stored refresh token, spend it and store the rotated one as one step: the server accepts each refresh token once,
     * so two processes refreshing with the same token leave one of them holding {@code invalid_grant}, which looks like
     * an ended login and sends the developer back to the browser in the middle of a build. Re-entrant within a thread,
     * so an action may store what it refreshed.
     */
    public <T> T locked(IOSupplier<T> action) throws IOException {
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), path -> new ReentrantLock());
        jvmLock.lock();
        try {
            if (jvmLock.getHoldCount() > 1) {
                return action.get();
            }
            createOwnerOnlyDirectory();
            try (FileChannel channel = FileChannel.open(lockFile, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), ownerOnly());
                    FileLock lock = channel.lock()) {
                return action.get();
            }
        } finally {
            jvmLock.unlock();
        }
    }
    public void locked(IORunnable action) throws IOException {
        locked(() -> {
            action.run();
            return action;
        });
    }
    public Path file() {
        return file;
    }
    /**
     * Each write gets a temporary file of its own: a fixed name was shared by every writer, so one writer deleted or
     * pre-empted the file another was filling, and its move then failed after the server had already rotated the
     * refresh token. A temporary file a crashed writer left behind is likewise never reused.
     */
    private void write(Properties stored) throws IOException {
        createOwnerOnlyDirectory();

        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp", ownerOnly());
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                stored.store(out, "codiqo credentials, written by the browser login");
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
    private Properties read() throws IOException {
        Properties toReturn = new Properties();
        if (Files.notExists(file)) {
            return toReturn;
        }
        if (isOwnerOnly()) {
            try (InputStream in = Files.newInputStream(file)) {
                toReturn.load(in);
            }
            return toReturn;
        }

        throw new IOException(String.format("%s is readable by other users; run 'chmod 600 %s' or delete it and log in again", file, file));
    }
    /**
     * Anything the owner-only set does not cover is a permission somebody else holds, whether that is read, write or
     * execute. Filesystems without posix permissions pass: there is nothing to inspect and nothing we could repair.
     */
    private boolean isOwnerOnly() throws IOException {
        if (POSIX) {
            return OWNER_ONLY.containsAll(Files.getPosixFilePermissions(file));
        }
        return true;
    }
    /**
     * The directory is owner-only too, or the file could be unlinked and replaced with one holding somebody else's
     * login.
     */
    private void createOwnerOnlyDirectory() throws IOException {
        if (Files.notExists(file.getParent())) {
            if (POSIX) {
                Files.createDirectories(file.getParent(), PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
            } else {
                Files.createDirectories(file.getParent());
            }
        }
    }
    /**
     * The permissions are set when a file is created, not after: chmod does not reach a descriptor another account
     * already holds, so creating the file world-readable and narrowing it afterwards leaves a window in which someone
     * can open it and then read the token we write next.
     */
    private static FileAttribute<?>[] ownerOnly() {
        if (POSIX) {
            return new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(OWNER_ONLY) };
        }
        return new FileAttribute<?>[0];
    }
    /**
     * The port is part of the name when the URL carries one: two local servers are both "localhost", and handing one
     * of them a login issued by the other would fail in a way that looks like a server problem.
     */
    private static String host(String authUrl) {
        URI uri = URI.create(Objects.requireNonNull(authUrl));
        if (StringUtils.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("codiqo.authUrl has no host: " + authUrl);
        }
        if (uri.getPort() > 0) {
            return uri.getHost() + "_" + uri.getPort();
        }
        return uri.getHost();
    }
    /**
     * The timestamp comes from the server as it serialised it, so an unreadable one is treated as usable rather than
     * fatal: the server is the authority on expiry and answers with ERR_API_KEY_EXPIRED.
     */
    private static boolean isExpired(String expiresAt) {
        if (StringUtils.isBlank(expiresAt)) {
            return false;
        }
        try {
            return Instant.parse(expiresAt).minus(RunArgs.CREDENTIAL_EXPIRY_HEADROOM).isBefore(Instant.now());
        } catch (DateTimeParseException err) {
            return false;
        }
    }
}
