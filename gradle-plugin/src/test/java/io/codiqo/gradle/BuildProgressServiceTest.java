package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildProgressServiceTest {
    @Test
    void attributesTasksToTheProjectThatOwnsThem() {
        assertEquals(":a:b", BuildProgressService.projectPath(":a:b:compileJava"));
        assertEquals(":a", BuildProgressService.projectPath(":a:test"));
        assertEquals(":", BuildProgressService.projectPath(":compileJava"));
    }
}
