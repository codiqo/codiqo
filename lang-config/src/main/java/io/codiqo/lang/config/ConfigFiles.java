package io.codiqo.lang.config;

import java.util.Collection;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;

import io.codiqo.lang.config.FileKind.Scoring;
import lombok.experimental.UtilityClass;

/** The build descriptors codiqo scores by their changed lines: the {@link FileKinds} rows scored by lines. */
@UtilityClass
public class ConfigFiles {
    public String describeSupported() {
        return FileKinds.all().stream().filter(kind -> kind.isScoredBy(Scoring.LINES)).map(FileKind::describe).collect(Collectors.joining(", "));
    }
    public boolean isConfigFile(String path) {
        return FileKinds.of(path).filter(kind -> kind.isScoredBy(Scoring.LINES)).isPresent();
    }
    /**
     * whether a change set is nothing but config descriptors. A change set with no files is not config-only:
     * nothing was changed, so there is nothing to classify.
     */
    public boolean isConfigOnly(Collection<String> paths) {
        return CollectionUtils.isNotEmpty(paths) && paths.stream().allMatch(ConfigFiles::isConfigFile);
    }
}
