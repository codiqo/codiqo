package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

class SettingsTest {
    /**
     * Every setting takes a value of its type under its name and reaches its own field: a field no parser reads, a
     * duration without its unit or two settings of one name would fail here rather than on the run that first sets it.
     */
    @Test
    void everySettingIsBoundOntoItsFieldByItsName() throws Exception {
        List<Field> fields = FieldUtils.getFieldsListWithAnnotation(RunArgs.class, Setting.class);

        for (Field field : fields) {
            Setting setting = field.getAnnotation(Setting.class);
            String name = Settings.nameOf(field);
            Pair<String, Object> sample = sample(field, setting);
            RunArgs args = new RunArgs();

            Settings.bind(args, Map.of(name, sample.getLeft()));

            assertEquals(sample.getRight(), FieldUtils.readField(field, args, true), name);
        }
        assertEquals(fields.size(), Settings.names().size());
    }
    /** the names CI configurations and the GitHub Action already pass, which no rename may break */
    @Test
    void theNamesUsersAlreadyPassKeepTheirSpelling() {
        assertTrue(Settings.names().containsAll(List.of(
                "codiqo.hotspots",
                "codiqo.review",
                "codiqo.review.timeoutMinutes",
                "codiqo.review.relayTimeoutSeconds",
                "codiqo.include.untracked",
                "codiqo.buildTimeoutMinutes",
                "codiqo.llm.conventionFilesMaxChars",
                "codiqo.excludeAuthorEmails")));
    }
    /** field by field: Lombok's equals leaves the transient fields out, the review's settings among them */
    @Test
    void aSettingLeftOutKeepsItsDefault() throws Exception {
        RunArgs args = new RunArgs();

        Settings.bind(args, Map.of("codiqo.review.timeoutMinutes", "7"));

        for (Field field : FieldUtils.getFieldsListWithAnnotation(RunArgs.class, Setting.class)) {
            Object expected = "reviewTimeout".equals(field.getName()) ? Duration.ofMinutes(7) : FieldUtils.readField(field, new RunArgs(), true);
            assertEquals(expected, FieldUtils.readField(field, args, true), field.getName());
        }
    }
    @Test
    void aDurationIsAWholeNumberOfItsUnit() {
        RunArgs args = new RunArgs();

        Settings.bind(args, Map.of("codiqo.review.relayTimeoutSeconds", "90", "codiqo.buildTimeoutMinutes", "50"));

        assertEquals(Duration.ofSeconds(90), args.getReviewRelayRequestTimeout());
        assertEquals(Duration.ofMinutes(50), args.getBuildTimeout());
    }
    @Test
    void aListIsCommaSeparatedWithoutBlanks() {
        RunArgs args = new RunArgs();

        Settings.bind(args, Map.of("codiqo.pmdRules", " first.xml , ,second.xml"));

        assertEquals(List.of("first.xml", "second.xml"), args.getPmdRules());
    }
    /** PMD given no rule at all would report nothing and earn the clean bonus */
    @Test
    void aBlankListKeepsItsDefault() {
        RunArgs args = new RunArgs();

        Settings.bind(args, Map.of("codiqo.pmdRules", " , "));

        assertEquals(new RunArgs().getPmdRules(), args.getPmdRules());
    }
    /** an empty value is a value: an empty exclusion list is how a project stops excluding bots */
    @Test
    void anEmptyTextIsBoundRatherThanSkipped() {
        RunArgs args = new RunArgs();

        Settings.bind(args, Map.of("codiqo.excludeAuthorEmails", StringUtils.EMPTY));

        assertEquals(StringUtils.EMPTY, args.getExcludeAuthorEmails());
    }
    /** a CI variable that was not set reaches the run as blank text, which names no path, number or switch */
    @Test
    void blankTextOfASettingThatIsNotTextKeepsItsDefault() {
        RunArgs args = new RunArgs();

        Settings.bind(args, Map.of(
                "codiqo.outputDirectory", StringUtils.SPACE,
                "codiqo.testTimeoutMinutes", StringUtils.EMPTY,
                "codiqo.review", StringUtils.EMPTY));

        assertNull(args.getOutputDirectory());
        assertEquals(new RunArgs().getTestTimeout(), args.getTestTimeout());
        assertTrue(args.isReviewEnabled());
    }
    /** a properties file keeps the space after a value, which would make a number unreadable */
    @Test
    void aValueIsReadWithoutTheSpaceAroundIt() {
        RunArgs args = new RunArgs();

        Settings.bind(args, Map.of("codiqo.testFailureHoldMinTests", "20 ", "codiqo.review.baseUrl", " http://127.0.0.1:11434/v1 "));

        assertEquals(20, args.getTestFailureHoldMinTests());
        assertEquals("http://127.0.0.1:11434/v1", args.getReviewBaseUrl());
    }
    /** read as Boolean.parseBoolean reads it, the misspelt switch would turn the review off */
    @Test
    void aMisspeltSwitchIsRefusedRatherThanReadAsFalse() {
        RunArgs args = new RunArgs();

        IllegalArgumentException err = assertThrows(IllegalArgumentException.class, () -> Settings.bind(args, Map.of("codiqo.review", "ture")));

        assertTrue(err.getMessage().contains("codiqo.review=ture"), err.getMessage());
        assertTrue(args.isReviewEnabled());
    }
    @Test
    void aValueItsSettingCannotReadIsRefusedNamingTheSetting() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> Settings.bind(new RunArgs(), Map.of("codiqo.buildTimeoutMinutes", "PT45M")));

        assertEquals("codiqo.buildTimeoutMinutes=PT45M cannot be read as a whole number of minutes", err.getMessage());
    }
    @Test
    void aDurationTooLongToHoldIsRefusedNamingTheSetting() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> Settings.bind(new RunArgs(), Map.of("codiqo.buildTimeoutMinutes", String.valueOf(Long.MAX_VALUE))));

        assertTrue(err.getMessage().startsWith("codiqo.buildTimeoutMinutes="), err.getMessage());
    }
    /** an adapter that passes a name no setting has has the name wrong, and the user's value would be lost without a word */
    @Test
    void aNameNoSettingHasIsRefused() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> Settings.bind(new RunArgs(), Map.of("codiqo.buildTimeout", "45")));

        assertEquals("no setting is named codiqo.buildTimeout", err.getMessage());
    }
    /**
     * A setting's text and the value it binds to, a value other than the field's default so a bind that missed its field
     * would show.
     */
    private static Pair<String, Object> sample(Field field, Setting setting) throws IllegalAccessException {
        Class<?> type = ClassUtils.primitiveToWrapper(field.getType());
        if (Boolean.class == type) {
            boolean flipped = BooleanUtils.isNotTrue((Boolean) FieldUtils.readField(field, new RunArgs(), true));
            return Pair.of(String.valueOf(flipped), flipped);
        }
        if (Integer.class == type) {
            return Pair.of("7", 7);
        }
        if (Duration.class == type) {
            return Pair.of("7", Duration.of(7, setting.unit()));
        }
        if (Double.class == type) {
            return Pair.of("0.125", 0.125);
        }
        if (List.class == type) {
            return Pair.of("first, second", List.of("first", "second"));
        }
        if (File.class == type) {
            return Pair.of("/sample/" + field.getName(), new File("/sample/" + field.getName()));
        }
        return Pair.of("/sample/" + field.getName(), "/sample/" + field.getName());
    }
}
