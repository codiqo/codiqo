package io.codiqo.submit.hotspots;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.Type;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.cpd.CloneLocations;
import io.codiqo.api.cpd.CloneLocations.Span;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.client.model.HotspotFindingKind;
import io.codiqo.client.model.HotspotFindingModel;
import io.codiqo.core.java.JavaBinaryFormat;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import lombok.experimental.UtilityClass;
import net.sourceforge.pmd.reporting.RuleViolation;

/**
 * Collects at most {@link #LIMIT} findings for one hotspot file. Kinds are interleaved in priority order rather than
 * concatenated, so a class with thirty uncovered methods still reports its clones and bugs within the limit.
 */
@UtilityClass
public class HotspotFindings {
    public static final int LIMIT = 10;

    /** Sonar's default cognitive-complexity threshold. */
    public static final int COMPLEX_METHOD_THRESHOLD = 15;

    public List<HotspotFindingModel> collect(File file, Collection<CodeBlockInfo> blocks, Collection<CopyPasteDetectionSummary> cpd, Path workTree) {
        Map<HotspotFindingKind, List<HotspotFindingModel>> byKind = new EnumMap<>(HotspotFindingKind.class);
        for (HotspotFindingKind kind : HotspotFindingKind.values()) {
            byKind.put(kind, new ArrayList<>());
        }

        /**
         * A violation is attached to every block whose range holds it, so a nested block shares it with its enclosing
         * blocks. Only the innermost block keeps it; otherwise one violation would be reported once per enclosing
         * block.
         */
        List<CodeBlockInfo> uncovered = new ArrayList<>();
        List<CodeBlockInfo> complex = new ArrayList<>();
        Map<RuleViolation, CodeBlockInfo> violations = new LinkedHashMap<>();
        Map<BugInstance, CodeBlockInfo> bugs = new LinkedHashMap<>();
        for (CodeBlockInfo block : blocks) {
            CodeBlockCoverage coverage = block.coverage();
            if (BooleanUtils.and(new boolean[] { coverage.hasCoverageData(), coverage.getMissed() + coverage.getPartial() > 0 })) {
                uncovered.add(block);
            }
            if (block.metrics().cognitive() > COMPLEX_METHOD_THRESHOLD) {
                complex.add(block);
            }
            block.getPmdViolations().forEach(violation -> violations.merge(violation, block, HotspotFindings::innermost));
            if (block instanceof JavaCodeBlockInfo java) {
                java.getSpotbugs().forEach(bug -> bugs.merge(bug, block, HotspotFindings::innermost));
            }
        }

        violations.entrySet().stream()
                .sorted(Comparator.comparingInt((Entry<RuleViolation, CodeBlockInfo> entry) -> entry.getKey().getRule().getPriority().getPriority()))
                .forEach(entry -> {
                    RuleViolation violation = entry.getKey();
                    byKind.get(HotspotFindingKind.PMD).add(finding(HotspotFindingKind.PMD, entry.getValue(), violation.getBeginLine(), violation.getEndLine(), violation.getDescription())
                            .rule(violation.getRule().getName()));
                });

        bugs.entrySet().stream()
                .sorted(Comparator.comparingInt((Entry<BugInstance, CodeBlockInfo> entry) -> entry.getKey().getBugRank()))
                .forEach(entry -> {
                    BugInstance bug = entry.getKey();
                    CodeBlockInfo block = entry.getValue();
                    SourceLineAnnotation line = bug.getPrimarySourceLineAnnotation();
                    int start = block.getLocation().getStartLine();
                    int end = block.getLocation().getEndLine();
                    if (Objects.nonNull(line)) {
                        start = line.getStartLine();
                        end = line.getEndLine();
                    }
                    byKind.get(HotspotFindingKind.SPOTBUGS).add(finding(HotspotFindingKind.SPOTBUGS, block, start, end, bug.getMessage())
                            .rule(bug.getBugPattern().getType()));
                });

        uncovered.sort(Comparator.comparingInt((CodeBlockInfo block) -> block.coverage().getMissed() + block.coverage().getPartial()).reversed());
        for (CodeBlockInfo block : uncovered) {
            CodeBlockCoverage coverage = block.coverage();
            byKind.get(HotspotFindingKind.UNCOVERED_METHOD).add(finding(HotspotFindingKind.UNCOVERED_METHOD, block,
                    block.getLocation().getStartLine(), block.getLocation().getEndLine(),
                    String.format("%d of %d executable lines not covered (%d partly)",
                            coverage.getMissed() + coverage.getPartial(), coverage.executable(), coverage.getPartial())));
        }

        complex.sort(Comparator.comparingInt((CodeBlockInfo block) -> block.metrics().cognitive()).reversed());
        for (CodeBlockInfo block : complex) {
            byKind.get(HotspotFindingKind.COMPLEX_METHOD).add(finding(HotspotFindingKind.COMPLEX_METHOD, block,
                    block.getLocation().getStartLine(), block.getLocation().getEndLine(),
                    String.format("cognitive complexity %d (threshold %d)", block.metrics().cognitive(), COMPLEX_METHOD_THRESHOLD)));
        }

        byKind.get(HotspotFindingKind.CPD_CLONE).addAll(clones(file, cpd, workTree));

        return interleave(byKind);
    }
    private static List<HotspotFindingModel> clones(File file, Collection<CopyPasteDetectionSummary> cpd, Path workTree) {
        List<CloneLocations> clones = new ArrayList<>();
        cpd.forEach(summary -> summary.clones().stream()
                .filter(clone -> clone.getSpans().stream().anyMatch(span -> span.getFile().equals(file)))
                .forEach(clones::add));
        clones.sort(Comparator.comparingInt(CloneLocations::getLineCount).reversed());

        /**
         * One fragment copied to several places is one thing to fix, so clones are grouped by the fragment's span in
         * this file and reported once with a copy count, rather than once per pair.
         */
        Map<Pair<Integer, Integer>, HotspotFindingModel> byFragment = new LinkedHashMap<>();
        Map<Pair<Integer, Integer>, Integer> copies = new HashMap<>();
        for (CloneLocations clone : clones) {
            Span here = null;
            List<Span> others = new ArrayList<>();
            for (Span span : clone.getSpans()) {
                if (Objects.isNull(here) && span.getFile().equals(file)) {
                    here = span;
                } else {
                    others.add(span);
                }
            }
            if (CollectionUtils.isNotEmpty(others)) {
                Pair<Integer, Integer> fragment = Pair.of(here.getStartLine(), here.getEndLine());
                copies.merge(fragment, others.size(), Integer::sum);
                Span other = others.get(0);
                byFragment.putIfAbsent(fragment, new HotspotFindingModel()
                        .kind(HotspotFindingKind.CPD_CLONE)
                        .startLine(here.getStartLine())
                        .endLine(here.getEndLine())
                        .message(String.format("%d duplicated lines (%d tokens)", clone.getLineCount(), clone.getTokenCount()))
                        .otherFilePath(relative(workTree, other.getFile()))
                        .otherStartLine(other.getStartLine())
                        .otherEndLine(other.getEndLine()));
            }
        }

        List<HotspotFindingModel> toReturn = new ArrayList<>();
        byFragment.forEach((fragment, finding) -> {
            int count = copies.get(fragment);
            if (count > 1) {
                finding.setMessage(finding.getMessage() + String.format(", copied to %d places", count));
            }
            toReturn.add(finding);
        });
        return toReturn;
    }
    private static List<HotspotFindingModel> interleave(Map<HotspotFindingKind, List<HotspotFindingModel>> byKind) {
        List<HotspotFindingModel> toReturn = new ArrayList<>();
        List<Iterator<HotspotFindingModel>> queues = byKind.values().stream().map(List::iterator).toList();
        boolean progressed = true;
        while (BooleanUtils.and(new boolean[] { progressed, toReturn.size() < LIMIT })) {
            progressed = false;
            for (Iterator<HotspotFindingModel> queue : queues) {
                if (BooleanUtils.and(new boolean[] { queue.hasNext(), toReturn.size() < LIMIT })) {
                    toReturn.add(queue.next());
                    progressed = true;
                }
            }
        }
        return toReturn;
    }
    private static HotspotFindingModel finding(HotspotFindingKind kind, CodeBlockInfo block, int startLine, int endLine, String message) {
        return new HotspotFindingModel()
                .kind(kind)
                .member(readable(block.getSignature()))
                .startLine(startLine)
                .endLine(endLine)
                .message(message);
    }
    /** Renders {@code a/B.toInfo(La/Account;Ljava/util/Set;)V} as {@code toInfo(Account, Set)}. */
    static String readable(String signature) {
        String owner = StringUtils.substringBefore(signature, "(");
        String name = StringUtils.substringAfterLast(owner, ".");
        if (JavaBinaryFormat.CONSTRUCTOR_NAME.equals(name)) {
            name = ClassUtils.getShortClassName(JavaBinaryFormat.getBinaryName(StringUtils.substringBeforeLast(owner, ".")));
        }
        List<String> parameters = new ArrayList<>();
        for (Type parameter : Type.getArgumentTypes("(" + StringUtils.substringAfter(signature, "("))) {
            parameters.add(ClassUtils.getShortClassName(parameter.getClassName()));
        }
        return name + "(" + StringUtils.join(parameters, ", ") + ")";
    }
    private static CodeBlockInfo innermost(CodeBlockInfo current, CodeBlockInfo candidate) {
        if (lineSpan(candidate) < lineSpan(current)) {
            return candidate;
        }
        return current;
    }
    private static int lineSpan(CodeBlockInfo block) {
        return block.getLocation().getEndLine() - block.getLocation().getStartLine();
    }
    static String relative(Path workTree, File file) {
        return FilenameUtils.separatorsToUnix(workTree.relativize(file.toPath().normalize()).toString());
    }
}
