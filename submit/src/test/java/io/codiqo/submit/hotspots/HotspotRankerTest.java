package io.codiqo.submit.hotspots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.api.code.DeclaredType;
import io.codiqo.api.code.TypeKind;
import io.codiqo.api.code.TypeReference;
import io.codiqo.api.code.TypeReferenceKind;
import io.codiqo.submit.hotspots.GitChurn.FileChurn;
import io.codiqo.submit.hotspots.HotspotRanker.RankedType;

class HotspotRankerTest {
    private static final Function<DeclaredType, String> PATHS = type -> type.getName() + ".java";

    @Test
    void callersOfAnInterfaceCountForItsImplementation() {
        List<DeclaredType> types = List.of(
                type("Api", TypeKind.INTERFACE),
                type("Impl", TypeKind.CLASS),
                type("CallerA", TypeKind.CLASS),
                type("CallerB", TypeKind.CLASS),
                type("CallerC", TypeKind.CLASS));
        List<TypeReference> references = List.of(
                reference("Impl", "Api", TypeReferenceKind.INHERIT),
                reference("CallerA", "Api", TypeReferenceKind.CALL),
                reference("CallerB", "Api", TypeReferenceKind.CALL),
                reference("CallerC", "Api", TypeReferenceKind.TYPE));

        Map<String, RankedType> ranked = byName(HotspotRanker.rank(types, references, PATHS, Map.of()));

        assertEquals(0, ranked.get("Impl").getDependents());
        assertEquals(3.0, ranked.get("Impl").getEffectiveDependents());
        assertEquals(4, ranked.get("Api").getDependents());
    }
    @Test
    void inheritingFromAConcreteClassSharesNothing() {
        List<DeclaredType> types = List.of(type("Entity", TypeKind.CLASS), type("Copier", TypeKind.CLASS), type("User", TypeKind.CLASS));
        List<TypeReference> references = List.of(
                reference("Copier", "Entity", TypeReferenceKind.INHERIT),
                reference("User", "Entity", TypeReferenceKind.TYPE));

        Map<String, RankedType> ranked = byName(HotspotRanker.rank(types, references, PATHS, Map.of()));

        assertEquals(0.0, ranked.get("Copier").getEffectiveDependents());
    }
    @Test
    void aClassThatDidNotChangeRecentlyIsNeverAHotspot() {
        List<DeclaredType> types = List.of(type("Busy", TypeKind.CLASS), type("Quiet", TypeKind.CLASS));
        Map<String, FileChurn> churn = Map.of(
                "Busy.java", new FileChurn(40, 12, 3),
                "Quiet.java", new FileChurn(90, 0, 0));

        Map<String, RankedType> ranked = byName(HotspotRanker.rank(types, List.of(), PATHS, churn));

        assertEquals(1, ranked.get("Busy").getHotspotRank());
        assertNull(ranked.get("Quiet").getHotspotRank());
    }
    @Test
    void rankingIsCappedAndDeterministic() {
        List<DeclaredType> types = Lists.newArrayList();
        List<TypeReference> references = Lists.newArrayList();
        Map<String, FileChurn> churn = Maps.newHashMap();
        for (int i = 0; i < HotspotRanker.IMPORTANT_LIMIT * 2; i++) {
            types.add(type("T" + i, TypeKind.CLASS));
            churn.put("T" + i + ".java", new FileChurn(i, i % 7, i % 3));
            if (i > 0) {
                references.add(reference("T" + i, "T" + (i / 2), TypeReferenceKind.CALL));
            }
        }

        List<RankedType> first = HotspotRanker.rank(types, references, PATHS, churn);
        List<RankedType> second = HotspotRanker.rank(types, references, PATHS, churn);

        assertEquals(first, second);
        assertEquals(HotspotRanker.IMPORTANT_LIMIT, first.stream().filter(ranked -> ranked.getImportanceRank() != null).count());
        assertTrue(first.stream().filter(ranked -> ranked.getHotspotRank() != null).count() <= HotspotRanker.HOTSPOT_LIMIT);
        assertEquals(1, first.get(0).bestRank());
    }
    @Test
    void tiedScoresDoNotRankByName() {
        List<DeclaredType> types = Lists.newArrayList();
        Map<String, FileChurn> churn = Maps.newHashMap();
        for (int i = 0; i < 200; i++) {
            types.add(type("T" + i, TypeKind.CLASS));
        }
        types.add(new DeclaredType("A", new File("A.java"), TypeKind.CLASS, 500, false));
        types.add(new DeclaredType("Z", new File("Z.java"), TypeKind.CLASS, 1000, false));
        churn.put("A.java", new FileChurn(50, 0, 0));
        churn.put("Z.java", new FileChurn(100, 0, 0));

        Map<String, RankedType> ranked = byName(HotspotRanker.rank(types, List.of(), PATHS, churn));

        assertEquals(1, ranked.get("Z").getImportanceRank());
        assertEquals(2, ranked.get("A").getImportanceRank());
    }
    @Test
    void tiedClassesShareARankAndATieAcrossTheLimitIsLeftOutWhole() {
        List<DeclaredType> types = Lists.newArrayList();
        types.add(new DeclaredType("Top1", new File("Top1.java"), TypeKind.CLASS, 10_000, false));
        types.add(new DeclaredType("Top2", new File("Top2.java"), TypeKind.CLASS, 10_000, false));
        for (int i = 0; i < HotspotRanker.IMPORTANT_LIMIT - 10; i++) {
            types.add(new DeclaredType("Mid" + i, new File("Mid" + i + ".java"), TypeKind.CLASS, 1000 + i, false));
        }
        for (int i = 0; i < 20; i++) {
            types.add(new DeclaredType("Tail" + i, new File("Tail" + i + ".java"), TypeKind.CLASS, 1, false));
        }

        Map<String, RankedType> ranked = byName(HotspotRanker.rank(types, List.of(), PATHS, Map.of()));

        assertEquals(1, ranked.get("Top1").getImportanceRank());
        assertEquals(1, ranked.get("Top2").getImportanceRank());
        assertEquals(3, ranked.get("Mid" + (HotspotRanker.IMPORTANT_LIMIT - 11)).getImportanceRank());
        assertTrue(types.stream().map(DeclaredType::getName).filter(name -> name.startsWith("Tail")).noneMatch(ranked::containsKey));
    }

    private static Map<String, RankedType> byName(List<RankedType> ranked) {
        return ranked.stream().collect(Collectors.toMap(type -> type.getType().getName(), Function.identity()));
    }
    private static DeclaredType type(String name, TypeKind kind) {
        return new DeclaredType(name, new File(name + ".java"), kind, 10, false);
    }
    private static TypeReference reference(String from, String to, TypeReferenceKind kind) {
        return new TypeReference(from, to, kind, 1);
    }
}
