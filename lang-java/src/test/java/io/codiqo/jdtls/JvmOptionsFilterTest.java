package io.codiqo.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class JvmOptionsFilterTest {
    @Test
    void nullInputReturnsEmpty() {
        assertTrue(JvmOptionsFilter.keepMemory(null).isEmpty());
    }
    @Test
    void emptyInputReturnsEmpty() {
        assertTrue(JvmOptionsFilter.keepMemory("").isEmpty());
    }
    @Test
    void whitespaceOnlyReturnsEmpty() {
        assertTrue(JvmOptionsFilter.keepMemory("   \t  ").isEmpty());
    }
    @Test
    void filtersJenkinsfileMavenOpts() {
        String input = "-server -Xms8g -XX:+UseZGC -XX:+SegmentedCodeCache -XX:+UseCompactObjectHeaders --sun-misc-unsafe-memory-access=allow";
        assertEquals(ImmutableList.of("-Xms8g"), JvmOptionsFilter.keepMemory(input));
    }
    @Test
    void keepsHeapAndStack() {
        assertEquals(ImmutableList.of("-Xmx2g", "-Xms512m", "-Xss256k"), JvmOptionsFilter.keepMemory("-Xmx2g -Xms512m -Xss256k"));
    }
    @Test
    void rejectsGcOptions() {
        assertTrue(JvmOptionsFilter.keepMemory("-XX:+UseG1GC -XX:+UseZGC").isEmpty());
    }
    @Test
    void handlesMultipleWhitespace() {
        assertEquals(ImmutableList.of("-Xms8g", "-Xmx16g"), JvmOptionsFilter.keepMemory("-Xms8g    -Xmx16g"));
    }
    @Test
    void rejectsLookalikes() {
        assertTrue(JvmOptionsFilter.keepMemory("-XX:+UseStringDeduplication -Xverify:none").isEmpty());
    }
}
