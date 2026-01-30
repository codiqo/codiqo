package io.codiqo.api;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Option.Builder;
import org.apache.commons.cli.Options;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import com.google.common.base.CaseFormat;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import edu.umd.cs.findbugs.Priorities;
import lombok.Data;
import net.sourceforge.pmd.lang.rule.RulePriority;

@Data
public class RunArgs {
    @Nullable
    private String commitId;
    @Nullable
    private String jdtlsVersion = "1.56.0";
    @Nullable
    private String pmdMinPriority = RulePriority.HIGH.name();
    @Nullable
    private Integer spotbugsPriorityThreshold = Priorities.HIGH_PRIORITY;
    @Nullable
    private boolean includeUntracked = true;
    @Nullable
    private boolean autoBuild = false;
    @Nullable
    private boolean dumpAnalysis = true;
    @Nullable
    private boolean ignoreCoverage = false;
    @Nullable
    private boolean ignoreComplexity = false;
    @Nullable
    private boolean ignoreCpd = false;
    @Nullable
    private boolean ignoreDiagnostics = false;
    @Nullable
    private File javaHome;
    @Nullable
    private File mavenHome;
    @Nullable
    private Duration importTimeout = Duration.ofMinutes(15);
    @Nullable
    private Duration connectTimeout = Duration.ofSeconds(30);
    @Nullable
    private Duration readTimeout = Duration.ofMinutes(5);
    @Nullable
    private int maxRequests = Short.MAX_VALUE;
    @Nullable
    private int maxRequestsPerHost = Byte.MAX_VALUE;
    @Nullable
    private int cpdMinimumTileSize = Byte.MAX_VALUE / 2;
    /**
     * Threshold ratio (0.0-1.0) for determining if a clone was "introduced" in the commit.
     * A clone is considered "introduced" only if this percentage of its lines overlap with added lines.
     * Default: 0.4 (40%) - just modifying 1 line of a pre-existing clone doesn't mean the duplication was introduced.
     */
    @Nullable
    private double cpdIntroducedThreshold = 0.4;
    @Nullable
    private transient List<ProjectSpec> projects = Lists.newArrayList();
    @Nullable
    private transient List<File> agents = Lists.newArrayList();
    @Nullable
    private Repository git;
    @Nullable
    private String defaultBranch;
    @Nullable
    private transient Set<String> remoteUrls = Sets.newHashSet();
    @Nullable
    private String llmModel = System.getProperty("ollama.model", "gpt-oss:120b-cloud");
    @Nullable
    private String llmApiKey = System.getProperty("ollama.apiKey");
    @Nullable
    private String llmBaseUrl = System.getProperty("ollama.url", "https://ollama.com/v1");
    @Nullable
    private Double llmTemperature = 0.3;
    @Nullable
    private Double llmTopP = 0.8;
    @Nullable
    private Integer llmMaxTokens = (int) Short.MAX_VALUE;
    @Nullable
    private Short llmMaxRetries = 3;
    @Nullable
    private boolean llmNativeThinking = true;
    @Nullable
    private boolean llmEnableWebSearchTool = true;
    @Nullable
    private File outputDirectory;
    @Nullable
    private double sizeFactorDivisor = 100.0;
    @Nullable
    private double modifyMultiplierScale = 0.3;
    @Nullable
    private double modifyMultiplierCap = 0.2;
    @Nullable
    private double addMultiplierScale = 0.1;
    @Nullable
    private double relativeAdjustmentFactor = 0.1;
    @Nullable
    private double relativeAdjustmentMin = 0.8;
    @Nullable
    private double relativeAdjustmentMax = 1.3;
    @Nullable
    private double qualityMultiplierMin = 0.5;
    @Nullable
    private double qualityMultiplierMax = 1.2;
    @Nullable
    private double staticAnalysisPenaltyCap = 0.30;
    @Nullable
    private double staticAnalysisIntroducedPenalty = -0.05;
    @Nullable
    private double staticAnalysisPreExistingPenalty = -0.01;
    @Nullable
    private double staticAnalysisCleanBonus = 0.05;
    @Nullable
    private double architecturePenaltyCap = 0.20;
    @Nullable
    private double qualityGatePenaltyCap = 0.15;
    @Nullable
    private double defaultComplexityMultiplier = 1.05;
    @Nullable
    private double coverageLowThreshold = 50.0;
    @Nullable
    private double coverageCriticalThreshold = 10.0;
    @Nullable
    private double coverageHighThreshold = 30.0;
    @Nullable
    private int highComplexityThreshold = 10;
    @Nullable
    private double linesLogFactor = 5.0;
    @Nullable
    private double methodsModifiedLogFactor = 6.0;
    @Nullable
    private double methodsAddedLogFactor = 8.0;
    @Nullable
    private double classesModifiedLogFactor = 5.0;
    @Nullable
    private double classesAddedLogFactor = 8.0;
    @Nullable
    private int complexityTrivialMax = 5;
    @Nullable
    private int complexityModerateMax = 10;
    @Nullable
    private int complexityComplexMax = 20;
    @Nullable
    private double modifyTrivialMultiplier = 1.0;
    @Nullable
    private double modifyModerateMultiplier = 1.1;
    @Nullable
    private double modifyComplexMultiplier = 1.2;
    @Nullable
    private double modifyHighlyComplexMultiplier = 1.3;
    @Nullable
    private double createTrivialMultiplier = 1.1;
    @Nullable
    private double createModerateMultiplier = 1.0;
    @Nullable
    private double createComplexMultiplier = 0.9;
    @Nullable
    private double createHighlyComplexMultiplier = 0.7;
    @Nullable
    private double cpdCleanBonus = 0.05;
    @Nullable
    private double cpdModeratePenalty = -0.10;
    @Nullable
    private double cpdHighPenalty = -0.20;
    @Nullable
    private double cpdSeverePenalty = -0.30;
    @Nullable
    private double cpdCleanThreshold = 1.0;
    @Nullable
    private double cpdAcceptableThreshold = 3.0;
    @Nullable
    private double cpdModerateThreshold = 6.0;
    @Nullable
    private double cpdHighThreshold = 10.0;
    @Nullable
    private double testCodePenaltyWeight = 0.2;
    @Nullable
    private double architectureBonusFactor = 0.01;
    @Nullable
    private double pmdPriority1Penalty = -0.05;
    @Nullable
    private double pmdPriority2Penalty = -0.03;
    @Nullable
    private double pmdPriority3Penalty = -0.01;
    @Nullable
    private double spotbugsScariestPenalty = -0.08;
    @Nullable
    private double spotbugsScaryPenalty = -0.04;
    @Nullable
    private double spotbugsTroublingPenalty = -0.02;
    @Nullable
    private double coverageExcellentBonus = 0.10;
    @Nullable
    private double coverageGoodBonus = 0.05;
    @Nullable
    private double coverageLowPenalty = -0.05;
    @Nullable
    private double coveragePoorPenalty = -0.10;
    @Nullable
    private double coverageTerriblePenalty = -0.15;
    @Nullable
    private double architectureMinorPenalty = -0.01;
    @Nullable
    private double architectureSolidPenalty = -0.03;
    @Nullable
    private double architectureMajorPenalty = -0.05;
    @Nullable
    private double qualityGateFailurePenalty = -0.03;
    @Nullable
    private int architectureImpactScoreThreshold = 7;
    @Nullable
    private int architectureImpactCoverageRequired = 80;
    @Nullable
    private int concurrencyRiskThreshold = 3;
    @Nullable
    private int integrationSurfaceThreshold = 7;
    @Nullable
    private int dataIntegrityThreshold = 7;
    @Nullable
    private int securitySensitivityThreshold = 5;
    @Nullable
    private int scalabilityImpactThreshold = 7;
    @Nullable
    private int observabilityThreshold = 7;
    @Nullable
    private int resilienceThreshold = 7;
    @Nullable
    private int performanceThreshold = 7;
    @Nullable
    private int seniorReviewThreshold = 7;
    public Optional<ProjectSpec> owner(File filePath) {
        return projects.stream().filter(proj -> proj.contains(filePath)).findAny();
    }
    public static Options options() {
        Options toReturn = new Options();
        Field[] fields = RunArgs.class.getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true);
            if (Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            String camel = field.getName();
            String kebab = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, camel);
            Builder builder = Option.builder().longOpt(kebab);
            if (field.getType().equals(boolean.class)) {
                builder = builder.hasArg(false);
            } else {
                builder = builder.hasArg();
            }
            builder = builder.required(Objects.isNull(field.getAnnotation(Nullable.class)));
            toReturn.addOption(builder.get());
        }
        return toReturn;
    }
    public static RunArgs from(CommandLine cmd) throws Exception {
        RunArgs toReturn = new RunArgs();
        Field[] fields = RunArgs.class.getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true);
            if (Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            String camel = field.getName();
            String kebab = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, camel);
            if (cmd.hasOption(kebab)) {
                if (field.getType().equals(boolean.class)) {
                    field.set(toReturn, true);
                    continue;
                }
                String value = cmd.getOptionValue(kebab);
                if (field.getType().equals(String.class)) {
                    field.set(toReturn, value);
                } else if (field.getType().equals(int.class)) {
                    field.set(toReturn, Integer.parseInt(value));
                } else if (field.getType().equals(double.class)) {
                    field.set(toReturn, Double.parseDouble(value));
                } else if (field.getType().equals(Duration.class)) {
                    field.set(toReturn, Duration.parse(value));
                } else if (field.getType().equals(File.class)) {
                    field.set(toReturn, new File(value));
                } else if (field.getType().equals(Repository.class)) {
                    field.set(toReturn, new FileRepositoryBuilder().setGitDir(new File(value, ".git")).readEnvironment().findGitDir().build());
                }
            }
        }
        return toReturn;
    }
}
