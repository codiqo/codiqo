package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.plugin.descriptor.PluginDescriptorBuilder;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.api.Setting;
import io.codiqo.api.Settings;

/**
 * Every goal's parameters, read from the generated plugin descriptor (@Parameter is not kept at run time). A setting's
 * parameter declares no default, since RunArgs holds the default and a Maven default would always overwrite it, and
 * each one reaches its RunArgs field through {@link MojoSettings}.
 */
class MojoSettingsTest {
    /**
     * The {@code codiqo.*} parameters that are no RunArgs setting: connections, hidden-source mode, the goals' own inputs
     * and switches, and the fork's Maven options, which the goals read themselves (Gradle reads some of them under the
     * same name). Anything else a goal reads under {@code codiqo.} is a setting, declared on RunArgs with its default.
     */
    private static final Set<String> MAVEN_ONLY = Set.of(
            "codiqo.apiUrl", "codiqo.apiKey", "codiqo.authUrl", "codiqo.resourceUrl",
            "codiqo.forkMavenOpts", "codiqo.preferYaml", "codiqo.jdtSourceExclusions",
            "codiqo.sourceHidden", "codiqo.sourceHashKey",
            "codiqo.commitId", "codiqo.haltReason", "codiqo.haltLogFile", "codiqo.inputFile",
            "codiqo.submit", "codiqo.scoringTimeoutMinutes",
            "codiqo.indexRef", "codiqo.commitWindow", "codiqo.indexBatchSize", "codiqo.branch",
            "codiqo.missingAnalysesOutputFile", "codiqo.missingAnalysesLimit", "codiqo.indexMetadataFile",
            "codiqo.review.commit", "codiqo.review.directory", "codiqo.review.output", "codiqo.review.findings", "codiqo.review.submission",
            "codiqo.recipes", "codiqo.commitAuthor", "codiqo.commitEmail", "codiqo.commitMessage", "codiqo.dryRun",
            "codiqo.force");

