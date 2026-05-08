package io.codiqo.api;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Option.Builder;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.Repository;

import com.google.common.base.CaseFormat;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import edu.umd.cs.findbugs.Priorities;
import io.codiqo.util.JGit;
import lombok.Data;
import net.sourceforge.pmd.lang.rule.RulePriority;
import okhttp3.HttpUrl;

@Data
public class RunArgs {
    public static final String DEFAULT_API_URL = "https://api.codiqo.io";
    public static final Map<String, String> JDTLS_CONFIG = ImmutableMap.of(
            "osx-x86_64", "config_mac",
            "osx-aarch_64", "config_mac_arm",
            "linux-x86_64", "config_linux",
            "linux-aarch_64", "config_linux_arm",
            "windows-x86_64", "config_win");
    public static final Supplier<HttpUrl.Builder> JDTLS_BASE_URL = () -> new HttpUrl.Builder().scheme("https").host("download.eclipse.org").addPathSegment("jdtls").addPathSegment("milestones");
    public static final Path JDT_SHARED_INDEX = FileSystems.getDefault().getPath(System.getProperty("user.home"), ".cache", "jdtls");
    static {
        try {
            Files.createDirectories(JDT_SHARED_INDEX);
        } catch (IOException err) {
            throw new ExceptionInInitializerError(err);
        }
    }

