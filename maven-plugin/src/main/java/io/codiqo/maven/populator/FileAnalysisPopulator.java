package io.codiqo.maven.populator;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolTag;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;

import com.google.common.collect.Maps;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.Priorities;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.client.model.CallerModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CoverageCounterModel;
import io.codiqo.client.model.CoverageModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.SignatureFormatModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.JavaAnnotationModel;
import io.codiqo.client.model.JavaInfoModel;
import io.codiqo.client.model.LineCoverageModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.InvocationModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.PmdPropertiesModel;
import io.codiqo.client.model.SpotbugsPropertiesModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.lang.spec.JInvocationBlock;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import io.codiqo.lang.spec.JavaConstructorBlockInfo;
import io.codiqo.lang.spec.JavaMethodBlockInfo;
import lombok.RequiredArgsConstructor;
import net.sourceforge.pmd.lang.java.ast.ASTAnnotation;
import net.sourceforge.pmd.lang.java.ast.ASTAnonymousClassDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTThrowsList;
import net.sourceforge.pmd.lang.java.ast.ASTTypeParameters;
import net.sourceforge.pmd.lang.java.ast.Annotatable;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.RuleViolation;

@RequiredArgsConstructor
public class FileAnalysisPopulator implements SubmissionPopulator {
    private static final Function<Integer, DiagnosticModel.SeverityEnum> SPOTBUGS_PRIORITY_MAPPER = priority -> {
        if (priority == Priorities.HIGH_PRIORITY) {
            return DiagnosticModel.SeverityEnum.ERROR;
        } else if (priority == Priorities.NORMAL_PRIORITY) {
            return DiagnosticModel.SeverityEnum.WARNING;
        } else {
            return DiagnosticModel.SeverityEnum.INFO;
        }
    };
    private static final Function<Integer, DiagnosticModel.SeverityEnum> PMD_PRIORITY_MAPPER = priority -> {
        if (priority == RulePriority.HIGH.getPriority()) {
            return DiagnosticModel.SeverityEnum.ERROR;
        } else if (priority == RulePriority.MEDIUM.getPriority()) {
            return DiagnosticModel.SeverityEnum.WARNING;
        } else {
            return DiagnosticModel.SeverityEnum.INFO;
        }
    };

