package io.codiqo.maven.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.maven.auth.CredentialStore.Credentials;

class CredentialStoreTest {
    private static final String AUTH_URL = "https://codiqo.io";

    @TempDir
    Path dir;

    @Test
    void storesAndReadsBackAKey() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.store(credentials("key-1", null));

        assertEquals("key-1", store.find().orElseThrow());
    }
    @Test
    void createsTheFileReadableOnlyByItsOwner() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.store(credentials("key-1", null));

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));
    }
    /**
     * A directory another account can write to is a directory where the file can be unlinked and replaced with one
     * holding somebody else's key, which {@link CredentialStore#find()} would then hand to a submission.
     */
    @Test
    void createsItsDirectoryReadableOnlyByItsOwner() throws IOException {
        CredentialStore store = new CredentialStore(dir.resolve("codiqo"), AUTH_URL);
        store.store(credentials("key-1", null));

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file().getParent())));
    }
    /** the host is in the file name, so forgetting one server is deleting one file */
    @Test
    void eachAuthHostGetsItsOwnFile() throws IOException {
        CredentialStore production = new CredentialStore(dir, AUTH_URL);
        CredentialStore local = new CredentialStore(dir, "http://localhost:4321");
        production.store(credentials("prod", null));
        local.store(credentials("local", null));

        assertEquals("credentials-codiqo.io", production.file().getFileName().toString());
        assertEquals("credentials-localhost_4321", local.file().getFileName().toString());
        assertEquals("prod", production.find().orElseThrow());
        assertEquals("local", local.find().orElseThrow());
        assertTrue(new CredentialStore(dir, "https://codiqo.test").find().isEmpty());
    }
    /** two local servers are both "localhost"; one of them holding the other's key would look like a server fault */
    @Test
    void portsSeparateTwoServersOnTheSameHost() throws IOException {
        new CredentialStore(dir, "http://localhost:4321").store(credentials("web-dev", null));

        assertTrue(new CredentialStore(dir, "http://localhost:8788").find().isEmpty());
    }
    @Test
    void urlWithoutAHostIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialStore(dir, "codiqo.io"));
    }
    @Test
    void expiredKeyIsNotOffered() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.store(credentials("stale", Instant.now().minusSeconds(TimeUnit.HOURS.toSeconds(1))));

        assertTrue(store.find().isEmpty());
    }
    /**
     * A key inside the headroom window is treated as gone: re-authorising a minute early costs a browser tab,
     * where submitting with a key that lapses mid-request costs the whole analysis.
     */
    @Test
    void keyAboutToExpireIsNotOffered() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.store(credentials("nearly", Instant.now().plusSeconds(10)));

        assertTrue(store.find().isEmpty());
    }
    @Test
    void refusesAFileOtherUsersCanRead() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.store(credentials("key-1", null));

        Files.setPosixFilePermissions(store.file(), PosixFilePermissions.fromString("rw-r--r--"));

        IOException err = assertThrows(IOException.class, store::find);
        assertTrue(err.getMessage().contains("readable by other users"), err.getMessage());
    }
    private static Credentials credentials(String key, Instant expiresAt) {
        return new Credentials(key, "org-1", Optional.ofNullable(expiresAt).map(Instant::toString).orElse(""));
    }
}
