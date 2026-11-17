package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.ScoringConfigModel;

/**
 * Which {@link RunArgs} fields the submission's scoring config carries ({@link ScoringConfigs}): every non-transient field
 * is either sent, as the run had it, or listed here as left out on purpose, and no transient field is sent. A new field
 * therefore needs a decision, and a field made transient never reaches the submission.
 */
class ScoringConfigsTest {
    /** settings of the client's run that the server never reads: which commits, which checks, what to tolerate */
    private static final Set<String> CLIENT_ONLY = Set.of(
            "jdtlsUseSnapshot", "failOnUninstrumentedModule", "hotspotsEnabled", "hotspotsCommitId", "skipOnBuildFailure",
            "buildErrorCaptureLimit", "cpdIgnoreIdentifiers", "llmConventionFiles", "autoDiscoveryAgentInstructions",
            "llmConventionFilesMaxChars", "testFailureHoldShare", "testFailureHoldMinTests", "excludeAuthorEmails",
            "excludeProjects", "excludePaths", "firstParentOnly", "timeMachineEnabled", "jdtIncludeDecompiledSources",
            "jdtLombokAgent");
    /**
     * Scoring parameters only the server's scoring reads, always from its compiled default: the scoring config has no
     * property for them, so neither a run nor an organization can set them, and a re-score takes the current default.
     */
    private static final Set<String> SERVER_DEFAULTS = Set.of(
            "configFileScoreMultiplier", "categoryMechanicalCoeff", "categoryRoutineCoeff", "categorySubstantiveCoeff",
            "categoryIntricateCoeff", "movedRunMaxGap", "qualityFactorWellCovered", "qualityFactorLowCoverage",
            "qualityFactorPoorCoverage", "qualityFactorTerribleCoverage", "qualityFactorUncovered", "qualityFactorUnmeasured",
            "qualityFactorBlockingBug", "qualityFactorWithBugsMax", "architectureBonusImpactDiscount");
    /**
     * Read on both sides, each from its own value: the client excludes a reverted original by the run's setting, and
     * the server keeps such an exclusion on a re-submission by its own (an organization's override, else its default),
     * which is also what it records in its scoring config columns.
     */
    private static final Set<String> EACH_SIDE_ITS_OWN = Set.of("excludeRevertedCommits");
    /** what the scoring config carries in place of the field: the language server version the run resolved */
    private static final String JDTLS_VERSION = "jdtlsVersion";

