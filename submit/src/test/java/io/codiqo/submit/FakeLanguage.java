package io.codiqo.submit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageSpec;
import io.codiqo.api.LanguageSupport;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.SourceLanguage;
import io.codiqo.api.code.Caller;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.Finding;
import io.codiqo.api.code.ParsedSources;
import io.codiqo.api.code.PreviousRevision;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.code.UnitKind;
import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.coverage.LineCoverage;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.api.metrics.CodeBlockMetrics;
import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.util.Fetch;
import net.sourceforge.pmd.lang.Language;

/**
 * A language that is not Java, small enough to read whole: a {@code .fake} file holds functions written {@code fn name}
 * up to {@code end}, and every line holding {@code TODO} is a finding of its function. Both lists on the wire are
 * closed, so it borrows the wire language {@code go} and the tool id {@code pmd}.
 */
class FakeLanguage implements LanguageSupport {
    static final String EXTENSION = "fake";
    static final String TODO_RULE = "NoTodo";

    private static final String FUNCTION_START = "fn ";
    private static final String FUNCTION_END = "end";
    private static final String TODO = "TODO";
    private static final int TODO_RANK = 3;

    @Override
    public SourceLanguage language() {
        return new SourceLanguage("go", List.of(EXTENSION));
    }
    /** the pipeline under test runs no review */
    @Override
    public ReviewLanguage review() {
        throw new UnsupportedOperationException();
    }
    @Override
    public void requireRuntime(RunArgs args) {}
    @Override
    public LanguageSpec analysis(LogFactory logFactory, RunArgs args, Fetch fetch) {
        return new Spec();
    }
    private static List<Unit> units(File file, String content) {
        List<Unit> toReturn = Lists.newArrayList();
        List<String> lines = Splitter.on(CharUtils.LF).splitToList(content);

        int start = 0;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.startsWith(FUNCTION_START)) {
                start = index;
            } else if (FUNCTION_END.equals(line)) {
                toReturn.add(new Unit(file, StringUtils.substringAfter(lines.get(start), FUNCTION_START).strip(), lines.subList(start, index + 1), start + 1));
            }
        }
        return toReturn;
    }

    private static final class Spec implements LanguageSpec {
        /** the fake has no copy-paste detection, so nothing asks for its PMD language */
        @Override
        public Language lang() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean supportsCpd() {
            return false;
        }
        @Override
        public ParsedSources parse(ProjectSpec owner, Collection<File> files) throws IOException {
            List<CodeBlockInfo> blocks = Lists.newArrayList();
            for (File file : files) {
                blocks.addAll(units(file, Files.readString(file.toPath(), StandardCharsets.UTF_8)));
            }
            return new ParsedSources(blocks, Set.copyOf(files), List.of(), List.of());
        }
        /** a function of the previous revision that the file no longer has */
        @Override
        public Multimap<File, CodeBlockInfo> parseRemoved(ProjectSpec owner, Collection<PreviousRevision> revisions) throws IOException {
            Multimap<File, CodeBlockInfo> toReturn = HashMultimap.create();
            for (PreviousRevision revision : revisions) {
                Map<String, Unit> before = Maps.newHashMap(Maps.uniqueIndex(units(revision.getFile(), revision.getContent()), Unit::getSignature));
                units(revision.getFile(), Files.readString(revision.getFile().toPath(), StandardCharsets.UTF_8)).forEach(kept -> before.remove(kept.getSignature()));
                toReturn.putAll(revision.getFile(), before.values());
            }
            return toReturn;
        }
        @Override
        public AffectedSymbolInfo symbol(CodeBlockInfo block) {
            return new Symbol((Unit) block);
        }
        @Override
        public void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) {}
        @Override
        public void captureViolations(IndexingSummary summary, CommitAnalysis analysis) {}
        @Override
        public void captureIncomingCalls(IndexingSummary summary, CommitAnalysis analysis) {}
        @Override
        public void close() {}
    }

    /** a function, which is its own metrics: one line of code per line of its body */
    private static final class Unit implements CodeBlockInfo, CodeBlockMetrics {
        private final File file;
        private final String name;
        private final List<String> lines;
        private final int startLine;

        private Unit(File file, String name, List<String> lines, int startLine) {
            this.file = file;
            this.name = name;
            this.lines = List.copyOf(lines);
            this.startLine = startLine;
        }
        @Override
        public String getSignature() {
            return FilenameUtils.getBaseName(file.getName()) + "." + name;
        }
        @Override
        public UnitKind getKind() {
            return UnitKind.FUNCTION;
        }
        @Override
        public String getDisplaySignature() {
            return name;
        }
        @Override
        public String getDeclaringType() {
            return FilenameUtils.getBaseName(file.getName());
        }
        @Override
        public List<String> getModifiers() {
            return List.of();
        }
        @Override
        public int getArity() {
            return 0;
        }
        @Override
        public File getFile() {
            return file;
        }
        @Override
        public SourceLocation getLocation() {
            return SourceLocation.builder().startLine(startLine).startColumn(1).endLine(startLine + lines.size() - 1).endColumn(1).build();
        }
        @Override
        public String getBody() {
            return Joiner.on(StringUtils.LF).join(lines);
        }
        @Override
        public boolean isTrivial() {
            return false;
        }
        @Override
        public CodeBlockMetrics metrics() {
            return this;
        }
        @Override
        public CodeBlockCoverage coverage() {
            return CodeBlockCoverage.from(new IntObjectHashMap<>());
        }
        @Override
        public IntObjectMap<LineCoverage> getLineCoverage() {
            return new IntObjectHashMap<>();
        }
        @Override
        public List<Finding> getFindings() {
            List<Finding> toReturn = Lists.newArrayList();
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains(TODO)) {
                    toReturn.add(new StubFinding("pmd", TODO_RULE, Finding.Severity.WARNING, StubFinding.lines(startLine + index, startLine + index), TODO_RANK));
                }
            }
            return toReturn;
        }
        @Override
        public CodeBlockInfo block() {
            return this;
        }
        @Override
        public int lineCount() {
            return lines.size();
        }
        @Override
        public int nonCommentCodeLines() {
            return lines.size();
        }
        @Override
        public int commentLines() {
            return 0;
        }
        @Override
        public int cyclo() {
            return 1;
        }
        @Override
        public int cognitive() {
            return 0;
        }
        @Override
        public int ncss() {
            return lines.size();
        }
        @Override
        public long npath() {
            return 1;
        }
        @Override
        public int fanOut() {
            return 0;
        }
        @Override
        public int directInvocationCount() {
            return 0;
        }
    }

    private static final class Symbol implements AffectedSymbolInfo {
        private final Unit unit;

        private Symbol(Unit unit) {
            this.unit = unit;
        }
        @Override
        public String getName() {
            return unit.name;
        }
        @Override
        public File getFile() {
            return unit.getFile();
        }
        @Override
        public SourceLocation getLocation() {
            return unit.getLocation();
        }
        @Override
        public Optional<CodeBlockInfo> block() {
            return Optional.of(unit);
        }
        @Override
        public UnitKind getKind() {
            return unit.getKind();
        }
        @Override
        public List<Caller> getCallers() {
            return List.of();
        }
        @Override
        public boolean isDeprecated() {
            return false;
        }
        @Override
        public void describe(CodeUnitModel model) {}
    }
}
