package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.codiqo.client.model.SymbolKindModel;

class FileAnalysisPopulatorTest {

    @Test
    void mapsEnumConstantCallerToEnumMember() {
        assertEquals(SymbolKindModel.ENUM_MEMBER, FileAnalysisPopulator.resolveSymbolKind(SymbolKind.EnumMember));
    }
    @Test
    void mapsExecutableKindsToTheirSchemaCounterparts() {
        assertEquals(SymbolKindModel.METHOD, FileAnalysisPopulator.resolveSymbolKind(SymbolKind.Method));
        assertEquals(SymbolKindModel.CONSTRUCTOR, FileAnalysisPopulator.resolveSymbolKind(SymbolKind.Constructor));
        assertEquals(SymbolKindModel.FIELD, FileAnalysisPopulator.resolveSymbolKind(SymbolKind.Field));
        assertEquals(SymbolKindModel.propertyClass, FileAnalysisPopulator.resolveSymbolKind(SymbolKind.Class));
    }
    @ParameterizedTest
    @EnumSource(SymbolKind.class)
    void mapsEveryLanguageServerKind(SymbolKind kind) {
        assertNotNull(FileAnalysisPopulator.resolveSymbolKind(kind), "unmapped language server kind: " + kind);
    }
}
