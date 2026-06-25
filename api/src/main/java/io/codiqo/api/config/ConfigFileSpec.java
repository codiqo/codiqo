package io.codiqo.api.config;

import io.codiqo.api.diff.IneffectiveLineFilter;

/**
 * A config-file kind (e.g. pom.xml, .proto) recognised by path and scored on line count + LLM
 * judgment rather than parsed structurally. Distinct from {@code LanguageSpec}, which carries the
 * heavy parsing/JDT machinery; config files only need path detection and a line filter.
 */
public interface ConfigFileSpec {
    boolean matches(String path);
    IneffectiveLineFilter lineFilter();
}
