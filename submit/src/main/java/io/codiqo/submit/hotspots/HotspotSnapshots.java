package io.codiqo.submit.hotspots;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.time.StopWatch;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.DeclaredType;
import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.cpd.CloneLocations;
import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiClient;
import io.codiqo.client.api.HotspotsApi;
import io.codiqo.client.model.HotspotClassModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.client.model.HotspotTypeKind;
import io.codiqo.submit.ApiRetry;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.hotspots.GitChurn.FileChurn;
import io.codiqo.submit.hotspots.HotspotRanker.RankedType;
import io.codiqo.util.MemoryReport;
import lombok.experimental.UtilityClass;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read off the analysis that just ran plus one git-history walk, so a snapshot costs seconds, not another build.
 */
@UtilityClass
public class HotspotSnapshots {
    private static final String API_KEY_HEADER = "X-API-Key";

    /**
     * A shallow clone is skipped rather than ranked: file churn is measured from git history, and with that history
     * missing every class would look quiet, so the hotspot ranking would be meaningless.
     */
    public Optional<HotspotSnapshotModel> build(SubmissionContext ctx, Log log) throws IOException {
        RunArgs args = ctx.getArgs();
        Optional<HotspotSnapshotModel> toReturn = Optional.empty();

        if (args.isHotspotsEnabled()) {
            if (args.isHotspotsCommit()) {
                Repository repo = args.getGit();
                if (isShallow(repo)) {
                    log.warn("hotspots skipped: the repository is a shallow clone, so file churn cannot be measured — re-run with full history (fetch-depth: 0)");
                } else {
                    toReturn = Optional.of(snapshot(ctx, repo, log));
                }
            } else {
                log.info("hotspots skipped for %s: the snapshot is built for %s, the commit the run started on", args.getCommitId(), args.getHotspotsCommitId());
            }
        }
        return toReturn;
    }
    public void submit(String apiUrl, String apiKey, long connectTimeoutSeconds, long readTimeoutSeconds, String projectId, HotspotSnapshotModel snapshot, Log log) throws Exception {
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(Strings.CS.removeEnd(apiUrl, "/"));
        apiClient.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        apiClient.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        apiClient.setRequestInterceptor(builder -> builder.header(API_KEY_HEADER, apiKey));

        HotspotsApi client = new HotspotsApi(apiClient);
        ApiRetry.call(log, "submitHotspots", apiUrl, () -> {
            client.submitHotspots(projectId, snapshot);
            return null;
        });
        log.info("submitted hotspot snapshot for %s: %d classes", snapshot.getCommitSha(), snapshot.getClasses().size());
    }
    public void write(SubmissionContext ctx, HotspotSnapshotModel snapshot, Log log) throws IOException {
        File outputDir = ctx.getArgs().getOutputDirectory();
        if (BooleanUtils.and(new boolean[] { ctx.getArgs().isDumpAnalysis(), Objects.nonNull(outputDir) })) {
            ObjectMapper mapper = JsonMapper.builder()
                    .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(Include.NON_NULL))
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .build();
            FileUtils.forceMkdir(outputDir);
            File file = new File(outputDir, "codiqo-hotspots-" + snapshot.getCommitSha() + ".json");
            FileUtils.writeStringToFile(file, mapper.writeValueAsString(snapshot), StandardCharsets.UTF_8);
            log.info("hotspot snapshot written to " + file.getAbsolutePath());
        }
    }
    private static HotspotSnapshotModel snapshot(SubmissionContext ctx, Repository repo, Log log) throws IOException {
        StopWatch watch = StopWatch.createStarted();
        long heapBefore = MemoryReport.heapUsed();
        MemoryReport.resetHeapPeak();
        RunArgs args = ctx.getArgs();
        IndexingSummary index = ctx.getIndex();
        Path workTree = ctx.getWorkTree();

        Map<String, FileChurn> churn = GitChurn.collect(repo, args.getCommitId());

        List<DeclaredType> production = index.getTypes().stream().filter(Predicate.not(DeclaredType::isTest)).toList();

        List<RankedType> ranked = HotspotRanker.rank(production, index.getReferences(), type -> HotspotFindings.relative(workTree, type.getFile()), churn);

        HotspotSnapshotModel toReturn = new HotspotSnapshotModel().commitSha(args.getCommitId()).classes(new ArrayList<>());
        for (RankedType type : ranked) {
            toReturn.getClasses().add(classModel(ctx, type));
        }

        watch.stop();
        /**
         * The peak heap is read before JOL walks the object graphs for the memory report: that walk allocates far
         * more than building the snapshot does, so reading the peak afterwards would attribute JOL's allocation to
         * this stage.
         */
        long peakOverEntry = Math.max(0, MemoryReport.peakHeapUsed() - heapBefore);
        if (MemoryReport.isProfiling()) {
            List<CloneLocations> clones = ctx.getAnalysis().cpd().stream().flatMap(summary -> summary.clones().stream()).toList();
            log.info("memory (hotspots) types=%s references=%s churn=%s clones=%s snapshot=%s peak-heap=+%s over stage entry",
                    MemoryReport.retained(index.getTypes()).orElse("?"),
                    MemoryReport.retained(index.getReferences()).orElse("?"),
                    MemoryReport.retained(churn).orElse("?"),
                    MemoryReport.retained(clones).orElse("?"),
                    MemoryReport.retained(toReturn).orElse("?"),
                    MemoryReport.human(peakOverEntry));
        }
        log.info("hotspots: ranked %d production types (%d references, %d files with history) into %d classes in %s",
                production.size(), index.getReferences().size(), churn.size(), toReturn.getClasses().size(), watch);
        return toReturn;
    }
    private static HotspotClassModel classModel(SubmissionContext ctx, RankedType ranked) {
        DeclaredType type = ranked.getType();
        File file = type.getFile();
        Collection<CodeBlockInfo> blocks = ctx.getIndex().getBlocks().get(file);

        HotspotClassModel toReturn = new HotspotClassModel()
                .className(type.getName())
                .filePath(ranked.getPath())
                .kind(HotspotTypeKind.fromValue(type.getKind().name()))
                .importanceRank(ranked.getImportanceRank())
                .hotspotRank(ranked.getHotspotRank())
                .ncss(type.getNcss())
                .dependents(ranked.getDependents())
                .effectiveDependents(ranked.getEffectiveDependents())
                .dependencies(ranked.getDependencies())
                .commits(ranked.getChurn().getCommits())
                .recentCommits(ranked.getChurn().getRecentCommits())
                .recentFixCommits(ranked.getChurn().getRecentFixCommits())
                .maxCognitiveComplexity(blocks.stream().mapToInt(block -> block.metrics().cognitive()).max().orElse(0))
                .findings(HotspotFindings.collect(file, blocks, ctx.getAnalysis().cpd(), ctx.getWorkTree()));
        ctx.getArgs().owner(file).ifPresent(module -> toReturn.setModule(module.getName()));

        /**
         * Only outermost blocks are counted: a block's coverage spans its whole line range, so a nested block's lines
         * are already in its enclosing block's counts, and summing both would count those lines twice.
         */
        int executable = 0;
        int covered = 0;
        for (CodeBlockInfo block : blocks) {
            CodeBlockCoverage coverage = block.coverage();
            if (BooleanUtils.and(new boolean[] { coverage.hasCoverageData(), isOutermost(block, blocks) })) {
                executable += coverage.executable();
                covered += coverage.getCovered() + coverage.getPartial();
            }
        }
        if (executable > 0) {
            toReturn.setLineCoverage((double) covered / executable);
        }
        return toReturn;
    }
    private static boolean isOutermost(CodeBlockInfo block, Collection<CodeBlockInfo> blocks) {
        int start = block.getLocation().getStartLine();
        int end = block.getLocation().getEndLine();
        return blocks.stream()
                .filter(other -> other != block)
                .noneMatch(other -> other.getLocation().getStartLine() <= start && end <= other.getLocation().getEndLine());
    }
    private static boolean isShallow(Repository repo) throws IOException {
        try (ObjectReader reader = repo.newObjectReader()) {
            return CollectionUtils.isNotEmpty(reader.getShallowCommits());
        }
    }
}
