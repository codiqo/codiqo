package io.codiqo.api.jdtls;

public interface JdtlsEnums {
    //
    // ~ CompletionGuessMethodArgumentsMode
    //
    String GUESS_OFF = "off";
    String GUESS_INSERT_PARAMETER_NAMES = "insertParameterNames";
    String GUESS_INSERT_BEST_GUESSED_ARGUMENTS = "insertBestGuessedArguments";

    //
    // ~ CompletionMatchCaseMode
    //
    String MATCH_CASE_OFF = "off";
    String MATCH_CASE_FIRST_LETTER = "firstLetter";

    //
    // ~ InlayHintsParameterMode
    //
    String INLAY_NONE = "none";
    String INLAY_LITERALS = "literals";
    String INLAY_ALL = "all";

    //
    // ~ ProjectEncodingMode
    //
    String ENCODING_IGNORE = "ignore";
    String ENCODING_WARNING = "warning";
    String ENCODING_SETDEFAULT = "setDefault";

    //
    // ~ InsertionLocationKind
    //
    String INSERTION_LAST_MEMBER = "lastMember";
    String INSERTION_BEFORE_CURSOR = "beforeCursor";

    //
    // ~ AddFinalForNewDeclarationKind
    //
    String ADD_FINAL_NONE = "none";
    String ADD_FINAL_ALL = "all";
    String ADD_FINAL_VARIABLES = "variables";
    String ADD_FINAL_FIELDS = "fields";

    //
    // ~ ToStringCodeStyleKind
    //
    String TOSTRING_STRING_CONCATENATION = "STRING_CONCATENATION";
    String TOSTRING_STRING_BUILDER = "STRING_BUILDER";
    String TOSTRING_STRING_BUILDER_CHAINED = "STRING_BUILDER_CHAINED";
    String TOSTRING_STRING_FORMAT = "STRING_FORMAT";

    //
    // ~ ImplementationsCodeLensKind
    //
    String IMPL_CODELENS_NONE = "none";
    String IMPL_CODELENS_ALL = "all";
    String IMPL_CODELENS_INTERFACE_ONLY = "interfaceOnly";

    //
    // ~ severity
    //
    String SEVERITY_IGNORE = "ignore";
    String SEVERITY_WARNING = "warning";
    String SEVERITY_ERROR = "error";

    // ~ Server launch mode
    String LAUNCH_MODE_STANDARD = "Standard";
    String LAUNCH_MODE_LIGHTWEIGHT = "LightWeight";
    String LAUNCH_MODE_HYBRID = "Hybrid";

    //
    // ~ build configuration update
    //
    String BUILD_CONFIG_INTERACTIVE = "interactive";
    String BUILD_CONFIG_AUTOMATIC = "automatic";
    String BUILD_CONFIG_DISABLED = "disabled";
}
