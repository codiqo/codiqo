package io.codiqo.core.java;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;

import io.codiqo.api.IncomingCallsResolver;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.jdtls.JdtLspProjectImporter;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import io.codiqo.lang.spec.PmdAffectedSymbolInfo;
import lombok.RequiredArgsConstructor;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;

@RequiredArgsConstructor
class JdtIncomingCallsResolver implements IncomingCallsResolver {
    private final Log log;
    private final RunArgs args;
    private final JdtLspProjectImporter jdt;

    @Override
    public void resolve(IndexingSummary summary, CommitAnalysis analysis) throws IOException {
        StopWatch watch = StopWatch.createStarted();

        AtomicInteger populated = new AtomicInteger();
        AtomicInteger total = new AtomicInteger();
        String workTreeUri = args.getGit().getWorkTree().toPath().toRealPath().toUri().toString();

        analysis.forEach(fileAnalysis -> {
            if (fileAnalysis.isTestFile()) {
                return;
            }
            for (AffectedSymbolInfo symbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                if (symbol instanceof PmdAffectedSymbolInfo) {
                    PmdAffectedSymbolInfo pmdSymbol = (PmdAffectedSymbolInfo) symbol;
                    symbol.block().ifPresent(rawBlock -> {
                        if (rawBlock instanceof JavaCodeBlockInfo) {
                            JavaCodeBlockInfo block = (JavaCodeBlockInfo) rawBlock;
                            total.incrementAndGet();

                            SourceLocation loc = block.getLocation();
                            ASTTypeDeclaration ownerType = Optional.ofNullable(block.getType()).orElse(block.getEnclosingType());

                            CallHierarchyItem item = new CallHierarchyItem();
                            item.setName(pmdSymbol.getName());
                            item.setKind(pmdSymbol.getKind());
                            item.setUri(block.getFile().toPath().normalize().toUri().toString());
                            item.setDetail(ownerType.getSymbol().getBinaryName());
                            item.setRange(loc.toLspRange());
                            item.setSelectionRange(loc.toLspSelectionRange());
                            item.setTags(pmdSymbol.getTags());

                            try {
                                long lspTimeout = args.getLspQueryTimeout().getSeconds();
                                log.info("querying incoming calls for: %s in %s [%d:%d-%d:%d]",
                                        pmdSymbol.getName(), block.getFile().getName(),
                                        item.getRange().getStart().getLine(), item.getRange().getStart().getCharacter(),
                                        item.getRange().getEnd().getLine(), item.getRange().getEnd().getCharacter());
                                Optional.ofNullable(jdt.callHierarchyIncomingCalls(item).get(lspTimeout, TimeUnit.SECONDS)).ifPresent(calls -> {
                                    log.info("  -> found %d callers", calls.size());
                                    for (CallHierarchyIncomingCall call : calls) {
                                        CallHierarchyItem from = call.getFrom();
                                        String callerPath = from.getUri();
                                        if (callerPath.startsWith(workTreeUri)) {
                                            callerPath = callerPath.substring(workTreeUri.length());
                                        }
                                        log.info("     caller: %s (%s) in %s [%d:%d-%d:%d]",
                                                from.getName(),
                                                from.getKind(),
                                                callerPath,
                                                from.getRange().getStart().getLine(),
                                                from.getRange().getStart().getCharacter(),
                                                from.getRange().getEnd().getLine(),
                                                from.getRange().getEnd().getCharacter());
                                    }
                                    pmdSymbol.getIncomingCalls().addAll(calls);
                                    populated.incrementAndGet();
                                });
                            } catch (Exception err) {
                                log.error(String.format(
                                        "failed to fetch incoming calls for symbol %s in file %s: %s | item[uri=%s, kind=%s, range=%d:%d-%d:%d, detail=%s]",
                                        pmdSymbol.getName(),
                                        block.getFile().getAbsolutePath(),
                                        err.getMessage(),
                                        item.getUri(),
                                        item.getKind(),
                                        item.getRange().getStart().getLine(),
                                        item.getRange().getStart().getCharacter(),
                                        item.getRange().getEnd().getLine(),
                                        item.getRange().getEnd().getCharacter(),
                                        item.getDetail()),
                                        err);

                                if (args.isFailOnJdtlsError()) {
                                    Throwable cause = ExceptionUtils.getRootCause(err);
                                    if (Objects.isNull(cause)) {
                                        cause = err;
                                    }
                                    if (cause instanceof IOException) {
                                        throw new UncheckedIOException((IOException) cause);
                                    }
                                    throw new UncheckedIOException(new IOException(cause.getMessage(), cause));
                                }
                            }
                        }
                    });
                }
            }
        });

        watch.stop();
        log.info("incoming calls resolved via JDT LS in %s: %d/%d symbols have callers", watch, populated.get(), total.get());
    }
}
