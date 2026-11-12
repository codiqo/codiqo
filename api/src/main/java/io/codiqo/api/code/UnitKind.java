package io.codiqo.api.code;

/**
 * What kind of code unit a symbol is, the same for every language: each language maps its own symbol kinds onto these
 * (the Java language maps the language server's), and the submission writes them as the wire's symbol kinds.
 */
public enum UnitKind {
    CLASS,
    INTERFACE,
    ENUM,
    ENUM_MEMBER,
    RECORD,
    METHOD,
    CONSTRUCTOR,
    FUNCTION,
    FIELD,
    CONSTANT,
    MODULE
}
