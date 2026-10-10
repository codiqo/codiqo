package io.codiqo.api.review;

import java.util.Collection;
import java.util.Set;

/**
 * What a local review needs to know about one language, supplied by that language's module so the review itself knows
 * none: how reviewers are told to name a code unit, how the name they wrote is compared with an indexed unit, and which
 * static-analysis tools' findings are put to the triage.
 */
public interface ReviewLanguage {
    /** the file extensions the language module registers, without the dot */
    Collection<String> extensions();
    /** one or two sentences, with examples, telling a reviewer how to write the signature of a unit of this language */
    String namingRule();
    /** what a signature a reviewer wrote names, in the form {@link #unit} produces for the same unit */
    UnitName labelled(String signature, String path);
    /** what an indexed unit is called, from the name and the signature the index gave it */
    UnitName unit(String name, String signature, String path);
    /** the tools, by their diagnostic tool value, whose findings on added lines the triage judges */
    Set<String> triagedTools();
}
