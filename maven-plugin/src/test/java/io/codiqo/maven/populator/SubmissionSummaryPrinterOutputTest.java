package io.codiqo.maven.populator;

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
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.submit.ModuleQualityTracker;
import io.codiqo.submit.SampleMaxTracker;
import io.codiqo.submit.SubmissionContext;

/** Pins every line the calibration summary logs, byte for byte and one log call per line. */
class SubmissionSummaryPrinterOutputTest {
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
    private static String print(SubmissionContext ctx) {
        RecordingLog log = new RecordingLog();
        new SubmissionSummaryPrinter(log).accept(ctx);
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

        FileChangeModel main = file("core/src/main/java/com/example/HttpChannel.java", ChangeTypeEnum.MODIFY, false);
        main.setCodeUnits(Lists.newArrayList(List.of(
                unit("initChannel(SocketChannel)", SymbolKindModel.METHOD, OperationEnum.NEW, false, metrics(30, 12, 15)),
                unit("handle(Request,   Response)", SymbolKindModel.METHOD, OperationEnum.MODIFY, false, metrics(22, 8, 4), 9, 3),
                unit("HttpChannel()", SymbolKindModel.CONSTRUCTOR, OperationEnum.NEW, false, metrics(3, 2, 1)),
                unit("HttpChannel(int, java.util.Map<String, Object>)", SymbolKindModel.CONSTRUCTOR, OperationEnum.NEW, false, metrics(10, 1, 9)),
                unit("dense()", SymbolKindModel.METHOD, OperationEnum.NEW, false, metrics(10, 9, 1)),
                unit("getName()", SymbolKindModel.METHOD, OperationEnum.NEW, true, metrics(1, 1, 0)),
                unit("removed()", SymbolKindModel.METHOD, OperationEnum.DELETE, false, metrics(5, 2, 1)),
                unit("Inner", SymbolKindModel.FIELD, OperationEnum.NEW, false, metrics(40, 20, 9)),
                unit("noMetrics()", SymbolKindModel.METHOD, OperationEnum.NEW, false, null))));

        FileChangeModel test = file("core/src/test/java/com/example/HttpChannelTest.java", ChangeTypeEnum.ADD, true);
        test.setCodeUnits(Lists.newArrayList(List.of(
                unit("routesEveryRequest()", SymbolKindModel.METHOD, OperationEnum.NEW, false, metrics(14, 3, 11)),
                unit("chatty()", SymbolKindModel.METHOD, OperationEnum.NEW, false, metrics(4, 1, 9)),
                unit("setUp()", SymbolKindModel.METHOD, OperationEnum.NEW, true, metrics(2, 2, 2)))));

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
    private static CodeUnitModel unit(String name, SymbolKindModel kind, OperationEnum operation, boolean trivial, MetricsModel metrics) {
        CodeUnitModel toReturn = new CodeUnitModel();
        toReturn.setName(name);
        toReturn.setKind(kind);
        toReturn.setOperation(operation);
        toReturn.setIsTrivial(trivial);
        toReturn.setMetrics(metrics);
        return toReturn;
    }
    private static CodeUnitModel unit(String name, SymbolKindModel kind, OperationEnum operation, boolean trivial, MetricsModel metrics,
            int effectiveLinesChanged, int effectiveInvocationsChanged) {
        CodeUnitModel toReturn = unit(name, kind, operation, trivial, metrics);
        toReturn.setEffectiveLinesChanged(effectiveLinesChanged);
        toReturn.setEffectiveInvocationsChanged(effectiveInvocationsChanged);
        return toReturn;
    }
    private static MetricsModel metrics(int lines, int ncss, int invocations) {
        MetricsModel toReturn = new MetricsModel();
        toReturn.setNonCommentCodeLines(lines);
        toReturn.setNonCommentCodeStatements(ncss);
        toReturn.setDirectInvocationCount(invocations);
        return toReturn;
    }

    private static final class RecordingLog extends SystemStreamLog {
        private final List<String> lines = Lists.newArrayList();

        @Override
        public void info(CharSequence content) {
            lines.add(String.valueOf(content));
        }
    }
}
