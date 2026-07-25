package io.codiqo.lang.config;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;

import io.codiqo.api.config.ConfigFileSpec;
import io.codiqo.api.diff.IneffectiveLineFilter;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ConfigFiles {
    private static final List<ConfigFileSpec> SPECS = List.of(new PomFileSpec(), new ProtoFileSpec());

    public boolean isConfigFile(String path) {
        return SPECS.stream().anyMatch(spec -> spec.matches(path));
    }
    /**
     * whether a whole change set is nothing but config descriptors — mechanical work (dependency
     * bumps, release preparation) that carries no source file. A change set with no files is not
     * config-only: nothing was changed, so there is nothing to classify.
     */
    public boolean isConfigOnly(Collection<String> paths) {
        return CollectionUtils.isNotEmpty(paths) && paths.stream().allMatch(ConfigFiles::isConfigFile);
    }
    public Optional<IneffectiveLineFilter> filterFor(String path) {
        return SPECS.stream().filter(spec -> spec.matches(path)).map(ConfigFileSpec::lineFilter).findFirst();
    }
}
