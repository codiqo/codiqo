package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.codiqo.api.review.UnitName;

class JavaReviewLanguageTest {
    private static final String PATH = "src/main/java/com/example/Totals.java";

    private final JavaReviewLanguage language = new JavaReviewLanguage();

    /** what a reviewer writes and what the index names the same unit compare equal, generics, packages and all */
    @Test
    void aReviewersSignatureNamesTheIndexedUnit() {
        assertMember("handle(java.util.List<String>, Optional<Long>)", "handle(List, Optional)");
        assertMember("put(Map<String, List<Integer>>, Optional<Map<K, V>>)", "put(Map, Optional)");
        assertMember("<init>(Config,Clock)", "Totals(Config, Clock)");
        assertMember("join(@Nullable java.lang.String...)", "join(String[])");
        assertMember("pick(Unknown)", "pick(*Unknown)");
        assertMember("Totals.<init>(Config, Clock)", "Totals(Config, Clock)");
        assertMember("Totals::run()", "run()");
        assertMember("@Override public void Totals.run()", "run()");
    }
    /** the classes a reviewer writes, and the binary class name of the index's descriptor, tell nested units apart */
    @Test
    void theEnclosingClassesAreTheContainer() {
        UnitName nested = language.unit("report()", "com/example/Totals$Task.report()V", PATH);
        UnitName outer = language.unit("report()", "com/example/Totals.report()V", PATH);

        assertEquals(List.of("Totals", "Task"), nested.getContainer());
        assertEquals(List.of("Totals"), outer.getContainer());
        assertEquals(nested.getContainer(), language.labelled("Totals.Task.report()", PATH).getContainer());
        assertEquals(nested.getContainer(), language.labelled("Totals$Task.report()", PATH).getContainer());
        assertEquals(outer.getContainer(), language.labelled("void Totals.report()", PATH).getContainer());
        assertEquals(List.of("Totals", "Task"), language.labelled("@Override Totals.Task.report()", PATH).getContainer());
        assertEquals(List.of(), language.labelled("report()", PATH).getContainer());
        assertEquals(nested.getMember(), language.labelled("Totals.Task.report()", PATH).getMember());
    }
    /** a constructor is named after its class, which is then no container of it, on both sides */
    @Test
    void aConstructorsClassIsItsName() {
        UnitName indexed = language.unit("Builder(String)", "com/example/Messages$Request$Builder.<init>(Ljava/lang/String;)V", PATH);

        assertEquals(List.of("Messages", "Request"), indexed.getContainer());
        assertEquals(indexed, language.labelled("Messages.Request.Builder(String)", PATH));
        assertEquals(indexed, language.labelled("Messages.Request.Builder.<init>(String)", PATH));
    }
    /** an anonymous class has only its number, a local class prefixes one, and neither is what a reviewer writes */
    @Test
    void anAnonymousClassMatchesWhatItCreates() {
        UnitName anonymous = language.unit("run()", "com/example/Totals$1.run()V", PATH);
        UnitName outer = language.unit("run()", "com/example/Totals.run()V", PATH);
        UnitName local = language.unit("report()", "com/example/Totals$1Local.report()V", PATH);

        assertEquals(List.of("Totals", UnitName.ANONYMOUS), anonymous.getContainer());
        assertEquals(List.of("Totals", "Local"), local.getContainer());
        assertEquals(UnitName.Match.LOOSE, anonymous.match(language.labelled("Totals.Runnable.run()", PATH)));
        assertEquals(UnitName.Match.NONE, outer.match(language.labelled("Totals.Runnable.run()", PATH)));
        assertEquals(UnitName.Match.EXACT, outer.match(language.labelled("Totals.run()", PATH)));
        assertEquals(UnitName.Match.EXACT, local.match(language.labelled("Local.report()", PATH)));
        assertEquals(UnitName.Match.EXACT, outer.match(language.labelled("com.example.Totals.run()", PATH)), "a written package is ignored");
    }
    /** two nested classes of one simple name are told apart by the classes that enclose them */
    @Test
    void nestedClassesOfOneNameAreToldApart() {
        UnitName request = language.unit("build()", "com/example/Messages$Request$Builder.build()Ljava/lang/Object;", PATH);
        UnitName response = language.unit("build()", "com/example/Messages$Response$Builder.build()Ljava/lang/Object;", PATH);
        UnitName label = language.labelled("Messages.Request.Builder.build()", PATH);

        assertEquals(UnitName.Match.EXACT, request.match(label));
        assertEquals(UnitName.Match.NONE, response.match(label));
    }
    /** the index prints a nested parameter type as Outer#Inner, a reviewer as Inner */
    @Test
    void aNestedParameterTypeIsNamedSimply() {
        assertEquals(language.unit("replace(Totals#Task)", "com/example/Totals.replace(Lcom/example/Totals$Task;)V", PATH).getMember(),
                language.labelled("Totals.replace(Task)", PATH).getMember());
        assertEquals(language.unit("replace(Totals.Task)", "com/example/Totals.replace(Lcom/example/Totals$Task;)V", PATH).getMember(),
                language.labelled("Totals.replace(Totals.Task)", PATH).getMember(), "a static nested type");
    }
    /**
     * Every example the naming rule shows reviewers names the unit it describes, so an edit to the rule that teaches a
     * form the matching cannot read fails here rather than silently dropping every label written that way.
     */
    @Test
    void everyExampleOfTheNamingRuleNamesItsUnit() {
        Map<String, UnitName> examples = Map.of(
                "Totals.onSuccess(String)", language.unit("onSuccess(String)", "com/example/Totals.onSuccess(Ljava/lang/String;)V", PATH),
                "Totals.Batch.onSuccess(String)", language.unit("onSuccess(String)", "com/example/Totals$Batch.onSuccess(Ljava/lang/String;)V", PATH),
                "Totals(Config, Clock)", language.unit("Totals(Config, Clock)", "com/example/Totals.<init>(Lcom/example/Config;Ljava/time/Clock;)V", PATH),
                "Totals.Runnable.run()", language.unit("run()", "com/example/Totals$1.run()V", PATH),
                "Op.ADD.apply(int, int)", language.unit("apply(int, int)", "com/example/Op$1.apply(II)I", "src/main/java/com/example/Op.java"));

        assertEquals(examples.keySet(), Pattern.compile("\"([^\"]+)\"").matcher(language.namingRule()).results().map(match -> match.group(1)).collect(Collectors.toSet()));
        examples.forEach((written, unit) -> {
            UnitName labelled = language.labelled(written, PATH);
            assertEquals(unit.getMember(), labelled.getMember(), written);
            assertTrue(EnumSet.of(UnitName.Match.EXACT, UnitName.Match.LOOSE).contains(unit.match(labelled)), written);
        });
    }
    @Test
    void itCoversJavaFilesAndTriagesTheJvmTools() {
        assertTrue(language.extensions().contains("java"));
        assertEquals(Set.of("pmd", "spotbugs"), language.triagedTools());
    }
    private void assertMember(String written, String indexed) {
        assertEquals(language.unit(indexed, "com/example/Totals.x()V", PATH).getMember(), language.labelled(written, PATH).getMember(), written);
    }
}
