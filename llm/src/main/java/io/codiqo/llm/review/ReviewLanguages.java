package io.codiqo.llm.review;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.api.review.UnitName;
import lombok.experimental.UtilityClass;

/**
 * Finds the {@link ReviewLanguage} of a file among those the language modules registered. A file of no registered
 * language is still reviewed: its labels match a unit only by the exact name, and its findings are not triaged.
 */
@UtilityClass
public class ReviewLanguages {
    private static final ReviewLanguage UNREGISTERED = new Unregistered();

    public ReviewLanguage of(String path, Collection<ReviewLanguage> languages) {
        String extension = FilenameUtils.getExtension(StringUtils.defaultString(path));
        for (ReviewLanguage language : languages) {
            if (language.extensions().contains(extension)) {
                return language;
            }
        }
        return UNREGISTERED;
    }
    /**
     * The naming rules of the registered languages these files are in, in registration order. A file of no registered
     * language adds no rule: its labels match only by the exact name, which the prompt's own wording already asks for,
     * and a rule for it would invite labels for units the index never produces. When the commit's files are not known
     * (the local diff could not be computed, or the commit is a shallow clone's boundary) every registered rule is given.
     */
    public List<String> namingRules(Collection<String> paths, Collection<ReviewLanguage> languages) {
        Set<ReviewLanguage> present = paths.stream().map(path -> of(path, languages)).collect(Collectors.toSet());
        return languages.stream()
                .filter(language -> BooleanUtils.or(new boolean[] { paths.isEmpty(), present.contains(language) }))
                .map(ReviewLanguage::namingRule)
                .toList();
    }
    private static final class Unregistered implements ReviewLanguage {
        @Override
        public Collection<String> extensions() {
            return List.of();
        }
        @Override
        public String namingRule() {
            throw new UnsupportedOperationException("a file of no registered language has no naming rule");
        }
        @Override
        public UnitName labelled(String signature, String path) {
            return new UnitName(List.of(), StringUtils.deleteWhitespace(StringUtils.defaultString(signature)));
        }
        @Override
        public UnitName unit(String name, String signature, String path) {
            return new UnitName(List.of(), StringUtils.deleteWhitespace(StringUtils.defaultString(name)));
        }
        @Override
        public Set<String> triagedTools() {
            return Set.of();
        }
    }
}
