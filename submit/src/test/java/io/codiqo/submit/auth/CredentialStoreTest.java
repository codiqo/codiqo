package io.codiqo.submit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialStoreTest {
    private static final String AUTH_URL = "https://codiqo.io";

    @TempDir
    Path dir;

    @Test
    void storesAndReadsBackALogin() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        OAuthTokens tokens = tokens("refresh-1");
        store.store(tokens);

        assertEquals(tokens, store.findOAuth().orElseThrow());
        assertTrue(store.findApiKey().isEmpty());
    }
    @Test
    void aRefreshReplacesTheFileWithoutLeavingATemporaryBehind() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.store(tokens("refresh-1"));
        store.store(tokens("refresh-2"));

        assertEquals("refresh-2", store.findOAuth().orElseThrow().getRefreshToken());
        /** the lock file stays: deleting it while another process may be waiting on it would split the lock in two */
        try (var files = Files.list(dir)) {
            assertEquals(List.of(store.file().getFileName() + ".lock", store.file().getFileName().toString()),
                    files.map(file -> file.getFileName().toString()).sorted(Comparator.reverseOrder()).toList());
        }
    }
    @Test
    void createsTheFileReadableOnlyByItsOwner() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.store(tokens("refresh-1"));

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));
    }
    /**
     * A directory another account can write to is one where the file can be replaced with one holding somebody
     * else's login, which a build would then submit with.
     */
    @Test
    void createsItsDirectoryReadableOnlyByItsOwner() throws IOException {
        CredentialStore store = new CredentialStore(dir.resolve("codiqo"), AUTH_URL);
        store.store(tokens("refresh-1"));

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file().getParent())));
    }
    /** the host is in the file name, so forgetting one server is deleting one file */
    @Test
    void eachAuthHostGetsItsOwnFile() throws IOException {
        new CredentialStore(dir, AUTH_URL).store(tokens("prod"));
        new CredentialStore(dir, "http://localhost:4321").store(tokens("local"));

        assertEquals("prod", new CredentialStore(dir, AUTH_URL).findOAuth().orElseThrow().getRefreshToken());
        assertEquals("local", new CredentialStore(dir, "http://localhost:4321").findOAuth().orElseThrow().getRefreshToken());
        assertEquals("credentials-localhost_4321", new CredentialStore(dir, "http://localhost:4321").file().getFileName().toString());
        assertTrue(new CredentialStore(dir, "http://localhost:8788").findOAuth().isEmpty(), "two local servers on one host stay apart");
    }
    @Test
    void urlWithoutAHostIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialStore(dir, "codiqo.io"));
    }
    /** a laptop that logged in before OAuth keeps its key until the key expires */
    @Test
    void aKeyFromALoginBeforeOAuthIsStillOffered() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.storeApiKey("cdq_live_abc", Instant.now().plus(1, ChronoUnit.DAYS).toString());

        assertEquals("cdq_live_abc", store.findApiKey().orElseThrow());
        assertTrue(store.findOAuth().isEmpty());
    }
    @Test
    void anExpiredKeyIsNotOffered() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.storeApiKey("stale", Instant.now().minusSeconds(TimeUnit.HOURS.toSeconds(1)).toString());

        assertTrue(store.findApiKey().isEmpty());
    }
    @Test
    void aLoginReplacesTheOldKey() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.storeApiKey("cdq_live_abc", StringUtils.EMPTY);
        store.store(tokens("refresh-1"));

        assertFalse(store.findApiKey().isPresent());
    }
    @Test
    void refusesAFileOtherUsersCanRead() throws IOException {
        CredentialStore store = new CredentialStore(dir, AUTH_URL);
        store.store(tokens("refresh-1"));
        Files.setPosixFilePermissions(store.file(), PosixFilePermissions.fromString("rw-r--r--"));

        IOException err = assertThrows(IOException.class, store::findOAuth);
        assertTrue(err.getMessage().contains("readable by other users"), err.getMessage());
    }
    private static OAuthTokens tokens(String refreshToken) {
        return new OAuthTokens("client-1", "access-1", Instant.parse("2026-10-05T12:00:00Z"), refreshToken);
    }
}
