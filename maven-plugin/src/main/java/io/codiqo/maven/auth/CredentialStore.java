package io.codiqo.maven.auth;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import lombok.Value;

/**
 * The key a browser login leaves behind, in {@code ~/.codiqo/credentials-<host>} — beside where gh, aws and gcloud
 * keep theirs, so a developer knows where to look and what to delete.
 *
 * <p>The host in the file name is the auth host that issued the key, which is what makes one file per server rather
 * than one file with entries inside it: a key minted against a local server is worthless against production, and a
 * developer who wants to forget one of them can delete a file.
 *
 * <p>Written {@code rw-------}, and a file others can read is refused rather than used — a key silently shared with
 * every account on the machine is worse than no stored key at all.
 */
public class CredentialStore {
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(OWNER_READ, OWNER_WRITE);
    /** a directory also needs the execute bit, or its own owner cannot reach the file inside it */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = EnumSet.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /** re-authorise slightly early, so a submission never fails on an expiry we could see coming */
    private static final Duration EXPIRY_HEADROOM = Duration.ofMinutes(1);

    private final Path file;

    public CredentialStore(String authUrl) {
        this(Paths.get(System.getProperty("user.home"), ".codiqo"), authUrl);
    }
    public CredentialStore(Path directory, String authUrl) {
        this.file = directory.resolve("credentials-" + host(authUrl));
    }
    public Optional<String> find() throws IOException {
        Properties stored = read();

        String key = stored.getProperty("key");
        if (StringUtils.isBlank(key) || isExpired(stored.getProperty("expiresAt"))) {
            return Optional.empty();
        }
        return Optional.of(key);
    }
    public void store(Credentials credentials) throws IOException {
        Properties stored = new Properties();
        stored.setProperty("key", credentials.getKey());
        // written for whoever opens the file wondering which organisation a key belongs to; nothing reads it back
        stored.setProperty("organizationId", StringUtils.defaultString(credentials.getOrganizationId()));
        stored.setProperty("expiresAt", StringUtils.defaultString(credentials.getExpiresAt()));

        createOwnerOnly();

        try (OutputStream out = Files.newOutputStream(file)) {
            stored.store(out, "codiqo credentials, written by the browser login");
        }
    }
    public Path file() {
        return file;
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
     * The permissions are set when the file is created, not after: chmod does not reach a descriptor another account
     * already holds, so creating the file world-readable and narrowing it afterwards leaves a window in which someone
     * can open it and then read the key we write next. The same reasoning gives the directory owner-only permissions —
     * otherwise the file could be unlinked and replaced with one holding somebody else's key.
     */
    private void createOwnerOnly() throws IOException {
        if (POSIX) {
            if (Files.notExists(file.getParent())) {
                Files.createDirectories(file.getParent(), PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
            }
            if (Files.notExists(file)) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            }
            Files.setPosixFilePermissions(file, OWNER_ONLY);
            return;
        }

        Files.createDirectories(file.getParent());
        if (Files.notExists(file)) {
            Files.createFile(file);
        }
    }
    /**
     * The port is part of the name when the URL carries one: two local servers are both "localhost", and handing one
     * of them a key minted by the other would fail in a way that looks like a server problem.
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
     * fatal: the server is the authority on expiry and answers with ERR_API_KEY_EXPIRED, where throwing here would
     * take down every submission over a format we only cache.
     */
    private static boolean isExpired(String expiresAt) {
        if (StringUtils.isBlank(expiresAt)) {
            return false;
        }
        try {
            return Instant.parse(expiresAt).minus(EXPIRY_HEADROOM).isBefore(Instant.now());
        } catch (DateTimeParseException err) {
            return false;
        }
    }
    @Value
    public static class Credentials {
        String key;
        String organizationId;
        String expiresAt;
    }
}