    @Nullable
    private String commitId;
    @Nullable
    private String jdtlsVersion = "1.58.0";
    @Nullable
    private String pmdMinPriority = RulePriority.HIGH.name();
    @Nullable
    private Integer spotbugsPriorityThreshold = Priorities.HIGH_PRIORITY;
    @Nullable
    private String spotbugsOmitVisitors;
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
    private boolean failOnJdtlsError = false;
    @Nullable
    private transient File javaHome;
    @Nullable
    private transient File mavenHome;
    @Nullable
    private transient File gradleHome;
    @Nullable
    private transient ClassGraphSpec classGraph;
    @Nullable
    private Duration buildTimeout = Duration.ofMinutes(30);
    @Nullable
    private Duration testTimeout = Duration.ofMinutes(10);
    @Nullable
    private Duration importTimeout = Duration.ofMinutes(15);
    @Nullable
    private Duration lspQueryTimeout = Duration.ofSeconds(30);
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
    @Nullable
    private int diffContextLines = 10;
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
    private transient Repository git;
    @Nullable
    private transient String defaultBranch;
    @Nullable
    private transient Set<String> remoteUrls = Sets.newHashSet();
    @Nullable
    private String llmApiKey = System.getProperty("ollama.apiKey");
    @Nullable
    private String llmModel = System.getProperty("ollama.model", "deepseek-v4-pro:cloud");
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
    private boolean llmEnableWebSearchTool = false;
    @Nullable
    private transient File outputDirectory;
    @Nullable
    private String includeBranches;
    @Nullable
    private String includeAuthorEmails;
    @Nullable
    private double sizeFactorDivisor = 100.0;
    @Nullable
    private double modifyMultiplierScale = 0.3;
    @Nullable
    private double modifyMultiplierCap = 0.2;
    @Nullable
    private double addMultiplierScale = 0.1;
    @Nullable
    private double qualityMultiplierMin = 0.5;
    @Nullable
    private double qualityMultiplierMax = 1.2;
    @Nullable
    private double staticAnalysisPenaltyCap = 0.2;
    @Nullable
    private double staticAnalysisIntroducedPenalty = -0.05;
    @Nullable
    private double staticAnalysisPreExistingPenalty = -0.01;
    @Nullable
    private double staticAnalysisCleanBonus = 0.05;
    @Nullable
    private double architecturePenaltyCap = 0.15;
    @Nullable
    private double qualityGatePenaltyCap = 0.1;
    @Nullable
    private double volumeExponent = 0.98;
    @Nullable
    private double filesScopeLogCoefficient = 0.02;
    @Nullable
    private double filesScopeMaxBonus = 0.10;
    @Nullable
    private double driverScoreCapMultiplier = 2.5;
    @Nullable
    private double driverFactorMaxDeviation = 0.75;
    @Nullable
    private boolean driverScoreCapDryRun = false;
    @Nullable
    private double statsQuantile = 0.95;
    @Nullable
    private double coverageLowThreshold = 50.0;
    @Nullable
    private double coverageCriticalThreshold = 10.0;
    @Nullable
    private double coverageHighThreshold = 30.0;
    @Nullable
    private int highComplexityThreshold = 10;
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
    private double testCodeScoreMultiplier = 0.4;
    @Nullable
    private double testCodePenaltyWeight = 0.3;
    @Nullable
    private int scoreThresholdHuge = 150;
    @Nullable
    private int scoreThresholdLarge = 90;
    @Nullable
    private int scoreThresholdMedium = 50;
    @Nullable
    private int scoreThresholdSmall = 20;
    @Nullable
    private int dimensionScoreCritical = 8;
    @Nullable
    private int dimensionScoreMajor = 6;
    @Nullable
    private int dimensionScoreModerate = 4;
    @Nullable
    private int callerThresholdHigh = 10;
    @Nullable
    private int callerThresholdModerate = 5;
    @Nullable
    private int maxClonesToShow = 10;
    @Nullable
    private int maxSourceLines = 30;
    @Nullable
    private int truncateSourceLines = 25;
    @Nullable
    private double architectureBonusFactor = 0.015;
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
    @Nullable
    private int seniorReviewCriticalThreshold = 8;
    @Nullable
    private int complexityHighDisplayThreshold = 15;
    @Nullable
    private int complexityModerateDisplayThreshold = 10;
    @Nullable
    private double similarityCriticalThreshold = 0.90;
    @Nullable
    private double similarityMajorThreshold = 0.75;
    @Nullable
    private int riskHighDimensionThreshold = 7;
    @Nullable
    private int riskBaseMultiplier = 7;
    @Nullable
    private int riskHighDimensionPenalty = 5;
    @Nullable
    private int riskCoreLibraryPenalty = 10;
    @Nullable
    private int riskBreakingChangesPenalty = 10;
    @Nullable
    private int riskScoreMax = 100;
    @Nullable
    private int riskLevelLowMax = 25;
    @Nullable
    private int riskLevelModerateMax = 50;
    @Nullable
    private int riskLevelHighMax = 75;
    @Nullable
    private int riskLevelVeryHighMax = 90;
    @Nullable
    private int coverageImpactExcellentMin = 90;
    @Nullable
    private int coverageImpactGoodMin = 80;
    @Nullable
    private int coverageImpactAcceptableMin = 70;
    @Nullable
    private int coverageImpactLowMin = 60;
    @Nullable
    private int coverageImpactPoorMin = 50;
    @Nullable
    private int fanOutHighThreshold = 10;
    @Nullable
    private int npathComplexThreshold = 200;
    @Nullable
    private boolean hideSourceCode = false;
    @Nullable
    private boolean jdtUseSharedIndex = true;
    @Nullable
    private boolean jdtIncludeDecompiledSources = false;
    @Nullable
    private transient Integer jdtDebugPort;

    public Optional<ProjectSpec> owner(File filePath) {
        return projects.stream().filter(proj -> proj.contains(filePath)).findAny();
    }
    public boolean matchesByBranch(List<String> branches) {
        if (StringUtils.isEmpty(includeBranches)) {
            return true;
        }
        List<String> patterns = Splitter.on(',').trimResults().omitEmptyStrings().splitToList(includeBranches);
        return branches.stream().anyMatch(branch -> patterns.stream().anyMatch(pattern -> branch.matches(pattern)));
    }
    public boolean matchesByAuthor(String authorEmail) {
        if (StringUtils.isEmpty(includeAuthorEmails)) {
            return true;
        }
        List<String> emails = Splitter.on(',').trimResults().omitEmptyStrings().splitToList(includeAuthorEmails);
        return emails.contains(authorEmail);
    }
    public void validate() {
        this.statsQuantile = Math.max(0.85, this.statsQuantile);
        this.llmMaxRetries = (short) Math.max(1, this.llmMaxRetries);
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
                    field.set(toReturn, JGit.openRepository(new File(value)));
                }
            }
        }
        toReturn.validate();
        return toReturn;
    }
}
