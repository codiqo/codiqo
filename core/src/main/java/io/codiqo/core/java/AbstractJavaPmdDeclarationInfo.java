package io.codiqo.core.java;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.jacoco.core.analysis.ILine;

import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.metrics.CodeBlockMetrics;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.internal.PrettyPrintingUtil;
import net.sourceforge.pmd.lang.java.metrics.JavaMetrics;
import net.sourceforge.pmd.lang.metrics.MetricOptions;
import net.sourceforge.pmd.lang.metrics.MetricsUtil;
import net.sourceforge.pmd.reporting.RuleViolation;
import reactor.core.publisher.Mono;

@Setter
@Getter
@SuperBuilder
public abstract class AbstractJavaPmdDeclarationInfo implements JavaCodeBlockInfo {
    private ASTTypeDeclaration type;
    private ASTExecutableDeclaration node;
    private File file;
    private String body;
    private SourceLocation location;
    @Builder.Default
    private List<RuleViolation> pmdViolations = Lists.newArrayList();
    @Builder.Default
    private Map<Integer, ILine> lineCoverage = Maps.newHashMap();
    private Optional<AffectedSymbolInfo> affectedSymbol = Optional.empty();
    private final Mono<CodeBlockCoverage> coverage = Mono.fromSupplier(new Supplier<CodeBlockCoverage>() {
        @Override
        public CodeBlockCoverage get() {
            return CodeBlockCoverage.from(lineCoverage);
        }
    });
    private final Mono<CodeBlockMetrics> metrics = Mono.fromSupplier(new Supplier<CodeBlockMetrics>() {
        @Override
        public CodeBlockMetrics get() {
            int lines = MetricsUtil.computeMetric(JavaMetrics.LINES_OF_CODE, node, MetricOptions.emptyOptions());
            int cyclo = MetricsUtil.computeMetric(JavaMetrics.CYCLO, node, MetricOptions.emptyOptions());
            int cognitive = MetricsUtil.computeMetric(JavaMetrics.COGNITIVE_COMPLEXITY, node, MetricOptions.emptyOptions());
            int ncss = MetricsUtil.computeMetric(JavaMetrics.NCSS, node, MetricOptions.emptyOptions());
            long npath = MetricsUtil.computeMetric(JavaMetrics.NPATH_COMP, node, MetricOptions.emptyOptions());
            int fanOut = MetricsUtil.computeMetric(JavaMetrics.FAN_OUT, node, MetricOptions.emptyOptions());

            return new CodeBlockMetrics() {
                @Override
                public JavaCodeBlockInfo block() {
                    return AbstractJavaPmdDeclarationInfo.this;
                }
                @Override
                public int lineCount() {
                    return lines;
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
                public String toString() {
                    return String.format("lines: %d, cyclo: %d, cognitive: %d, ncss: %d, npath: %d, fanOut: %d",
                            lines,
                            cyclo,
                            cognitive,
                            ncss,
                            npath,
                            fanOut);
                }
            };
        }
    });

    @Override
    public void accept(AffectedSymbolInfo info) {
        this.affectedSymbol = Optional.of(info);
    }
    @Override
    public void pmdViolation(RuleViolation violation) {
        pmdViolations.add(violation);
    }
    @Override
    public void lineCoverage(int lineNumber, ILine line) {
        lineCoverage.put(lineNumber, line);
    }
    @Override
    public boolean hasMethodCalls() {
        return node.descendants(ASTMethodCall.class).nonEmpty();
    }
    @Override
    public int countMethodCalls() {
        return node.descendants(ASTMethodCall.class).count();
    }
    @Override
    public Optional<AffectedSymbolInfo> affectedSymbol() {
        return affectedSymbol;
    }
    @Override
    public Mono<CodeBlockMetrics> metrics() {
        return metrics;
    }
    @Override
    public Mono<CodeBlockCoverage> coverage() {
        return coverage;
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        AbstractJavaPmdDeclarationInfo that = (AbstractJavaPmdDeclarationInfo) o;
        return new EqualsBuilder().append(node, that.node).isEquals();
    }
    @Override
    public int hashCode() {
        return node.hashCode();
    }
    @Override
    public String toString() {
        return String.format("%s( %s )", getType().getSymbol().getCanonicalName(), PrettyPrintingUtil.displaySignature(node));
    }
}
