package io.codiqo.llm.lang;

import java.util.EnumSet;
import java.util.Objects;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;

import io.codiqo.api.diff.IneffectiveLineProfile;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FileChangeModel.LanguageEnum;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LanguageCapabilities {
    private static final EnumSet<LanguageEnum> LINE_FILTERING_LANGUAGES = EnumSet.of(LanguageEnum.JAVA);

    private static final String POM_FILE_NAME = "pom.xml";
    private static final String PROTO_EXTENSION = "proto";

    public static boolean requiresLineFiltering(LanguageEnum language) {
        return Objects.nonNull(language) && LINE_FILTERING_LANGUAGES.contains(language);
    }
    public static boolean requiresLineFiltering(FileChangeModel file) {
        return requiresLineFiltering(file.getLanguage());
    }
    public static boolean isLineCountScored(FileChangeModel file) {
        return BooleanUtils.or(new boolean[] { isPom(file), isProto(file) });
    }
    public static boolean requiresDiffClassification(FileChangeModel file) {
        return BooleanUtils.or(new boolean[] { requiresLineFiltering(file), isLineCountScored(file) });
    }
    public static IneffectiveLineProfile profileFor(FileChangeModel file) {
        if (BooleanUtils.or(new boolean[] { requiresLineFiltering(file), isProto(file) })) {
            return IneffectiveLineProfile.C_STYLE;
        }
        if (isPom(file)) {
            return IneffectiveLineProfile.XML;
        }
        return IneffectiveLineProfile.NONE;
    }
    private static boolean isPom(FileChangeModel file) {
        return POM_FILE_NAME.equalsIgnoreCase(FilenameUtils.getName(file.getPath()));
    }
    private static boolean isProto(FileChangeModel file) {
        return PROTO_EXTENSION.equalsIgnoreCase(FilenameUtils.getExtension(file.getPath()));
    }
}
