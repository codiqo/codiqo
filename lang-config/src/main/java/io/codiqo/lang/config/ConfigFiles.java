package io.codiqo.lang.config;

import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import io.codiqo.api.config.ConfigFileSpec;
import io.codiqo.api.diff.IneffectiveLineFilter;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ConfigFiles {
    private static final List<ConfigFileSpec> SPECS = ImmutableList.of(new PomFileSpec(), new ProtoFileSpec());

    public boolean isConfigFile(String path) {
        return SPECS.stream().anyMatch(spec -> spec.matches(path));
    }
    public Optional<IneffectiveLineFilter> filterFor(String path) {
        return SPECS.stream().filter(spec -> spec.matches(path)).map(ConfigFileSpec::lineFilter).findFirst();
    }
}
