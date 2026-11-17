package io.codiqo.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import io.codiqo.api.RunArgs;
import io.codiqo.api.Setting;
import io.codiqo.api.Settings;
import io.codiqo.gradle.model.AnalysisRequest;

/**
 * Every setting reaches the analysis worker's {@link RunArgs} from a {@code -P} property and from the extension, under
 * its one name, as binding that name would set it ({@link Settings}). The worker half runs through
 * {@link AnalysisEngine#runArgs}, which reads the map the daemon collected.
 */
class GradleSettingsTest {
    /** the extension's properties that are the plugin's own: the login, hidden-source mode, the fork, the index, the tests */
    private static final Set<String> GRADLE_ONLY = Set.of(
            "apiUrl", "apiKey", "authUrl", "resourceUrl", "sourceHidden", "sourceHashKey", "analysisMaxHeap",
            "commitWindow", "indexOutputFile", "indexMetadataFile", "ignoreTestFailures");
    /** samples for the settings whose text RunArgs.validate() reads against a list of its own */
    private static final Map<String, String> CHECKED_SAMPLES = Map.of("pmdMinPriority", "LOW");

    @Test
    void everySettingGivenAsAPropertyReachesItsFieldAsItsNameWouldBindIt() throws Exception {
        for (Field field : FieldUtils.getFieldsListWithAnnotation(RunArgs.class, Setting.class)) {
            String name = Settings.nameOf(field);
            String sample = sampleText(field);

            RunArgs bound = worker(GradleSettings.of(Map.of(name, sample)::get, new CodiqoExtension()));

            assertEquals(read(field, boundAlone(name, sample)), read(field, bound), name);
            assertNotEquals(read(field, new RunArgs()), read(field, bound), name + ": the sample is the default");
        }
    }
    @Test
    void everySettingPropertyOfTheExtensionReachesItsField() throws Exception {
        for (Field property : settingProperties()) {
            String name = settingOf(property).orElseThrow();
            Field field = fieldOf(name);
            String sample = sampleText(field);
            CodiqoExtension ext = new CodiqoExtension();
            FieldUtils.writeField(property, ext, typed(property, sample), true);

            RunArgs bound = worker(GradleSettings.of(unset -> null, ext));

            assertEquals(read(field, boundAlone(name, sample)), read(field, bound), property.getName());
        }
    }
    /** a default on the extension would overwrite the RunArgs one whatever RunArgs says, so the two could drift apart */
    @Test
    void noSettingPropertyOfTheExtensionDeclaresADefault() throws Exception {
        CodiqoExtension ext = new CodiqoExtension();
        for (Field property : settingProperties()) {
            assertNull(FieldUtils.readField(property, ext, true), property.getName());
        }
    }
    @Test
    void everyExtensionPropertyIsASettingOrThePluginsOwn() {
        for (Field property : FieldUtils.getAllFieldsList(CodiqoExtension.class)) {
            if (BooleanUtils.negate(Modifier.isStatic(property.getModifiers()))) {
                assertTrue(BooleanUtils.xor(new boolean[] { GRADLE_ONLY.contains(property.getName()), settingOf(property).isPresent() }),
                        property.getName() + " is neither a setting's property nor listed as the plugin's own");
            }
        }
    }
    @Test
    void aPropertyWinsOverTheExtension() {
        CodiqoExtension ext = new CodiqoExtension();
        ext.setTestTimeoutMinutes(10L);

        RunArgs bound = GradleSettings.runArgs(GradleSettings.of(Map.of("codiqo.testTimeoutMinutes", "20")::get, ext));

        assertEquals(Duration.ofMinutes(20), bound.getTestTimeout());
    }
    /** the fork's JDK is read from the map as it stands, so a path given with a space around it must not keep it */
    @Test
    void aValueIsCollectedWithoutTheSpaceAroundIt() {
        Map<String, String> settings = GradleSettings.of(Map.of(GradleSettings.JAVA_HOME, " /jdk ")::get, new CodiqoExtension());

        assertEquals("/jdk", settings.get(GradleSettings.JAVA_HOME));
    }
    @Test
    void aValueItsSettingCannotReadFailsTheBuildNamingTheSetting() {
        GradleException err = assertThrows(GradleException.class, () -> GradleSettings.runArgs(Map.of("codiqo.review", "ture")));

        assertTrue(Strings.CS.contains(err.getMessage(), "codiqo.review=ture"), err.getMessage());
    }
    /** read as Boolean.parseBoolean reads it, -Pcodiqo.sourceHidden=ture would send the run's source code */
    @Test
    void aSwitchOfThePluginsOwnIsReadAsStrictlyAsASetting() {
        GradleException err = assertThrows(GradleException.class, () -> GradleSettings.flag(Map.of("codiqo.sourceHidden", "ture")::get, "codiqo.sourceHidden", false));

        assertTrue(Strings.CS.contains(err.getMessage(), "codiqo.sourceHidden=ture"), err.getMessage());
        assertTrue(GradleSettings.flag(Map.of("codiqo.sourceHidden", "yes ")::get, "codiqo.sourceHidden", false));
        assertTrue(GradleSettings.flag(unset -> null, "codiqo.sourceHidden", true), "an unset switch keeps the extension's value");
        assertTrue(GradleSettings.flag(Map.of("codiqo.sourceHidden", StringUtils.EMPTY)::get, "codiqo.sourceHidden", true), "and so does a blank one");
    }
    /** an unset CI variable passes a blank property, which must neither hide the block's value nor clear the default */
    @Test
    void aBlankPropertyLeavesTheExtensionsValueOrTheDefault() {
        CodiqoExtension ext = new CodiqoExtension();
        ext.setReviewBaseUrl("http://127.0.0.1:11434/v1");

        Map<String, String> settings = GradleSettings.of(Map.of("codiqo.review.baseUrl", StringUtils.EMPTY, "codiqo.review.coordinatorModel", StringUtils.SPACE)::get, ext);

        assertEquals("http://127.0.0.1:11434/v1", settings.get("codiqo.review.baseUrl"));
        assertFalse(settings.containsKey("codiqo.review.coordinatorModel"));
    }
    /** written in the block on purpose, an empty filter is a value: it excludes nobody */
    @Test
    void anEmptyTextInTheExtensionIsAValue() {
        CodiqoExtension ext = new CodiqoExtension();
        ext.setExcludeAuthorEmails(StringUtils.EMPTY);

        assertEquals(StringUtils.EMPTY, GradleSettings.runArgs(GradleSettings.of(unset -> null, ext)).getExcludeAuthorEmails());
    }
    @Test
    void theNamesThePluginReadsAreSettings() {
        assertTrue(Settings.names().containsAll(List.of(GradleSettings.JAVA_HOME, GradleSettings.OUTPUT_DIRECTORY)));
        assertTrue(Settings.names().containsAll(CodiqoGradlePlugin.TEST_TIMEOUTS));
    }
    @Test
    void anExtensionPropertyIsTheSettingsNameInCamelCase() {
        assertEquals("review", GradleSettings.extensionProperty("codiqo.review"));
        assertEquals("reviewTimeoutMinutes", GradleSettings.extensionProperty("codiqo.review.timeoutMinutes"));
        assertEquals("testTimeoutMinutes", GradleSettings.extensionProperty("codiqo.testTimeoutMinutes"));
    }
    private static RunArgs worker(Map<String, String> settings) {
        AnalysisRequest request = new AnalysisRequest();
        request.setSettings(settings);
        return AnalysisEngine.runArgs(request);
    }
    private static RunArgs boundAlone(String name, String text) {
        RunArgs toReturn = new RunArgs();
        Settings.bind(toReturn, Map.of(name, text));
        toReturn.validate();
        return toReturn;
    }
    private static List<Field> settingProperties() {
        return FieldUtils.getAllFieldsList(CodiqoExtension.class).stream().filter(property -> settingOf(property).isPresent()).toList();
    }
    private static Optional<String> settingOf(Field property) {
        return Settings.names().stream().filter(name -> property.getName().equals(GradleSettings.extensionProperty(name))).findFirst();
    }
    private static Field fieldOf(String name) {
        return FieldUtils.getFieldsListWithAnnotation(RunArgs.class, Setting.class).stream()
                .filter(field -> name.equals(Settings.nameOf(field)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no setting is named " + name));
    }
    private static Object read(Field field, RunArgs args) throws IllegalAccessException {
        return FieldUtils.readField(field, args, true);
    }
    /** text other than the setting's default: the opposite of a switch's default, otherwise a value no default has */
    private static String sampleText(Field field) throws IllegalAccessException {
        if (CHECKED_SAMPLES.containsKey(field.getName())) {
            return CHECKED_SAMPLES.get(field.getName());
        }
        Class<?> type = ClassUtils.primitiveToWrapper(field.getType());
        if (Boolean.class == type) {
            return String.valueOf(BooleanUtils.isNotTrue((Boolean) read(field, new RunArgs())));
        }
        if (Double.class == type) {
            return "0.125";
        }
        if (BooleanUtils.or(new boolean[] { Integer.class == type, Duration.class == type })) {
            return "7";
        }
        return "/sample/" + field.getName();
    }
    /** the sample text as the value a build script would give the extension property */
    private static Object typed(Field property, String text) {
        if (String.class == property.getType()) {
            return text;
        }
        if (Boolean.class == property.getType()) {
            return Boolean.valueOf(text);
        }
        if (Long.class == property.getType()) {
            return Long.valueOf(text);
        }
        if (File.class == property.getType()) {
            return new File(text);
        }
        throw new AssertionError(property + " is a " + property.getType().getName() + ", which GradleSettings cannot hand to RunArgs as text");
    }
}
