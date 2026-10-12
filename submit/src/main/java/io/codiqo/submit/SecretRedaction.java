package io.codiqo.submit;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scrubs the values of the environment's secrets out of every string of a JSON document that leaves the machine, the
 * review's trace: its tool outputs are whatever the agents' commands printed, and those commands ran with the build's
 * environment. The same rules as the GitHub Action's log redaction, which runs only after the submission: the
 * variables it names and every variable whose name says it holds a secret, raw and Base64-encoded.
 */
@UtilityClass
public class SecretRedaction {
    public static final String PLACEHOLDER = "***";
    /** the variable the GitHub Action lists the caller's secret names in, newline or comma separated */
    public static final String REDACT_NAMES_VARIABLE = "CODIQO_REDACT_ENV_NAMES";

    /** shorter values are too likely to be ordinary words */
    private static final int MIN_LENGTH = 8;
    private static final Set<String> ALWAYS = Set.of("CODIQO_API_KEY", "CODIQO_SETTINGS_XML");
    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(TOKEN|SECRET|PASSWORD|PASSWD|API_?KEY|PRIVATE_?KEY|CREDENTIAL).*");

    /** redacts in place: every string value of the tree, whatever its depth */
    public void redact(JsonNode document, Map<String, String> environment) {
        List<String> secrets = secrets(environment);
        if (CollectionUtils.isNotEmpty(secrets)) {
            redact(document, secrets);
        }
    }
    private void redact(JsonNode node, List<String> secrets) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getValue().isString()) {
                    object.put(field.getKey(), scrub(field.getValue().stringValue(), secrets));
                } else {
                    redact(field.getValue(), secrets);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isString()) {
                    array.set(i, array.stringNode(scrub(array.get(i).stringValue(), secrets)));
                } else {
                    redact(array.get(i), secrets);
                }
            }
        }
    }
    private String scrub(String value, List<String> secrets) {
        String toReturn = value;
        for (String secret : secrets) {
            toReturn = StringUtils.replace(toReturn, secret, PLACEHOLDER);
        }
        return toReturn;
    }
    /** longest first, so a secret that contains another is not left partly in place */
    private List<String> secrets(Map<String, String> environment) {
        Set<String> names = Sets.newHashSet(ALWAYS);
        names.addAll(Splitter.onPattern("[,\\n]").trimResults().omitEmptyStrings().splitToList(environment.getOrDefault(REDACT_NAMES_VARIABLE, StringUtils.EMPTY)));

        Set<String> toReturn = Sets.newHashSet();
        environment.forEach((name, value) -> {
            if (BooleanUtils.or(new boolean[] { names.contains(name), SECRET_NAME.matcher(name).matches() })) {
                for (String variant : List.of(value, Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))) {
                    if (StringUtils.length(variant) >= MIN_LENGTH) {
                        toReturn.add(variant);
                    }
                }
            }
        });
        List<String> sorted = Lists.newArrayList(toReturn);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }
}
