package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.core.java.JavaBinaryFormat;
import io.codiqo.core.java.JavaLanguageSpec;
import io.codiqo.core.java.JavaReviewLanguage;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import io.codiqo.llm.review.LocalReview;
import io.codiqo.llm.review.LocalReviewModels;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.CodeBlockCategory;
import io.codiqo.util.Fetch;

/**
 * The review's labels against units the Java index really produces: the descriptors and names come from parsing source
 * with {@link JavaLanguageSpec}, as a submission's do, and the labels are written the way the naming rule of
 * {@link JavaReviewLanguage} tells reviewers to. A test double with made-up descriptors cannot show that an anonymous
 * class is {@code Totals$1}, a local class {@code Totals$1Local}, or that PMD prints an unresolved type as
 * {@code *Unknown}, which is where matching went wrong before.
 */
class ReviewLabelsOnJavaDescriptorsTest {
    private static final String TOTALS = """
            package com.example;
            public class Totals implements Runnable {
                @Override
                public void run() {
                    System.out.println("outer");
                }
                public void schedule() {
                    Runnable later = new Runnable() {
                        @Override
                        public void run() {
                            System.out.println("anonymous");
                        }
                    };
                    later.run();
                }
                public void local() {
                    class Local {
                        void report() {
                            System.out.println("local");
                        }
                    }
                    new Local().report();
                }
                public void report() {
                    System.out.println("outer report");
                }
                void pick(Unknown unknown) {
                    System.out.println(unknown);
                }
                static class Request {
                    static class Builder {
                        Builder(String name) {
                            System.out.println(name);
                        }
                        Object build() {
                            return "request";
                        }
                    }
                }
                static class Response {
                    static class Builder {
                        Object build() {
                            return "response";
                        }
                    }
                }
            }
            """;
    private static final String OP = """
            package com.example;
            public enum Op {
                ADD {
                    @Override
                    int apply(int a, int b) {
                        return a + b;
                    }
                },
                SUB {
                    @Override
                    int apply(int a, int b) {
                        return a - b;
                    }
                };
                abstract int apply(int a, int b);
            }
            """;
    private static final String WATCHDOG = """
            package com.example;
            public class Watchdog {
                static class Task {
                    void report() {
                        System.out.println("moved here");
                    }
                }
            }
            """;
    private static final String TOTALS_PATH = "src/main/java/com/example/Totals.java";
    private static final String OP_PATH = "src/main/java/com/example/Op.java";
    private static final String WATCHDOG_PATH = "src/main/java/com/example/Watchdog.java";

    @TempDir
    Path workTree;

    /** the file each indexed descriptor came from */
    private final Map<String, String> paths = Maps.newHashMap();

