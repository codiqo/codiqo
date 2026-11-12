package io.codiqo.lang.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.codiqo.api.code.Caller;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.code.UnitKind;

class LspSymbolsTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsEnumConstantCallerToEnumMember() {
        assertEquals(UnitKind.ENUM_MEMBER, LspSymbols.unitKind(SymbolKind.EnumMember));
    }
    @Test
    void mapsExecutableKindsToTheirUnitKinds() {
        assertEquals(UnitKind.METHOD, LspSymbols.unitKind(SymbolKind.Method));
        assertEquals(UnitKind.CONSTRUCTOR, LspSymbols.unitKind(SymbolKind.Constructor));
        assertEquals(UnitKind.FIELD, LspSymbols.unitKind(SymbolKind.Field));
        assertEquals(UnitKind.CLASS, LspSymbols.unitKind(SymbolKind.Class));
    }
    @ParameterizedTest
    @EnumSource(SymbolKind.class)
    void mapsEveryLanguageServerKind(SymbolKind kind) {
        assertNotNull(LspSymbols.unitKind(kind), "unmapped language server kind: " + kind);
    }
    /** the language server's zero-based ranges become the one-based locations the submission writes */
    @Test
    void readsACallerAsTheCodeUnitThatMakesTheCall() {
        Path file = tempDir.resolve("Caller.java");
        CallHierarchyItem from = new CallHierarchyItem("run()", SymbolKind.Method, file.toUri().toString(), range(4, 2, 9, 3), range(4, 2, 4, 2));
        from.setDetail("app.Caller");
        from.setTags(List.of(SymbolTag.Deprecated));

        Caller caller = LspSymbols.caller(new CallHierarchyIncomingCall(from, List.of(range(6, 8, 6, 20)))).orElseThrow();

        assertEquals("run()", caller.getName());
        assertEquals("app.Caller", caller.getSymbol());
        assertEquals(Optional.of(file), caller.getPath());
        assertEquals(Optional.of(UnitKind.METHOD), caller.getKind());
        assertTrue(caller.isDeprecated());
        assertEquals(Optional.of(SourceLocation.builder().startLine(5).startColumn(3).endLine(10).endColumn(4).build()), caller.getLocation());
        assertEquals(List.of(SourceLocation.builder().startLine(7).startColumn(9).endLine(7).endColumn(21).build()), caller.getCallSites());
    }
    @Test
    void aCallWithNoCallerIsNone() {
        assertEquals(Optional.empty(), LspSymbols.caller(new CallHierarchyIncomingCall()));
        assertFalse(LspSymbols.isDeprecated(null));
    }
    private static Range range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Range(new Position(startLine, startCharacter), new Position(endLine, endCharacter));
    }
}