    /** and every setting has a parameter, or a Maven user could not set it at all */
    @Test
    void everySettingParameterReachesItsFieldAsItsNameWouldBindIt() throws Exception {
        Set<String> checked = Sets.newTreeSet();
        for (MojoDescriptor goal : goals()) {
            Object mojo = Class.forName(goal.getImplementation()).getDeclaredConstructor().newInstance();
            for (Parameter parameter : goal.getParameters()) {
                Optional<String> setting = settingOf(parameter);
                if (setting.isPresent()) {
                    Field field = fieldOf(setting.get());
                    String text = sampleText(field);
                    FieldUtils.writeField(mojo, parameter.getName(), typed(FieldUtils.getField(mojo.getClass(), parameter.getName(), true), text), true);

                    RunArgs throughMaven = new RunArgs();
                    Settings.bind(throughMaven, MojoSettings.of(mojo, goal));
                    RunArgs byName = new RunArgs();
                    Settings.bind(byName, Map.of(setting.get(), text));

                    /** field by field: Lombok's equals leaves the transient fields out, the review's settings among them */
                    String where = goal.getGoal() + " " + parameter.getName();
                    Object expected = FieldUtils.readField(field, byName, true);
                    assertNotEquals(FieldUtils.readField(field, new RunArgs(), true), expected, where + ": the sample is the default and proves nothing");
                    assertEquals(expected, FieldUtils.readField(field, throughMaven, true), where);
                    FieldUtils.writeField(mojo, parameter.getName(), null, true);
                    checked.add(setting.get());
                }
            }
        }
        assertEquals(Settings.names(), checked);
    }
    /** a Maven default always reached RunArgs, so it had to repeat RunArgs' own, and some 38 did as literals */
    @Test
    void noSettingParameterDeclaresADefault() throws Exception {
        for (MojoDescriptor goal : goals()) {
            for (Parameter parameter : goal.getParameters()) {
                if (settingOf(parameter).isPresent()) {
                    assertNull(parameter.getDefaultValue(), goal.getGoal() + " " + parameter.getName());
                }
            }
        }
    }
    @Test
    void everyCodiqoParameterIsASettingOrOnlyMavensOwn() throws Exception {
        for (MojoDescriptor goal : goals()) {
            for (Parameter parameter : goal.getParameters()) {
                String property = StringUtils.substringBetween(parameter.getExpression(), "${", "}");
                if (Strings.CS.startsWith(property, "codiqo.")) {
                    assertTrue(BooleanUtils.or(new boolean[] { Settings.names().contains(property), MAVEN_ONLY.contains(property) }),
                            goal.getGoal() + " reads " + property + ", which is neither a RunArgs setting nor listed as Maven's own");
                }
            }
        }
    }
    /**
     * the fork timeout has to fire before the test-phase timeout is meaningless and before whatever outer deadline the
     * harness imposes, or the fork is killed from outside and buildProject never reaches the CommandLineTimeOutException
     * branch that excuses the commit as a build failure and lets the time-machine ladder retry it.
     */
    @Test
    void testTimeoutStaysBelowBuildTimeout() {
        RunArgs reference = new RunArgs();

        assertTrue(reference.getTestTimeout().compareTo(reference.getBuildTimeout()) < 0,
                "testTimeout " + reference.getTestTimeout() + " must be shorter than buildTimeout " + reference.getBuildTimeout());
    }
    /**
     * The interactive wait for a submitted analysis is its own parameter. It used to borrow buildTimeout, which is
     * sized for a forked CI build, so a wedged scorer blocked a developer's terminal for three quarters of an hour.
     */
    @Test
    void interactiveScoringWaitIsBoundedWellBelowTheForkedBuildTimeout() throws Exception {
        RunArgs reference = new RunArgs();

        Parameter scoringTimeout = goals().stream()
                .flatMap(goal -> goal.getParameters().stream())
                .filter(parameter -> "scoringTimeoutMinutes".equals(parameter.getName()))
                .findFirst()
                .orElseThrow();
        long scoringTimeoutMinutes = Long.parseLong(scoringTimeout.getDefaultValue());

        assertTrue(scoringTimeoutMinutes < reference.getBuildTimeout().toMinutes(),
                "scoringTimeoutMinutes " + scoringTimeoutMinutes + " must be shorter than buildTimeout "
                        + reference.getBuildTimeout().toMinutes() + " minutes — it bounds an interactive wait, not a build");
    }
    private static List<MojoDescriptor> goals() throws Exception {
        InputStream in = MojoSettingsTest.class.getResourceAsStream("/META-INF/maven/plugin.xml");
        assertNotNull(in, "plugin descriptor must be generated before tests run");
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new PluginDescriptorBuilder().build(reader).getMojos();
        }
    }
    private static Optional<String> settingOf(Parameter parameter) {
        return Optional.ofNullable(StringUtils.substringBetween(parameter.getExpression(), "${", "}")).filter(Settings.names()::contains);
    }
    private static Field fieldOf(String name) {
        return FieldUtils.getFieldsListWithAnnotation(RunArgs.class, Setting.class).stream()
                .filter(field -> name.equals(Settings.nameOf(field)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no setting is named " + name));
    }
    /** text other than the setting's default: the opposite of a switch's default, otherwise a value no default has */
    private static String sampleText(Field field) throws IllegalAccessException {
        Class<?> type = ClassUtils.primitiveToWrapper(field.getType());
        if (Boolean.class == type) {
            return String.valueOf(BooleanUtils.isNotTrue((Boolean) FieldUtils.readField(field, new RunArgs(), true)));
        }
        if (Double.class == type) {
            return "0.125";
        }
        if (BooleanUtils.or(new boolean[] { Integer.class == type, Duration.class == type })) {
            return "7";
        }
        return "/sample/" + field.getName();
    }
    /** the sample text as the value Maven would inject into a parameter field of this type */
    private static Object typed(Field field, String text) {
        Class<?> type = ClassUtils.primitiveToWrapper(field.getType());
        if (String.class == type) {
            return text;
        }
        if (Boolean.class == type) {
            return Boolean.valueOf(text);
        }
        if (Integer.class == type) {
            return Integer.valueOf(text);
        }
        if (Long.class == type) {
            return Long.valueOf(text);
        }
        if (Double.class == type) {
            return Double.valueOf(text);
        }
        if (File.class == type) {
            return new File(text);
        }
        throw new AssertionError(field + " is a " + type.getName() + ", which MojoSettings cannot hand to RunArgs as text");
    }
}