    @Test
    void everyLabelFindsTheUnitItNames() throws Exception {
        Map<String, CodeUnitModel> units = index(Map.of(TOTALS_PATH, TOTALS, OP_PATH, OP));
        AnalysisSubmissionModel submission = submission(units);

        LocalReviewModels.Labelling labelling = LocalReviewModels.applyBlockCategories(submission, review(
                label(TOTALS_PATH, "Totals.run()", CodeBlockCategory.INTRICATE),
                label(TOTALS_PATH, "Totals.Runnable.run()", CodeBlockCategory.MECHANICAL),
                label(TOTALS_PATH, "Totals.Local.report()", CodeBlockCategory.SUBSTANTIVE),
                label(TOTALS_PATH, "Totals.report()", CodeBlockCategory.ROUTINE),
                label(TOTALS_PATH, "void Totals.pick(Unknown)", CodeBlockCategory.SUBSTANTIVE),
                label(TOTALS_PATH, "Totals.Request.Builder(String)", CodeBlockCategory.INTRICATE),
                label(TOTALS_PATH, "Totals.Request.Builder.build()", CodeBlockCategory.ROUTINE),
                label(TOTALS_PATH, "Totals.Response.Builder.build()", CodeBlockCategory.INTRICATE),
                label(OP_PATH, "Op.apply(int, int)", CodeBlockCategory.ROUTINE)), List.of(new JavaReviewLanguage()));

        assertEquals(List.of(), labelling.getUnmatched());
        assertEquals(CodeUnitModel.CategoryEnum.INTRICATE, units.get("com/example/Totals.run()V").getCategory());
        assertEquals(CodeUnitModel.CategoryEnum.MECHANICAL, units.get("com/example/Totals$1.run()V").getCategory());
        assertEquals(CodeUnitModel.CategoryEnum.SUBSTANTIVE, units.get("com/example/Totals$1Local.report()V").getCategory());
        assertEquals(CodeUnitModel.CategoryEnum.ROUTINE, units.get("com/example/Totals.report()V").getCategory());
        assertEquals(CodeUnitModel.CategoryEnum.SUBSTANTIVE, units.get("com/example/Totals.pick(LUnknown;)V").getCategory(), "an unresolved parameter type");
        assertEquals(CodeUnitModel.CategoryEnum.INTRICATE, units.get("com/example/Totals$Request$Builder.<init>(Ljava/lang/String;)V").getCategory());
        assertEquals(CodeUnitModel.CategoryEnum.ROUTINE, units.get("com/example/Totals$Request$Builder.build()Ljava/lang/Object;").getCategory());
        assertEquals(CodeUnitModel.CategoryEnum.INTRICATE, units.get("com/example/Totals$Response$Builder.build()Ljava/lang/Object;").getCategory());
        assertEquals(CodeUnitModel.CategoryEnum.ROUTINE, units.get("com/example/Op$1.apply(II)I").getCategory(), "the constants' bodies share the one label");
        assertEquals(CodeUnitModel.CategoryEnum.ROUTINE, units.get("com/example/Op$2.apply(II)I").getCategory());
        assertEquals(10, labelling.getApplied());
    }
    /** the compiler numbers constant bodies in source order, so labels written in that order reach their own constant */
    @Test
    void eachConstantsLabelReachesItsOwnBody() throws Exception {
        Map<String, CodeUnitModel> units = index(Map.of(OP_PATH, OP));

        LocalReviewModels.Labelling labelling = LocalReviewModels.applyBlockCategories(submission(units), review(
                label(OP_PATH, "Op.ADD.apply(int, int)", CodeBlockCategory.ROUTINE),
                label(OP_PATH, "Op.SUB.apply(int, int)", CodeBlockCategory.INTRICATE)), List.of(new JavaReviewLanguage()));

        assertEquals(List.of(), labelling.getUnmatched());
        assertEquals(CodeUnitModel.CategoryEnum.ROUTINE, units.get("com/example/Op$1.apply(II)I").getCategory());
        assertEquals(CodeUnitModel.CategoryEnum.INTRICATE, units.get("com/example/Op$2.apply(II)I").getCategory());
    }
    /** a label naming the unit exactly wins over one that only had the member to go by, whichever came first */
    @Test
    void anExactLabelIsNotDisplacedByAGuess() throws Exception {
        Map<String, CodeUnitModel> units = index(Map.of(WATCHDOG_PATH, WATCHDOG));

        LocalReviewModels.Labelling labelling = LocalReviewModels.applyBlockCategories(submission(units), review(
                label(WATCHDOG_PATH, "Watchdog.report()", CodeBlockCategory.MECHANICAL),
                label(WATCHDOG_PATH, "Watchdog.Task.report()", CodeBlockCategory.SUBSTANTIVE)), List.of(new JavaReviewLanguage()));

        assertEquals(CodeUnitModel.CategoryEnum.SUBSTANTIVE, units.get("com/example/Watchdog$Task.report()V").getCategory());
        assertEquals(List.of(WATCHDOG_PATH + "#Watchdog.report()"), labelling.getUnmatched());
    }
    /** the submission's units for these sources, by descriptor, named as {@code FileAnalysisPopulator} names them */
    private Map<String, CodeUnitModel> index(Map<String, String> sources) throws Exception {
        Map<String, CodeUnitModel> toReturn = Maps.newLinkedHashMap();
        RunArgs args = new RunArgs();
        try (JavaLanguageSpec spec = new JavaLanguageSpec(StubAnalysis.LOGS, args, new Fetch(args))) {
            for (Map.Entry<String, String> source : sources.entrySet()) {
                Path file = workTree.resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                for (CodeBlockInfo block : spec.parse(mock(ProjectSpec.class), List.of(file.toFile())).getBlocks()) {
                    toReturn.put(block.getSignature(), new CodeUnitModel()
                            .name(JavaBinaryFormat.toDisplayName(((JavaCodeBlockInfo) block).getGenericSignature(), false))
                            .signature(block.getSignature())
                            .operation(CodeUnitModel.OperationEnum.NEW));
                    paths.put(block.getSignature(), source.getKey());
                }
            }
        }
        return toReturn;
    }
    private AnalysisSubmissionModel submission(Map<String, CodeUnitModel> units) {
        Map<String, List<CodeUnitModel>> byFile = Maps.newLinkedHashMap();
        units.forEach((signature, unit) -> byFile.computeIfAbsent(paths.get(signature), file -> Lists.newArrayList()).add(unit));

        AnalysisSubmissionModel toReturn = new AnalysisSubmissionModel();
        byFile.forEach((path, fileUnits) -> toReturn.addFilesItem(new FileChangeModel().path(path).codeUnits(fileUnits)));
        return toReturn;
    }
    private static LocalReview review(LlmScoringResponse.CodeBlockCategoryView... labels) {
        LlmScoringResponse assessment = new LlmScoringResponse();
        assessment.setBlockCategories(List.of(labels));
        return new LocalReview("abc", new LlmScoringResponse.Bugs(), List.of(), Duration.ofSeconds(1), "{}", assessment, 0, List.of(), List.of());
    }
    private static LlmScoringResponse.CodeBlockCategoryView label(String path, String signature, CodeBlockCategory category) {
        return LlmScoringResponse.CodeBlockCategoryView.builder().file(path).signature(signature).category(category).reason("because").build();
    }
}
