package io.codiqo.llm.lang;

import java.util.EnumSet;
import java.util.Objects;

import org.apache.commons.lang3.BooleanUtils;

import io.codiqo.api.diff.CommentSyntax;
import io.codiqo.api.diff.IneffectiveLineFilter;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FileChangeModel.LanguageEnum;
import io.codiqo.lang.config.ConfigFiles;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LanguageCapabilities {
    private static final EnumSet<LanguageEnum> LINE_FILTERING_LANGUAGES = EnumSet.of(LanguageEnum.JAVA);

    private static final IneffectiveLineFilter C_STYLE_FILTER = new IneffectiveLineFilter(CommentSyntax.C_STYLE, IneffectiveLineFilter.IMPORT_PREFIX);

    public static boolean requiresLineFiltering(LanguageEnum language) {
        return Objects.nonNull(language) && LINE_FILTERING_LANGUAGES.contains(language);
    }
    public static boolean requiresLineFiltering(FileChangeModel file) {
        return requiresLineFiltering(file.getLanguage());
    }
    public static boolean isLineCountScored(FileChangeModel file) {
        return ConfigFiles.isConfigFile(file.getPath());
    }
    public static boolean requiresDiffClassification(FileChangeModel file) {
        return BooleanUtils.or(new boolean[] { requiresLineFiltering(file), isLineCountScored(file) });
    }
    public static IneffectiveLineFilter filterFor(FileChangeModel file) {
        if (requiresLineFiltering(file)) {
            return C_STYLE_FILTER;
        }
        return ConfigFiles.filterFor(file.getPath()).orElse(IneffectiveLineFilter.NONE);
    }
}
