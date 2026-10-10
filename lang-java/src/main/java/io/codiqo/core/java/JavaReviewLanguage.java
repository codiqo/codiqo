package io.codiqo.core.java;

import java.lang.constant.ConstantDescs;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.api.review.UnitName;

/**
 * How a Java unit is named in a local review. A reviewer writes {@code Class.Nested.method(Types)}; the index names a
 * unit {@code method(Types)}, with a nested parameter type as {@code Outer#Inner}, and its signature is a JVM
 * descriptor ({@code com/example/Outer$Inner.method(I)V}) whose binary class name is the path of containers.
 */
public class JavaReviewLanguage implements ReviewLanguage {
    private static final String NAMING_RULE = "For a Java file, the signature is every class that encloses the member, outermost first, then the method name"
            + " and its parameter types, all without packages, e.g. \"Totals.onSuccess(String)\", \"Totals.Batch.onSuccess(String)\" for a method of the nested class Batch,"
            + " or \"Totals(Config, Clock)\" for a constructor of Totals. A method of an anonymous class is written with the type the anonymous class creates,"
            + " e.g. \"Totals.Runnable.run()\" for run() in a new Runnable() { ... } inside Totals, and a method in an enum constant's body with the constant,"
            + " e.g. \"Op.ADD.apply(int, int)\".";
    private static final Set<String> TRIAGED_TOOLS = Set.of("pmd", "spotbugs");
    /** what separates the steps of a written name: {@code Totals.Batch}, {@code Totals#Batch}, {@code Totals$Batch} or {@code Totals::run} */
    private static final CharMatcher LABEL_STEP_SEPARATOR = CharMatcher.anyOf(".#$:");
    /** the binary name of an anonymous class ends in its number ({@code Op$1}), and a local class prefixes one ({@code Totals$1Local}) */
    private static final CharMatcher DIGIT = CharMatcher.inRange('0', '9');
    private static final char BINARY_NESTED_SEPARATOR = '$';

    @Override
    public Collection<String> extensions() {
        return JavaLanguageSpec.supportedExtensions();
    }
    @Override
    public String namingRule() {
        return NAMING_RULE;
    }
    /**
     * Splits the written name once, into the steps before the parameter list and the parameters, so the container and
     * the member come from the same reading: a return type or modifier in front ({@code void Totals.run()}) is the text
     * before the last space, annotations and type arguments are gone first, and a constructor written
     * {@code Totals.<init>(Config)} reads as {@code Totals(Config)}: named after the class it constructs, which is then
     * not a container of it. The {@code <init>} step goes before the type arguments are erased, which would take its
     * angle brackets for a type argument list and leave {@code (Config)}; a name with no step at all is a constructor of
     * the file's class.
     */
    @Override
    public UnitName labelled(String signature, String path) {
        String constructorNamed = Strings.CS.remove(Strings.CS.remove(StringUtils.defaultString(signature), '.' + ConstantDescs.INIT_NAME), ConstantDescs.INIT_NAME);
        String declared = JavaSignatures.declaration(constructorNamed);
        String head = StringUtils.substringBefore(declared, '(');
        String parameters = declared.substring(head.length());

        String qualified = Iterables.getLast(Splitter.on(CharMatcher.whitespace()).omitEmptyStrings().split(head), StringUtils.EMPTY);
        List<String> container = Lists.newArrayList(Splitter.on(LABEL_STEP_SEPARATOR).trimResults().omitEmptyStrings().split(qualified));
        String member = container.isEmpty() ? FilenameUtils.getBaseName(path) : container.removeLast();
        return new UnitName(container, JavaSignatures.comparable(member + parameters, FilenameUtils.getBaseName(path)));
    }
    /**
     * The containers come from the descriptor's binary class name: {@code com/example/Messages$Request$Builder} is
     * {@code [Messages, Request, Builder]}, an anonymous class's number is {@link UnitName#ANONYMOUS}, and a local class
     * {@code Totals$1Local} is {@code Local}. A constructor's class is its name ({@code Builder(Config)}), so it is not
     * one of its containers, matching how {@link #labelled} reads {@code Messages.Request.Builder(Config)}.
     */
    @Override
    public UnitName unit(String name, String signature, String path) {
        String owner = StringUtils.substringBefore(StringUtils.defaultString(signature), '(');
        List<String> container = Lists.newArrayList();
        int separator = owner.lastIndexOf('.');
        if (separator > 0) {
            String internalName = owner.substring(0, separator);
            String binaryName = internalName.substring(internalName.lastIndexOf('/') + 1);
            for (String step : Splitter.on(BINARY_NESTED_SEPARATOR).split(binaryName)) {
                container.add(containerStep(step));
            }
            if (ConstantDescs.INIT_NAME.equals(owner.substring(separator + 1))) {
                container.removeLast();
            }
        }
        return new UnitName(container, JavaSignatures.comparable(StringUtils.defaultString(name), FilenameUtils.getBaseName(path)));
    }
    @Override
    public Set<String> triagedTools() {
        return TRIAGED_TOOLS;
    }
    private static String containerStep(String step) {
        if (DIGIT.matchesAllOf(step)) {
            return UnitName.ANONYMOUS;
        }
        return DIGIT.trimLeadingFrom(step);
    }
}
