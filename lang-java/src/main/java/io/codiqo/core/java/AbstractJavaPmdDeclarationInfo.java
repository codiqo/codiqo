package io.codiqo.core.java;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.jacoco.core.analysis.ILine;

import com.google.common.base.Objects;
import com.google.common.base.Suppliers;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import edu.umd.cs.findbugs.BugInstance;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.metrics.CodeBlockMetrics;
import io.codiqo.lang.spec.JInvocationBlock;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.JModifier;
import net.sourceforge.pmd.lang.java.ast.internal.PrettyPrintingUtil;
import net.sourceforge.pmd.lang.java.metrics.JavaMetrics;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.metrics.MetricOptions;
import net.sourceforge.pmd.lang.metrics.MetricsUtil;
import net.sourceforge.pmd.reporting.RuleViolation;

@Setter
@Getter
@SuperBuilder
abstract class AbstractJavaPmdDeclarationInfo implements JavaCodeBlockInfo {
    private static final int TRIVIAL_NCSS_MAX = 2;
    private static final int TRIVIAL_CYCLO_MAX = 1;

    private ASTTypeDeclaration type;
    private ASTTypeDeclaration enclosingType;
    private ASTExecutableDeclaration node;
    private File file;
    private String body;
    private SourceLocation location;
    @Builder.Default
    private Collection<JInvocationBlock> invocations = Lists.newArrayList();
    @Builder.Default
    private List<RuleViolation> pmdViolations = Lists.newArrayList();
    @Builder.Default
    private List<BugInstance> spotbugs = Lists.newArrayList();
    @Builder.Default
    private Map<Integer, ILine> lineCoverage = Maps.newHashMap();
    @Builder.Default
    private Optional<AffectedSymbolInfo> affectedSymbol = Optional.empty();

    private final Supplier<CodeBlockCoverage> coverage = Suppliers.memoize(() -> CodeBlockCoverage.from(lineCoverage));
    private final Supplier<CodeBlockMetrics> metrics = Suppliers.memoize(() -> {
        int lineCount = MetricsUtil.computeMetric(JavaMetrics.LINES_OF_CODE, node, MetricOptions.emptyOptions());
        JavaLineCountAnalyzer.LineCounts lineCounts = JavaLineCountAnalyzer.analyze(node);
        int nonCommentCodeLines = lineCounts.getCodeLines();
        int commentLines = lineCounts.getCommentLines();
        int cyclo = MetricsUtil.computeMetric(JavaMetrics.CYCLO, node, MetricOptions.emptyOptions());
        int cognitive = MetricsUtil.computeMetric(JavaMetrics.COGNITIVE_COMPLEXITY, node, MetricOptions.emptyOptions());
        int ncss = MetricsUtil.computeMetric(JavaMetrics.NCSS, node, MetricOptions.emptyOptions());
        long npath = MetricsUtil.computeMetric(JavaMetrics.NPATH_COMP, node, MetricOptions.emptyOptions());
        int fanOut = MetricsUtil.computeMetric(JavaMetrics.FAN_OUT, node, MetricOptions.emptyOptions());
        List<Integer> directInvocationLines = InvocationCounter.collectDirect(node);
        int directInvocationCount = directInvocationLines.size();

        return new CodeBlockMetrics() {
            @Override
            public JavaCodeBlockInfo block() {
                return AbstractJavaPmdDeclarationInfo.this;
            }
            @Override
            public int lineCount() {
                return lineCount;
            }
            @Override
            public int nonCommentCodeLines() {
                return nonCommentCodeLines;
            }
            @Override
            public int commentLines() {
                return commentLines;
            }
            @Override
            public int cyclo() {
                return cyclo;
            }
            @Override
            public int cognitive() {
                return cognitive;
            }
            @Override
            public int ncss() {
                return ncss;
            }
            @Override
            public long npath() {
                return npath;
            }
            @Override
            public int fanOut() {
                return fanOut;
            }
            @Override
            public int directInvocationCount() {
                return directInvocationCount;
            }
            @Override
            public List<Integer> directInvocationLines() {
                return directInvocationLines;
            }
            @Override
            public String toString() {
                return String.format("lines: %d, cyclo: %d, cognitive: %d, ncss: %d, invocations: %d", lineCount(), cyclo(), cognitive(), ncss(), directInvocationCount());
            }
        };
    });

    @Override
    public void accept(AffectedSymbolInfo info) {
        this.affectedSymbol = Optional.of(info);
    }
    @Override
    public List<String> getModifiers() {
        return node.getModifiers().getEffectiveModifiers().stream().map(JModifier::getToken).collect(Collectors.toList());
    }
    @Override
    public boolean isFinal() {
        return node.isFinal();
    }
    @Override
    public boolean isStatic() {
        return node.isStatic();
    }
    @Override
    public boolean isAbstract() {
        return node.isAbstract();
    }
    @Override
    public boolean isSynchronized() {
        return node.hasModifiers(JModifier.SYNCHRONIZED);
    }
    @Override
    public void pmdViolation(RuleViolation violation) {
        pmdViolations.add(violation);
    }
    @Override
    public void spotbug(BugInstance violation) {
        spotbugs.add(violation);
    }
    @Override
    public void lineCoverage(int lineNumber, ILine line) {
        lineCoverage.put(lineNumber, line);
    }
    @Override
    public ASTExecutableDeclaration getDeclaration() {
        return node;
    }
    @Override
    public int getArity() {
        return node.getArity();
    }
    @Override
    public boolean hasMethodCalls() {
        return CollectionUtils.isNotEmpty(invocations);
    }
    @Override
    public boolean isTrivial() {
        CodeBlockMetrics blockMetrics = metrics.get();
        return blockMetrics.ncss() <= TRIVIAL_NCSS_MAX && blockMetrics.directInvocationCount() == 0 && blockMetrics.cyclo() <= TRIVIAL_CYCLO_MAX;
    }
    @Override
    public Optional<AffectedSymbolInfo> affectedSymbol() {
        return affectedSymbol;
    }
    @Override
    public CodeBlockMetrics metrics() {
        return metrics.get();
    }
    @Override
    public CodeBlockCoverage coverage() {
        return coverage.get();
    }
    @Override
    public int hashCode() {
        return node.hashCode();
    }
    @Override
    public boolean equals(Object other) {
        return Objects.equal(node, ((AbstractJavaPmdDeclarationInfo) other).node);
    }
    @Override
    public String toString() {
        JClassSymbol symbol = Optional.ofNullable(type).orElse(enclosingType).getSymbol();
        return String.format("%s( %s )", symbol.getBinaryName(), PrettyPrintingUtil.displaySignature(node));
    }
}
