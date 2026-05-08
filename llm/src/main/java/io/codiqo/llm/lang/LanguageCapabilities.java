package io.codiqo.llm.lang;

import java.util.EnumSet;
import java.util.Objects;

import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FileChangeModel.LanguageEnum;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LanguageCapabilities {
    private static final EnumSet<LanguageEnum> LINE_FILTERING_LANGUAGES = EnumSet.of(LanguageEnum.JAVA);

    public static boolean requiresLineFiltering(LanguageEnum language) {
        return Objects.nonNull(language) && LINE_FILTERING_LANGUAGES.contains(language);
    }
    public static boolean requiresLineFiltering(FileChangeModel file) {
        return requiresLineFiltering(file.getLanguage());
    }
}
