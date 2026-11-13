package io.codiqo.submit.hotspots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.Test;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.Finding;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.metrics.CodeBlockMetrics;
import io.codiqo.client.model.HotspotFindingKind;
import io.codiqo.client.model.HotspotFindingModel;
import io.codiqo.submit.StubFinding;

class HotspotFindingsTest {
    private static final File FILE = new File("/repo/a/B.java");
    private static final Path WORK_TREE = Path.of("/repo");

    @Test
    void membersReadAsMethodNameAndParameterTypes() {
        assertEquals("toAccountInfo(Account, Set)", HotspotFindings.readable("shop/dto/Mappers.toAccountInfo(Lshop/model/Account;Ljava/util/Set;)Lshop/api/AccountInfo;"));
        assertEquals("apply(int[], Map.Entry, boolean)", HotspotFindings.readable("a/B.apply([ILjava/util/Map$Entry;Z)V"));
        assertEquals("Mappers()", HotspotFindings.readable("shop/dto/Mappers.<init>()V"));
        assertEquals("Mappers()", HotspotFindings.readable("Mappers.<init>()V"));
        assertEquals("B.C(String)", HotspotFindings.readable("a/B$C.<init>(Ljava/lang/String;)V"));
    }
    @Test
    void aFindingOnNestedUnitsIsReportedOnceOnTheInnermost() {
        Finding finding = new StubFinding("pmd", "UnusedLocalVariable", Finding.Severity.WARNING, StubFinding.lines(5, 5), 3);

        List<HotspotFindingModel> found = HotspotFindings.collect(FILE, List.of(
                unit("a/B.outer()V", 1, 20, List.of(finding)),
                unit("a/B.inner()V", 4, 8, List.of(finding))), List.of(), WORK_TREE);

        assertEquals(1, found.size());
        assertEquals("inner()", found.iterator().next().getMember());
    }
    @Test
    void eachToolsFindingsComeMostSevereFirst() {
        List<HotspotFindingModel> found = HotspotFindings.collect(FILE, List.of(unit("a/B.run()V", 1, 20, List.of(
                new StubFinding("pmd", "P3", Finding.Severity.WARNING, StubFinding.lines(2, 2), 3),
                new StubFinding("spotbugs", "S15", Finding.Severity.INFO, StubFinding.lines(3, 3), 15),
                new StubFinding("pmd", "P1", Finding.Severity.ERROR, StubFinding.lines(4, 4), 1),
                new StubFinding("spotbugs", "S2", Finding.Severity.ERROR, StubFinding.lines(5, 5), 2)))), List.of(), WORK_TREE);

        assertEquals(List.of("P1", "P3"), rules(found, HotspotFindingKind.PMD));
        assertEquals(List.of("S2", "S15"), rules(found, HotspotFindingKind.SPOTBUGS));
    }
    private static List<String> rules(List<HotspotFindingModel> found, HotspotFindingKind kind) {
        return found.stream().filter(finding -> kind == finding.getKind()).map(HotspotFindingModel::getRule).toList();
    }
    private static CodeBlockInfo unit(String signature, int startLine, int endLine, List<Finding> findings) {
        CodeBlockInfo toReturn = mock(CodeBlockInfo.class);
        when(toReturn.getSignature()).thenReturn(signature);
        when(toReturn.getLocation()).thenReturn(SourceLocation.builder().startLine(startLine).endLine(endLine).build());
        when(toReturn.coverage()).thenReturn(CodeBlockCoverage.from(new IntObjectHashMap<>()));
        when(toReturn.metrics()).thenReturn(mock(CodeBlockMetrics.class));
        when(toReturn.getFindings()).thenReturn(findings);
        return toReturn;
    }
}
