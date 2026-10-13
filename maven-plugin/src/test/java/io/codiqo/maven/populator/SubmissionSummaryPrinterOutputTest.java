package io.codiqo.maven.populator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.api.metrics.DriverScaler.Sample;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CodeUnitModel.OperationEnum;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FileChangeModel.ChangeTypeEnum;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.submit.ModuleQualityTracker;
import io.codiqo.submit.SampleMaxTracker;
import io.codiqo.submit.SubmissionContext;

/** Pins every line the calibration summary logs, byte for byte and one log call per line. */
class SubmissionSummaryPrinterOutputTest {
    private static final int SIGNATURE_LINES = 2;

    @Test
    void fullSubmissionLogsAsPinned() throws Exception {
        GoldenOutput.assertMatches("submission-summary-full.txt", print(fullContext()));
    }
    @Test
    void emptySubmissionLogsAsPinned() throws Exception {
        SubmissionContext ctx = SubmissionContext.create(new RunArgs(), null, null, null, null, "empty-project", "Empty", null);
        ctx.getSubmissionModel().setProject(ctx.getProjectModel());
        ctx.getProjectModel().setModules(Lists.newArrayList());

        GoldenOutput.assertMatches("submission-summary-empty.txt", print(ctx));
    }
    /**
     * A code unit without metrics cannot be mapped the way the server maps it. The summary still prints, without the
     * block table and with the header saying why, and the cause is logged as a warning instead of failing the analysis.
     */
    @Test
    void unmappableBlocksLeaveTheSummaryAndAWarning() throws Exception {
        SubmissionContext ctx = fullContext();
        ctx.getSubmissionModel().getFiles().iterator().next().getCodeUnits()
                .add(unit("noMetrics()", SymbolKindModel.METHOD, OperationEnum.NEW, false, null, location(200, 201, 210)));
        RecordingLog log = new RecordingLog();

        new SubmissionSummaryPrinter(log).accept(ctx);

        GoldenOutput.assertMatches("submission-summary-unmappable.txt", Joiner.on(StringUtils.LF).join(log.lines) + StringUtils.LF);
        assertEquals(1, log.warnings.size());
        assertTrue(log.warnings.iterator().next().startsWith("calibration summary: the changed code blocks could not be mapped"));
    }
    private static String print(SubmissionContext ctx) {
        RecordingLog log = new RecordingLog();
        new SubmissionSummaryPrinter(log).accept(ctx);
        assertTrue(log.warnings.isEmpty(), "a mappable submission logs no warning: " + log.warnings);
        return Joiner.on(StringUtils.LF).join(log.lines) + StringUtils.LF;
    }
    private static SubmissionContext fullContext() {
        SubmissionContext ctx = SubmissionContext.create(new RunArgs(), null, null, null, null, "example-service", "Example", null);
        ProjectModel project = ctx.getProjectModel();
        ModuleModel core = new ModuleModel();
        core.setId("core");
        ModuleModel web = new ModuleModel();
        web.setId("web");
        project.setModules(Lists.newArrayList(List.of(core, web)));
        ctx.getSubmissionModel().setProject(project);

        ModuleQualityTracker tracker = ctx.trackerFor("core");
        tracker.incrementTrivialMethod(false);
        tracker.incrementTrivialMethod(false);
        tracker.incrementTrivialMethod(true);
        tracker.incrementTrivialConstructor(false);

        CommitModel commit = new CommitModel();
        commit.setSha("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b");
        commit.setAuthor("Jane Developer");
        ctx.getSubmissionModel().setCommit(commit);

        ctx.setMethodScalerProd(DriverScaler.of(List.of(new Sample(10, 5, 3), new Sample(20, 9, 7), new Sample(4, 2, 1), new Sample(33, 12, 15))));
        ctx.setMethodScalerTest(DriverScaler.of(List.of(new Sample(8, 4, 6), new Sample(16, 6, 9))));
        ctx.setConstructorScalerProd(DriverScaler.of(List.of(new Sample(3, 2, 1))));
        ctx.setMethodCapQuantileProd(31);
        ctx.setMethodCapQuantileTest(15);
        ctx.setConstructorCapQuantileProd(3);

        SampleMaxTracker methodMax = new SampleMaxTracker();
        methodMax.update("HttpChannelInitializer", "initChannel(io.netty.channel.socket.SocketChannel channel, java.util.Map<String, java.util.List<Integer>> options)", new Sample(33, 12, 15));
        methodMax.update("VeryLongGeneratedClassNameWithoutAnySpacesThatKeepsGoingAndGoingPastTheColumnLimitImpl", "run()", new Sample(40, 3, 2));
        ctx.setMethodMaxProd(methodMax);

        /**
         * handle() is pre-populated with 9 effective added lines, and the diff deletes 3 more lines anchored in its body:
         * the server scores it on 12 changed lines, which the summary must print. Every block's L is its body line
         * count, two lines below its signature-inclusive nonCommentCodeLines.
         */
        FileChangeModel main = file("core/src/main/java/com/example/HttpChannel.java", ChangeTypeEnum.MODIFY, false, Joiner.on(StringUtils.LF).join(
                "--- a/core/src/main/java/com/example/HttpChannel.java",
                "+++ b/core/src/main/java/com/example/HttpChannel.java",
                "@@ -24,5 +24,5 @@",
                "         context();",
                "-        int a = compute(1);",
                "-        int b = compute(2);",
                "-        int c = compute(3);",
                "+        int a = compute(10);",
                "+        int b = compute(20);",
                "+        int c = compute(30);",
                "         done();"));
        main.setCodeUnits(Lists.newArrayList(List.of(
                unit("initChannel(SocketChannel)", SymbolKindModel.METHOD, OperationEnum.NEW, false, metrics(30, 12, 15), location(50, 52, 85)),
                unit("handle(Request,   Response)", SymbolKindModel.METHOD, OperationEnum.MODIFY, false, metrics(24, 8, 4), location(18, 20, 45), 9, 3),
                unit("HttpChannel()", SymbolKindModel.CONSTRUCTOR, OperationEnum.NEW, false, metrics(5, 2, 1), location(3, 4, 6)),
                unit("HttpChannel(int, java.util.Map<String, Object>)", SymbolKindModel.CONSTRUCTOR, OperationEnum.NEW, false, metrics(12, 1, 9), location(7, 8, 17)),
                unit("dense()", SymbolKindModel.METHOD, OperationEnum.NEW, false, metrics(12, 9, 1), location(88, 89, 100)),
                unit("getName()", SymbolKindModel.METHOD, OperationEnum.NEW, true, metrics(3, 1, 0), location(101, 101, 103)),
                unit("removed()", SymbolKindModel.METHOD, OperationEnum.DELETE, false, metrics(5, 2, 1), location(110, 111, 115)),
                unit("Inner", SymbolKindModel.FIELD, OperationEnum.NEW, false, metrics(40, 20, 9), location(120, 120, 160)))));

        FileChangeModel test = file("core/src/test/java/com/example/HttpChannelTest.java", ChangeTypeEnum.ADD, true, Joiner.on(StringUtils.LF).join(
                "--- /dev/null",
                "+++ b/core/src/test/java/com/example/HttpChannelTest.java",
                "@@ -0,0 +1,3 @@",
                "+class HttpChannelTest {",
                "+    void routesEveryRequest() { channel.route(request); }",
                "+}"));
        test.setCodeUnits(Lists.newArrayList(List.of(
                unit("routesEveryRequest()", SymbolKindModel.METHOD, OperationEnum.NEW, false, metrics(16, 3, 11), location(2, 3, 18)),
                unit("chatty()", SymbolKindModel.METHOD, OperationEnum.NEW, false, metrics(6, 1, 9), location(19, 20, 25)),
                unit("HttpChannelTest()", SymbolKindModel.CONSTRUCTOR, OperationEnum.NEW, false, metrics(8, 3, 2), location(31, 32, 38)),
                unit("setUp()", SymbolKindModel.METHOD, OperationEnum.NEW, true, metrics(4, 2, 2), location(26, 27, 30)))));

        ctx.getSubmissionModel().setFiles(Lists.newArrayList(List.of(
                main,
                test,
                file("README", ChangeTypeEnum.DELETE, false),
                file("docs/old.md", ChangeTypeEnum.RENAME, false),
                file("docs/copy.md", ChangeTypeEnum.COPY, false))));
        return ctx;
    }
    private static FileChangeModel file(String path, ChangeTypeEnum changeType, boolean isTest) {
        FileChangeModel toReturn = new FileChangeModel();
        toReturn.setPath(path);
        toReturn.setChangeType(changeType);
        toReturn.setIsTest(isTest);
        return toReturn;
    }
    private static FileChangeModel file(String path, ChangeTypeEnum changeType, boolean isTest, String diff) {
        FileChangeModel toReturn = file(path, changeType, isTest);
        toReturn.setDiff(diff);
        return toReturn;
    }
    private static CodeUnitModel unit(String name, SymbolKindModel kind, OperationEnum operation, boolean trivial, MetricsModel metrics,
            LocationModel location) {
        CodeUnitModel toReturn = new CodeUnitModel();
        toReturn.setName(name);
        toReturn.setSignature(name);
        toReturn.setKind(kind);
        toReturn.setOperation(operation);
        toReturn.setIsTrivial(trivial);
        toReturn.setMetrics(metrics);
        toReturn.setLocation(location);
        return toReturn;
    }
    private static CodeUnitModel unit(String name, SymbolKindModel kind, OperationEnum operation, boolean trivial, MetricsModel metrics,
            LocationModel location, int effectiveLinesChanged, int effectiveInvocationsChanged) {
        CodeUnitModel toReturn = unit(name, kind, operation, trivial, metrics, location);
        toReturn.setEffectiveLinesChanged(effectiveLinesChanged);
        toReturn.setEffectiveInvocationsChanged(effectiveInvocationsChanged);
        return toReturn;
    }
    /** the body is two lines shorter than the whole declaration, so a summary reading the wrong count shows it */
    private static MetricsModel metrics(int nonCommentCodeLines, int ncss, int invocations) {
        MetricsModel toReturn = new MetricsModel();
        toReturn.setNonCommentCodeLines(nonCommentCodeLines);
        toReturn.setBodyCodeLines(nonCommentCodeLines - SIGNATURE_LINES);
        toReturn.setNonCommentCodeStatements(ncss);
        toReturn.setDirectInvocationCount(invocations);
        return toReturn;
    }
    private static LocationModel location(int startLine, int bodyStartLine, int endLine) {
        LocationModel toReturn = new LocationModel();
        toReturn.setStartLine(startLine);
        toReturn.setEndLine(endLine);
        toReturn.setBodyStartLine(bodyStartLine);
        toReturn.setBodyEndLine(endLine);
        return toReturn;
    }

    private static final class RecordingLog extends SystemStreamLog {
        private final List<String> lines = Lists.newArrayList();
        private final List<String> warnings = Lists.newArrayList();

        @Override
        public void info(CharSequence content) {
            lines.add(String.valueOf(content));
        }
        @Override
        public void warn(CharSequence content, Throwable error) {
            warnings.add(String.valueOf(content));
        }
    }
}