    @Test
    void everyFieldIsSentOrLeftOutOnPurpose() {
        Set<String> leftOut = Sets.union(Sets.union(CLIENT_ONLY, SERVER_DEFAULTS), EACH_SIDE_ITS_OWN);
        Set<String> sent = names(ScoringConfigModel.class, isTransient -> true);
        Set<String> travelling = names(RunArgs.class, isTransient -> BooleanUtils.negate(isTransient));

        assertEquals(Set.of(), Sets.difference(travelling, Sets.union(leftOut, sent)), "neither sent nor listed as left out");
        assertEquals(Set.of(), Sets.intersection(leftOut, sent), "listed as left out, but sent");
        assertEquals(Set.of(), Sets.difference(leftOut, travelling), "listed as left out, but no field that could travel");
        assertEquals(Set.of(), Sets.difference(sent, Sets.union(travelling, Set.of(JDTLS_VERSION))), "a property with no field to send");
    }
    /**
     * Each property, set alone to a value its default does not have, reaches its own property and no other: a mapping
     * that read another getter would leave this one at the default, or move another.
     */
    @Test
    void everyPropertyCarriesItsOwnFieldsValue() throws Exception {
        Set<String> properties = Sets.difference(names(ScoringConfigModel.class, isTransient -> true), Set.of(JDTLS_VERSION));
        ScoringConfigModel baseline = ScoringConfigs.map(withArchive(new RunArgs()));
        assertEquals(withArchive(new RunArgs()).resolvedJdtlsVersion().orElseThrow(), baseline.getJdtlsVersion());

        for (String property : properties) {
            Field field = FieldUtils.getField(RunArgs.class, property, true);
            RunArgs args = withArchive(new RunArgs());
            Object value = sample(field, FieldUtils.readField(field, args, true));
            FieldUtils.writeField(field, args, value, true);

            ScoringConfigModel sent = ScoringConfigs.map(args);

            assertEquals(wire(value), FieldUtils.readField(sent, property, true), property);
            for (String other : Sets.difference(properties, Set.of(property))) {
                assertEquals(FieldUtils.readField(baseline, other, true), FieldUtils.readField(sent, other, true), property + " moved " + other);
            }
        }
    }
    @Test
    void noTransientFieldIsSent() {
        Set<String> local = names(RunArgs.class, isTransient -> isTransient);

        assertTrue(local.containsAll(Set.of("llmApiKey", "llmModel", "llmBaseUrl")));
        assertEquals(Set.of(), Sets.intersection(local, names(ScoringConfigModel.class, isTransient -> true)));
    }
    /** transient keeps the server's LLM client out of the submission, not out of equality */
    @Test
    void runsAgainstTwoLlmClientsAreNotEqual() {
        Map<String, BiConsumer<RunArgs, String>> setters = Map.of(
                "llmApiKey", RunArgs::setLlmApiKey,
                "llmModel", RunArgs::setLlmModel,
                "llmBaseUrl", RunArgs::setLlmBaseUrl);
        for (Map.Entry<String, BiConsumer<RunArgs, String>> setter : setters.entrySet()) {
            RunArgs one = new RunArgs();
            RunArgs other = new RunArgs();
            setter.getValue().accept(one, "one");
            setter.getValue().accept(other, "other");

            assertNotEquals(one, other, setter.getKey());
        }
    }
    private static RunArgs withArchive(RunArgs args) {
        args.setJdtlsArchiveName(RunArgs.JDTLS_MIRRORED_ARCHIVE);
        return args;
    }
    /** the non-static fields of {@code type} whose transient modifier {@code accepted} takes */
    private static Set<String> names(Class<?> type, Predicate<Boolean> accepted) {
        Set<String> toReturn = Sets.newTreeSet();
        for (Field field : FieldUtils.getAllFieldsList(type)) {
            int modifiers = field.getModifiers();
            if (BooleanUtils.and(new boolean[] { BooleanUtils.negate(Modifier.isStatic(modifiers)), accepted.test(Modifier.isTransient(modifiers)) })) {
                toReturn.add(field.getName());
            }
        }
        return toReturn;
    }
    /** a value the field's default is not, so a property that kept the default is told apart from one that was sent */
    private static Object sample(Field field, Object current) {
        Class<?> type = ClassUtils.primitiveToWrapper(field.getType());
        if (Boolean.class == type) {
            return BooleanUtils.isNotTrue((Boolean) current);
        }
        if (Integer.class == type) {
            return current instanceof Integer number ? number + 1001 : 1001;
        }
        if (Short.class == type) {
            return (short) (current instanceof Short number ? number + 11 : 11);
        }
        if (Double.class == type) {
            return current instanceof Double number ? number + 0.5 : 0.125;
        }
        if (Duration.class == type) {
            return current instanceof Duration duration ? duration.plusMinutes(7) : Duration.ofMinutes(7);
        }
        if (String.class == type) {
            return "sample-" + field.getName();
        }
        if (List.class == type) {
            return List.of("sample-" + field.getName());
        }
        if (File.class == type) {
            return new File("/sample/" + field.getName());
        }
        throw new AssertionError(field.getName() + " is a " + type.getName() + ", which this test has no sample for");
    }
    /** a value as the scoring config carries it: a duration as ISO-8601 text, a short widened */
    private static Object wire(Object value) {
        if (value instanceof Duration duration) {
            return duration.toString();
        }
        if (value instanceof Short number) {
            return number.intValue();
        }
        return value;
    }
}