    @Override
    public void accept(SubmissionContext ctx) {
        Path workTree = ctx.getWorkTree();

        for (FileAnalysis fileAnalysis : ctx.getAnalysis()) {
            FileChangeModel fileChangeModel = new FileChangeModel();
            fileChangeModel.setDiff(fileAnalysis.getDiffText());
            if (ctx.getArgs().isHideSourceCode()) {
                fileChangeModel.setContentBefore(null);
                fileChangeModel.setContentAfter(null);
            } else {
                fileChangeModel.setContentBefore(fileAnalysis.getContentBefore());
                fileChangeModel.setContentAfter(fileAnalysis.getContentAfter());
            }
            fileChangeModel.setChangeType(FileChangeModel.ChangeTypeEnum.fromValue(fileAnalysis.getChangeType().name().toLowerCase()));
            fileChangeModel.setPreviousPath(fileAnalysis.getOldPath());
            fileChangeModel.setPath(fileAnalysis.getNewPath());
            fileChangeModel.setIsTest(fileAnalysis.isTestFile());
            if (Objects.nonNull(fileAnalysis.getLanguage())) {
                fileChangeModel.setLanguage(FileChangeModel.LanguageEnum.fromValue(fileAnalysis.getLanguage().getId()));
            }
            fileAnalysis.project().ifPresent(spec -> {
                fileChangeModel.setModule(spec.getId());
                ctx.getQualityTrackers().getUnchecked(spec.getId()).incrementFilesChanged();
            });

            for (AffectedSymbolInfo affectedSymbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                LocationModel locationModel = new LocationModel();
                locationModel.setStartLine(affectedSymbol.getLocation().getStartLine());
                locationModel.setStartColumn(affectedSymbol.getLocation().getStartColumn());
                locationModel.setEndLine(affectedSymbol.getLocation().getEndLine());
                locationModel.setEndColumn(affectedSymbol.getLocation().getEndColumn());

                affectedSymbol.block().ifPresent(block -> {
                    if (block instanceof JavaCodeBlockInfo) {
                        JavaCodeBlockInfo javaBlock = (JavaCodeBlockInfo) block;
                        CodeUnitModel codeUnitModel = createCodeUnitModel(ctx, fileAnalysis, affectedSymbol, javaBlock, locationModel, workTree);
                        fileChangeModel.getCodeUnits().add(codeUnitModel);
                    }
                });
            }

            if (MapUtils.isNotEmpty(fileAnalysis.getLineCoverage())) {
                fileChangeModel.setCoverage(buildCoverageModel(fileAnalysis.getLineCoverage()));
            }

            ctx.getSubmissionModel().getFiles().add(fileChangeModel);
        }
    }
    private static CodeUnitModel createCodeUnitModel(
            SubmissionContext ctx,
            FileAnalysis fileAnalysis,
            AffectedSymbolInfo affectedSymbol,
            JavaCodeBlockInfo javaBlock,
            LocationModel locationModel,
            Path workTree) {
        JavaInfoModel infoModel = new JavaInfoModel();
        infoModel.setIsAnonymous(javaBlock.getType() instanceof ASTAnonymousClassDeclaration);
        infoModel.setIsAbstract(javaBlock.isAbstract());
        infoModel.setIsFinal(javaBlock.isFinal());
        infoModel.setIsStatic(javaBlock.isStatic());
        infoModel.setIsSynchronized(javaBlock.isSynchronized());
        infoModel.setEnclosingClass(javaBlock.getEnclosingType().getSimpleName());
        if (Objects.nonNull(javaBlock.getType())) {
            infoModel.setPackageName(javaBlock.getType().getPackageName());
            infoModel.setClassName(javaBlock.getType().getSimpleName());
        }

        CodeUnitModel codeUnitModel = new CodeUnitModel();
        codeUnitModel.setName(affectedSymbol.getName());
        codeUnitModel.setBody(javaBlock.getBody());
        codeUnitModel.setLocation(locationModel);
        codeUnitModel.setModifiers(javaBlock.getModifiers());
        codeUnitModel.setSignature(javaBlock.getSignature());
        codeUnitModel.setSignatureFormat(SignatureFormatModel.JVM_DESCRIPTOR);
        codeUnitModel.setIsTrivial(javaBlock.isTrivial());
        codeUnitModel.setJavaInfo(infoModel);

        switch (fileAnalysis.getChangeType()) {
            case ADD:
            case COPY:
                codeUnitModel.setOperation(CodeUnitModel.OperationEnum.NEW);
                break;
            case DELETE:
                codeUnitModel.setOperation(CodeUnitModel.OperationEnum.DELETE);
                break;
            case MODIFY:
            case RENAME:
            default:
                codeUnitModel.setOperation(CodeUnitModel.OperationEnum.MODIFY);
                break;
        }

        populateSymbolInfo(ctx, affectedSymbol, codeUnitModel, infoModel, workTree, fileAnalysis);

        populateCoverage(javaBlock, codeUnitModel);
        populateMethodCalls(javaBlock, infoModel);
        populateDiagnostics(javaBlock, codeUnitModel, fileAnalysis.getNewPath());
        populateTypeInfo(javaBlock, codeUnitModel, infoModel);
        populateMetrics(ctx, fileAnalysis, javaBlock, codeUnitModel);
        populateAffectedCoverage(ctx, fileAnalysis, javaBlock);

        return codeUnitModel;
    }
    private static void populateSymbolInfo(
            SubmissionContext ctx,
            AffectedSymbolInfo lsp4jSymbol,
            CodeUnitModel codeUnitModel,
            JavaInfoModel infoModel,
            Path workTree,
            FileAnalysis fileAnalysis) {
        codeUnitModel.setKind(SymbolKindModel.fromValue(lsp4jSymbol.getKind().name().toLowerCase()));
        List<SymbolTag> symbolTags = lsp4jSymbol.getTags();
        if (CollectionUtils.isNotEmpty(symbolTags)) {
            for (SymbolTag tag : symbolTags) {
                if (tag == SymbolTag.Deprecated) {
                    infoModel.setIsDeprecated(true);
                }
            }
        }

        Map<String, String> fileContentCache = Maps.newHashMap();
        if (!ctx.getArgs().isHideSourceCode() && Objects.nonNull(fileAnalysis.getContentAfter())) {
            fileContentCache.put(fileAnalysis.getFile().getAbsolutePath(), fileAnalysis.getContentAfter());
        }

        for (CallHierarchyIncomingCall incomingCall : lsp4jSymbol.getIncomingCalls()) {
            CallHierarchyItem from = incomingCall.getFrom();
            if (Objects.isNull(from)) {
                continue;
            }

            CallerModel callerModel = new CallerModel();
            callerModel.setName(from.getName());
            callerModel.setSymbol(from.getDetail());
            if (StringUtils.isNotEmpty(from.getUri())) {
                try {
                    URI uri = new URI(from.getUri());
                    File file = new File(uri.toURL().getFile());
                    if (file.exists()) {
                        ctx.getArgs().owner(file).ifPresent(fileSpec -> {
                            callerModel.setIsTest(fileSpec.isTestResource(file));
                        });
                    }
                    callerModel.setPath(workTree.toRealPath().relativize(Paths.get(uri).toRealPath()).toString());

                    if (!ctx.getArgs().isHideSourceCode() && Objects.nonNull(from.getRange())) {
                        String callerFilePath = Paths.get(uri).toAbsolutePath().toString();
                        String fileContent = fileContentCache.get(callerFilePath);
                        if (Objects.isNull(fileContent)) {
                            fileContent = Files.readString(Paths.get(uri));
                            fileContentCache.put(callerFilePath, fileContent);
                        }
                        callerModel.setCallerBody(extractCallerLines(fileContent, from.getRange()));
                    }
                } catch (URISyntaxException | IOException err) {
                    ExceptionUtils.wrapAndThrow(err);
                }
            }

            if (Objects.nonNull(from.getKind())) {
                callerModel.setKind(SymbolKindModel.fromValue(from.getKind().name().toLowerCase()));
            }

            List<SymbolTag> callerTags = from.getTags();
            if (CollectionUtils.isNotEmpty(callerTags)) {
                for (SymbolTag tag : callerTags) {
                    if (tag == SymbolTag.Deprecated) {
                        callerModel.setIsDeprecated(true);
                    }
                }
            }

            if (Objects.nonNull(from.getRange())) {
                LocationModel callerLocation = new LocationModel();
                callerLocation.setStartLine(from.getRange().getStart().getLine() + SourceLocation.LSP_OFFSET);
                callerLocation.setStartColumn(from.getRange().getStart().getCharacter() + SourceLocation.LSP_OFFSET);
                callerLocation.setEndLine(from.getRange().getEnd().getLine() + SourceLocation.LSP_OFFSET);
                callerLocation.setEndColumn(from.getRange().getEnd().getCharacter() + SourceLocation.LSP_OFFSET);
                callerModel.setLocation(callerLocation);
            }

            List<Range> fromRanges = incomingCall.getFromRanges();
            if (CollectionUtils.isNotEmpty(fromRanges)) {
                for (Range range : fromRanges) {
                    LocationModel callSiteLocation = new LocationModel();
                    callSiteLocation.setStartLine(range.getStart().getLine() + SourceLocation.LSP_OFFSET);
                    callSiteLocation.setStartColumn(range.getStart().getCharacter() + SourceLocation.LSP_OFFSET);
                    callSiteLocation.setEndLine(range.getEnd().getLine() + SourceLocation.LSP_OFFSET);
                    callSiteLocation.setEndColumn(range.getEnd().getCharacter() + SourceLocation.LSP_OFFSET);
                    callerModel.getCallSites().add(callSiteLocation);
                }
            }

            codeUnitModel.getCallers().add(callerModel);
        }
    }
    private static void populateCoverage(JavaCodeBlockInfo javaBlock, CodeUnitModel codeUnitModel) {
        javaBlock.coverage().subscribe(cov -> {
            if (cov.hasCoverageData()) {
                codeUnitModel.setCoverage(buildCoverageModel(javaBlock.getLineCoverage()));
            }
        });
    }
    private static CoverageModel buildCoverageModel(Map<Integer, ILine> lineCoverageMap) {
        CodeBlockCoverage cov = CodeBlockCoverage.from(lineCoverageMap);

        CoverageModel coverageModel = new CoverageModel();
        coverageModel.setTool(CoverageModel.ToolEnum.JACOCO);
        coverageModel.setCoveredLines(cov.getCovered() + cov.getPartial());
        coverageModel.setMissedLines(cov.getMissed());
        coverageModel.setLinePercent(cov.lineCoveragePercent());

        if (cov.totalBranches() > 0) {
            coverageModel.setCoveredBranches(cov.getCoveredBranches());
            coverageModel.setMissedBranches(cov.getMissedBranches());
            coverageModel.setBranchPercent(cov.branchCoveragePercent());
        }

        for (Map.Entry<Integer, ILine> lineEntry : lineCoverageMap.entrySet()) {
            Integer lineNumber = lineEntry.getKey();
            ILine lineInfo = lineEntry.getValue();

            LineCoverageModel lineModel = new LineCoverageModel();
            lineModel.setLine(lineNumber);
            lineModel.setHits(lineInfo.getInstructionCounter().getCoveredCount());

            lineModel.setStatus(LineCoverageModel.StatusEnum.EMPTY);
            if (lineInfo.getStatus() == ICounter.EMPTY) {
                lineModel.setStatus(LineCoverageModel.StatusEnum.EMPTY);
            } else if (lineInfo.getStatus() == ICounter.NOT_COVERED) {
                lineModel.setStatus(LineCoverageModel.StatusEnum.MISSED);
            } else if (lineInfo.getStatus() == ICounter.PARTLY_COVERED) {
                lineModel.setStatus(LineCoverageModel.StatusEnum.PARTIAL);
            } else if (lineInfo.getStatus() == ICounter.FULLY_COVERED) {
                lineModel.setStatus(LineCoverageModel.StatusEnum.COVERED);
            }

            ICounter branchCtr = lineInfo.getBranchCounter();
            if (branchCtr.getTotalCount() > 0) {
                CoverageCounterModel branchModel = new CoverageCounterModel();
                branchModel.setCovered(branchCtr.getCoveredCount());
                branchModel.setMissed(branchCtr.getMissedCount());
                lineModel.setBranches(branchModel);
            }

            coverageModel.getLines().add(lineModel);
        }

        return coverageModel;
    }
    private static void populateMethodCalls(JavaCodeBlockInfo javaBlock, JavaInfoModel infoModel) {
        for (JInvocationBlock methodCall : javaBlock.getInvocations()) {
            InvocationModel methodCallModel = new InvocationModel();
            methodCallModel.setOwner(methodCall.getOwnerClass());
            methodCallModel.setName(methodCall.getName());
            methodCallModel.setDescriptor(methodCall.getMethodDescriptor());
            methodCallModel.setIsStatic(methodCall.isStatic());
            methodCallModel.setIsConstructor(methodCall.isConstructor());
            methodCallModel.setIsMethodReference(methodCall.isMethodReference());
            methodCallModel.setIsExplicitConstructor(methodCall.isExplicitConstructor());
            methodCallModel.setIsEnumConstant(methodCall.isEnumConstant());
            methodCallModel.setInvocationKind(resolveInvocationKind(methodCall));

            LocationModel callLocation = new LocationModel();
            callLocation.setStartLine(methodCall.getBeginLine());
            callLocation.setStartColumn(methodCall.getBeginColumn());
            callLocation.setEndLine(methodCall.getEndLine());
            callLocation.setEndColumn(methodCall.getEndColumn());
            methodCallModel.setLocation(callLocation);

            methodCall.artifact().ifPresent(artifact -> methodCallModel.setArtifact(artifact.getId()));

            infoModel.getInvocations().add(methodCallModel);
        }
    }
    private static void populateDiagnostics(JavaCodeBlockInfo javaBlock, CodeUnitModel codeUnitModel, String filePath) {
        for (BugInstance bug : javaBlock.getSpotbugs()) {
            DiagnosticModel diagnosticModel = new DiagnosticModel();
            diagnosticModel.setTool(DiagnosticModel.ToolEnum.SPOTBUGS);
            diagnosticModel.setRuleId(bug.getBugPattern().getType());
            diagnosticModel.setMessage(bug.getMessage());
            diagnosticModel.setCategory(bug.getBugPattern().getCategory());
            diagnosticModel.setFilePath(filePath);
            diagnosticModel.setSeverity(SPOTBUGS_PRIORITY_MAPPER.apply(bug.getPriority()));

            SpotbugsPropertiesModel spotbugsInfo = new SpotbugsPropertiesModel();
            spotbugsInfo.setBugCategory(bug.getBugPattern().getCategory());
            spotbugsInfo.setBugRank(bug.getBugRank());
            spotbugsInfo.setConfidence(resolveSpotbugsConfidence(bug.getPriority()));
            MethodAnnotation primaryMethod = bug.getPrimaryMethod();
            if (Objects.nonNull(primaryMethod)) {
                spotbugsInfo.setMethodSignature(primaryMethod.getFullMethod(bug.getPrimaryClass()));
            }
            diagnosticModel.setSpotbugsInfo(spotbugsInfo);

            Optional.ofNullable(bug.getPrimarySourceLineAnnotation()).ifPresent(srcLine -> {
                LocationModel diagLocation = new LocationModel();
                diagLocation.setStartLine(srcLine.getStartLine());
                diagLocation.setEndLine(srcLine.getEndLine());
                diagnosticModel.setLocation(diagLocation);
            });

            int cweid = bug.getBugPattern().getCWEid();
            if (cweid > 0) {
                diagnosticModel.setCwe(com.google.common.collect.Lists.newArrayList("CWE-" + cweid));
            }

            codeUnitModel.getDiagnostics().add(diagnosticModel);
        }

        for (RuleViolation violation : javaBlock.getPmdViolations()) {
            DiagnosticModel diagnosticModel = new DiagnosticModel();
            diagnosticModel.setTool(DiagnosticModel.ToolEnum.PMD);
            diagnosticModel.setRuleId(violation.getRule().getName());
            diagnosticModel.setMessage(violation.getDescription());
            diagnosticModel.setCategory(violation.getRule().getRuleSetName());
            diagnosticModel.setFilePath(filePath);
            diagnosticModel.setSeverity(PMD_PRIORITY_MAPPER.apply(violation.getRule().getPriority().getPriority()));

            PmdPropertiesModel pmdInfo = new PmdPropertiesModel();
            pmdInfo.setRuleSet(violation.getRule().getRuleSetName());
            pmdInfo.setPriority(violation.getRule().getPriority().getPriority());
            Optional.ofNullable(violation.getRule().getExternalInfoUrl()).ifPresent(url -> {
                if (StringUtils.isNotEmpty(url)) {
                    pmdInfo.setExternalInfoUrl(URI.create(url));
                }
            });
            diagnosticModel.setPmdInfo(pmdInfo);

            LocationModel diagLocation = new LocationModel();
            diagLocation.setStartLine(violation.getBeginLine());
            diagLocation.setStartColumn(violation.getBeginColumn());
            diagLocation.setEndLine(violation.getEndLine());
            diagLocation.setEndColumn(violation.getEndColumn());
            diagnosticModel.setLocation(diagLocation);

            codeUnitModel.getDiagnostics().add(diagnosticModel);
        }
    }
    private static void populateTypeInfo(JavaCodeBlockInfo javaBlock, CodeUnitModel codeUnitModel, JavaInfoModel infoModel) {
        if (javaBlock instanceof JavaConstructorBlockInfo) {
            ASTConstructorDeclaration constructor = ((JavaConstructorBlockInfo) javaBlock).getConstructor();
            ASTThrowsList throwsList = constructor.getThrowsList();
            ASTTypeParameters typeParameters = constructor.getTypeParameters();

            codeUnitModel.setKind(SymbolKindModel.CONSTRUCTOR);
            Optional.ofNullable(typeParameters).ifPresent(t -> t.forEach(tp -> infoModel.getTypeParameters().add(tp.getName())));
            Optional.ofNullable(throwsList).ifPresent(l -> l.forEach(tt -> infoModel.getThrowsTypes().add(tt.getSimpleName())));
            populateAnnotations(constructor, infoModel);

        } else if (javaBlock instanceof JavaMethodBlockInfo) {
            ASTMethodDeclaration method = ((JavaMethodBlockInfo) javaBlock).getMethod();
            ASTTypeParameters typeParameters = method.getTypeParameters();
            ASTThrowsList throwsList = method.getThrowsList();

            codeUnitModel.setKind(SymbolKindModel.METHOD);
            Optional.ofNullable(typeParameters).ifPresent(t -> t.forEach(tp -> infoModel.getTypeParameters().add(tp.getName())));
            Optional.ofNullable(throwsList).ifPresent(l -> l.forEach(tt -> infoModel.getThrowsTypes().add(tt.getSimpleName())));
            populateAnnotations(method, infoModel);
        }
    }
    private static void populateMetrics(SubmissionContext ctx, FileAnalysis fileAnalysis, JavaCodeBlockInfo javaBlock, CodeUnitModel codeUnitModel) {
        javaBlock.metrics().subscribe(metrics -> {
            MetricsModel metricsModel = new MetricsModel();
            metricsModel.setCyclomaticComplexity(metrics.cyclo());
            metricsModel.setCognitiveComplexity(metrics.cognitive());
            metricsModel.setLinesOfCode(metrics.lineCount());
            metricsModel.setLogicalLinesOfCode(metrics.ncss());
            metricsModel.setFanOut(metrics.fanOut());
            metricsModel.setNpath(metrics.npath());

            if (javaBlock instanceof JavaMethodBlockInfo) {
                metricsModel.setParameterCount(((JavaMethodBlockInfo) javaBlock).getMethod().getArity());
            } else if (javaBlock instanceof JavaConstructorBlockInfo) {
                metricsModel.setParameterCount(((JavaConstructorBlockInfo) javaBlock).getConstructor().getArity());
            }

            codeUnitModel.setMetrics(metricsModel);

            fileAnalysis.project().ifPresent(spec -> {
                ModuleQualityTracker tracker = ctx.getQualityTrackers().getUnchecked(spec.getId());
                tracker.incrementCodeUnits();
                tracker.addLines(metrics.ncss());
                tracker.addComplexity(metrics.cyclo());
                tracker.addPmdViolations(javaBlock.getPmdViolations().size());
                tracker.addSpotbugsIssues(javaBlock.getSpotbugs().size());
            });
        });
    }
    private static void populateAffectedCoverage(SubmissionContext ctx, FileAnalysis fileAnalysis, JavaCodeBlockInfo javaBlock) {
        javaBlock.coverage().subscribe(cov -> {
            if (cov.hasCoverageData()) {
                fileAnalysis.project().ifPresent(spec -> {
                    ModuleQualityTracker tracker = ctx.getQualityTrackers().getUnchecked(spec.getId());
                    tracker.addCoverage(cov.lineCoveragePercent());
                });
            }
        });
    }
    private static void populateAnnotations(Annotatable declaration, JavaInfoModel infoModel) {
        for (ASTAnnotation annotation : declaration.getDeclaredAnnotations()) {
            JavaAnnotationModel annotationModel = new JavaAnnotationModel();
            annotationModel.setName(annotation.getSimpleName());
            annotationModel.setQualifiedName(annotation.getTypeMirror().getSymbol().getBinaryName());
            infoModel.getAnnotations().add(annotationModel);
        }
    }
    private static InvocationModel.InvocationKindEnum resolveInvocationKind(JInvocationBlock call) {
        if (call.isConstructor()) {
            return InvocationModel.InvocationKindEnum.INVOKESPECIAL;
        }
        if (call.isMethodReference()) {
            return InvocationModel.InvocationKindEnum.REFERENCE;
        }
        if (call.isStatic()) {
            return InvocationModel.InvocationKindEnum.INVOKESTATIC;
        }
        if (call.isInterfaceCall()) {
            return InvocationModel.InvocationKindEnum.INVOKEINTERFACE;
        }
        return InvocationModel.InvocationKindEnum.INVOKEVIRTUAL;
    }
    private static SpotbugsPropertiesModel.ConfidenceEnum resolveSpotbugsConfidence(int priority) {
        if (priority == Priorities.HIGH_PRIORITY) {
            return SpotbugsPropertiesModel.ConfidenceEnum.HIGH;
        } else if (priority == Priorities.NORMAL_PRIORITY) {
            return SpotbugsPropertiesModel.ConfidenceEnum.MEDIUM;
        } else {
            return SpotbugsPropertiesModel.ConfidenceEnum.LOW;
        }
    }
    private static String extractCallerLines(String content, Range range) {
        String[] lines = content.split("\n", -1);
        int startLine = range.getStart().getLine();
        int endLine = Math.min(range.getEnd().getLine(), lines.length - 1);
        if (startLine >= lines.length) {
            return null;
        }
        String body = String.join("\n", Arrays.copyOfRange(lines, startLine, endLine + 1));
        // JDT LS includes javadoc in CallHierarchyItem.range; strip it so only
        // the method/constructor body is stored.
        return body.replaceFirst("(?s)^\\s*/\\*\\*.*?\\*/\\s*", "");
    }
}
