package io.codiqo.submit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import io.codiqo.api.diff.IneffectiveLineFilter;
import io.codiqo.api.diff.JavaInvocationCounter;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.BugModel;
import io.codiqo.client.model.BugsModel;
import io.codiqo.client.model.CloneLocationModel;
import io.codiqo.client.model.CloneModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.DuplicationReportModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.HotspotClassModel;
import io.codiqo.client.model.HotspotFindingModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.client.model.JavaInfoModel;
import io.codiqo.client.model.LocalAssessmentModel;
import io.codiqo.client.model.LocalReviewModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.RiskDimensionsModel;
import io.codiqo.client.model.StaticAnalysisFindingModel;
import io.codiqo.client.model.StaticAnalysisReviewModel;
import io.codiqo.lang.config.ConfigFiles;
import io.codiqo.llm.MovedLineDetector;

/**
 * Hidden-source mode ({@code codiqo.sourceHidden}): what leaves the build machine carries no source code, and the
 * commit scores exactly as its plain submission would. It is applied to every request model just before it is sent,
 * after the local review has read the plain code and labelled the plain units, and it changes the model in place.
 *
 * <ul>
 * <li>Source text and everything the review wrote is dropped: file contents, unit bodies and diffs, clone slices,
 * annotation arguments, diagnostic and finding messages, build output, agent instructions, the review's reasoning,
 * observations, summary, rationales and bug texts. The review trace is never sent (the callers' concern).</li>
 * <li>Identifiers (signatures, names, class and package names, invocation targets, test names) become keyed hashes. A
 * path is hashed segment by segment, so its separators survive, and its last segment keeps its extension, or its whole
 * name when it is a config file recognised by name ({@link ConfigFiles}): the scorer picks the line filter and the
 * language from exactly those.</li>
 * <li>The diff keeps its git headers, hunk headers, line prefixes, whitespace and punctuation, and every token
 * {@link MovedLineDetector#WORD_TOKEN} cuts becomes a keyed hash of the same kind (identifier, number, literal),
 * except the words the scorer reads by name. Line counts, comment and import detection, moved-line matching and the
 * invocation count are then what they are on the plain diff, and the server can score it again later.</li>
 * </ul>
 *
 * <p>The hash is HMAC-SHA256 under a key only the client holds, so the server cannot reverse a name by hashing
 * candidates. The same key must be used for the whole project: the server compares a unit's file with a clone's, a
 * diagnostic's and a bug's, and a label's signature with its unit's, so an identifier must hash identically wherever
 * it occurs and in every commit. Nothing is normalised before hashing, for the same reason: two plain values are equal
 * exactly when their hashes are.
 */
public final class SourceHiding {
    public static final SourceHiding NONE = new SourceHiding(Optional.empty());

    /**
     * A key shorter than 32 characters could be guessed: whoever has the hashes and a guess at a common class name could
     * search a short key offline. {@code openssl rand -hex 32} gives 64.
     */
    private static final int MIN_KEY_LENGTH = 32;

    /** 128 bits: no two of a project's identifiers collide, and a signature stays far below every column's limit */
    private static final int IDENTIFIER_HEX = 32;

    /**
     * 64 bits per path segment: a deep Java path (twelve segments) stays below 220 characters, where whole 64-character
     * hashes would pass the 1024-character path column, and a segment only has to differ from its siblings.
     */
    private static final int PATH_SEGMENT_HEX = 16;

    /**
     * 48 bits per diff token: a diff is mostly tokens, so this bounds how much a hidden diff grows, and a token only has
     * to differ from the other tokens of the same commit for moved lines to match as they do in plain text.
     */
    private static final int TOKEN_HEX = 12;

    /** each run of non-ASCII characters (a word of a comment in another script) becomes this many private-use characters */
    private static final int NON_ASCII_RUN_LENGTH = 4;
    private static final int NON_ASCII_BITS_PER_CHAR = 12;
    private static final int PRIVATE_USE_AREA = 0xE000;

    private static final String DEV_NULL = "/dev/null";
    private static final String IDENTIFIER_PREFIX = "h";
    private static final String DIGIT_PREFIX = "0";
    private static final char DOLLAR = '$';
    private static final char DOUBLE_QUOTE = '"';
    private static final char SINGLE_QUOTE = '\'';
    private static final char NO_NEWLINE_MARKER = '\\';
    private static final String HUNK_LINE_PREFIXES = "+- ";

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+\\d+(?:,\\d+)? @@");
    private static final String GIT_HEADER = "diff --git ";
    private static final String OLD_FILE = "--- ";
    private static final String NEW_FILE = "+++ ";
    private static final String OLD_SIDE = "a/";
    private static final String NEW_SIDE = "b/";
    private static final String BINARY_FILES = "Binary files ";
    private static final String BINARY_AND = " and ";
    private static final String BINARY_DIFFER = " differ";
    private static final List<String> OLD_PATH_HEADERS = List.of("rename from ", "copy from ");
    private static final List<String> NEW_PATH_HEADERS = List.of("rename to ", "copy to ");
    /** git's metadata lines that carry modes or percentages, never a path */
    private static final List<String> PATHLESS_HEADERS = List.of(
            "old mode ", "new mode ", "deleted file mode ", "new file mode ", "similarity index ", "dissimilarity index ");
    /**
     * {@code index <old>..<new> <mode>}: the abbreviated blob ids are hashes of the files' content, which would let the
     * server confirm a guessed file, so each becomes a keyed hash of the same length. All zeros (no such blob) stays.
     */
    private static final Pattern INDEX_HEADER = Pattern.compile("^index ([0-9a-f]+)\\.\\.([0-9a-f]+)(.*)$");
    private static final char NO_BLOB = '0';

    /**
     * A reason's file names and paths: a run of characters that is no whitespace and no punctuation a message frames a
     * name with, and that holds a path separator or a dot. The general description around them stays readable.
     */
    private static final Pattern REASON_NAME = Pattern.compile("[^\\s\\[\\](){}<>,;:'\"`]*[./\\\\][^\\s\\[\\](){}<>,;:'\"`]*");
    /** an extension a masked name keeps: lowercase and short, which a class name ({@code com.acme.Foo}) is not */
    private static final Pattern REASON_EXTENSION = Pattern.compile("[a-z0-9]{1,6}");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");
    private static final String MASK = "******";
    /**
     * What ends a reason's file name on its left: a file name with spaces ({@code Secret Invoice.java}) is masked back
     * to the nearest of these, so none of its words stays readable. A description between the two is masked with it.
     */
    private static final String REASON_DELIMITERS = "[](){}<>,;:'\"`";
    private static final CharSequence[] BLOCK_COMMENT_ENDS = { "*/", "-->" };

    /**
     * The words the scorer reads by name in a diff line: a deleted import is ineffective ({@link IneffectiveLineFilter})
     * and a control-flow keyword followed by a parenthesis is no call ({@link JavaInvocationCounter}). Hashed, an import
     * would count as a changed line and an {@code if (} as an invocation.
     */
    private static final Set<String> READABLE_WORDS = ImmutableSet.<String> builder()
            .add(StringUtils.trim(IneffectiveLineFilter.IMPORT_PREFIX))
            .addAll(JavaInvocationCounter.CONTROL_FLOW_KEYWORDS)
            .build();

    private final Optional<HashFunction> hmac;

    private SourceHiding(Optional<HashFunction> hmac) {
        this.hmac = hmac;
    }
    public boolean isHidden() {
        return hmac.isPresent();
    }
    public void hide(AnalysisSubmissionModel submission) {
        if (isHidden()) {
            submission.setSourceHidden(true);
            submission.setAgentInstructions(null);

            if (Objects.nonNull(submission.getProject())) {
                CollectionUtils.emptyIfNull(submission.getProject().getModules()).forEach(this::hideModule);
            }
            CollectionUtils.emptyIfNull(submission.getFiles()).forEach(this::hideFile);
            if (Objects.nonNull(submission.getDuplication())) {
                hideDuplication(submission.getDuplication());
            }
            if (Objects.nonNull(submission.getIndex())) {
                CollectionUtils.emptyIfNull(submission.getIndex().getCodeUnits()).forEach(ref -> {
                    replace(ref::getSignature, ref::setSignature, this::id);
                    replace(ref::getPath, ref::setPath, this::path);
                });
            }
            CollectionUtils.emptyIfNull(submission.getExcludedCoverageClasses()).forEach(excluded -> replace(excluded::getClassName, excluded::setClassName, this::id));
            if (Objects.nonNull(submission.getTestRun())) {
                replace(submission.getTestRun()::getSkippedTests, submission.getTestRun()::setSkippedTests, this::ids);
            }
            if (Objects.nonNull(submission.getBuildFailure())) {
                submission.getBuildFailure().setDetail(null);
                replace(submission.getBuildFailure()::getReason, submission.getBuildFailure()::setReason, SourceHiding::reason);
            }
            if (Objects.nonNull(submission.getLocalReview())) {
                hideReview(submission.getLocalReview());
            }
        }
    }
    public void hide(AnalysisExcludeModel exclusion) {
        if (isHidden()) {
            exclusion.setSourceHidden(true);
            exclusion.setDetail(null);
            replace(exclusion::getReason, exclusion::setReason, SourceHiding::reason);
            CollectionUtils.emptyIfNull(exclusion.getFiles()).forEach(this::hideFile);
        }
    }
    public void hide(HotspotSnapshotModel snapshot) {
        if (isHidden()) {
            snapshot.setSourceHidden(true);
            for (HotspotClassModel type : CollectionUtils.emptyIfNull(snapshot.getClasses())) {
                replace(type::getClassName, type::setClassName, this::id);
                replace(type::getFilePath, type::setFilePath, this::path);
                for (HotspotFindingModel finding : CollectionUtils.emptyIfNull(type.getFindings())) {
                    finding.setMessage(null);
                    replace(finding::getMember, finding::setMember, this::id);
                    replace(finding::getOtherFilePath, finding::setOtherFilePath, this::path);
                }
            }
        }
    }
    /**
     * The plain diff with every token hashed and its git headers naming the hashed paths. {@code oldPath} and
     * {@code newPath} are the plain paths the headers name: a deleted file's new side and an added file's old side
     * are {@code /dev/null} only on the {@code ---}/{@code +++} lines, which keep what git wrote.
     */
    @VisibleForTesting
    public String diff(String diff, String oldPath, String newPath) {
        List<String> toReturn = Lists.newArrayList();
        boolean inHunk = false;
        for (String line : StringUtils.splitPreserveAllTokens(diff, CharUtils.LF)) {
            Matcher hunk = HUNK_HEADER.matcher(line);
            if (line.startsWith(GIT_HEADER)) {
                inHunk = false;
                toReturn.add(GIT_HEADER + OLD_SIDE + path(oldPath) + StringUtils.SPACE + NEW_SIDE + path(newPath));
            } else if (hunk.find()) {
                inHunk = true;
                toReturn.add(hunk.group() + text(line.substring(hunk.end())));
            } else if (inHunk) {
                toReturn.add(hunkLine(line));
            } else {
                toReturn.add(header(line, oldPath, newPath));
            }
        }
        return Joiner.on(CharUtils.LF).join(toReturn);
    }
    /** each segment hashed and every separator kept, so a path's directories stay the same directories in every commit */
    @VisibleForTesting
    public String path(String value) {
        if (DEV_NULL.equals(value)) {
            return value;
        }
        StringBuilder toReturn = new StringBuilder();
        int segmentStart = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (BooleanUtils.or(new boolean[] { c == IOUtils.DIR_SEPARATOR_UNIX, c == IOUtils.DIR_SEPARATOR_WINDOWS })) {
                toReturn.append(directory(value.substring(segmentStart, i))).append(c);
                segmentStart = i + 1;
            }
        }
        return toReturn.append(fileName(value.substring(segmentStart))).toString();
    }
    @VisibleForTesting
    public String id(String value) {
        if (StringUtils.isEmpty(value)) {
            return value;
        }
        return hex(value, IDENTIFIER_HEX);
    }
    private void hideModule(ModuleModel module) {
        replace(module::getBaseDirectory, module::setBaseDirectory, this::directoryPath);
        replace(module::getSourceRoots, module::setSourceRoots, this::directoryPaths);
        replace(module::getTestSourceRoots, module::setTestSourceRoots, this::directoryPaths);
        if (Objects.nonNull(module.getQuality())) {
            CollectionUtils.emptyIfNull(module.getQuality().getCriticalViolations()).forEach(this::hideDiagnostic);
        }
    }
    private void hideFile(FileChangeModel file) {
        String oldPath = file.getPath();
        if (BooleanUtils.and(new boolean[] { StringUtils.isNotEmpty(file.getPreviousPath()), BooleanUtils.negate(DEV_NULL.equals(file.getPreviousPath())) })) {
            oldPath = file.getPreviousPath();
        }
        String newPath = file.getPath();
        if (DEV_NULL.equals(newPath)) {
            newPath = oldPath;
        }
        String headerOld = oldPath;
        String headerNew = newPath;
        replace(file::getDiff, file::setDiff, diff -> diff(diff, headerOld, headerNew));

        file.setContentBefore(null);
        file.setContentAfter(null);
        replace(file::getPath, file::setPath, this::path);
        replace(file::getPreviousPath, file::setPreviousPath, this::path);
        CollectionUtils.emptyIfNull(file.getCodeUnits()).forEach(this::hideUnit);
    }
    private void hideUnit(CodeUnitModel unit) {
        unit.setBody(null);
        unit.setBodyBefore(null);
        unit.setUnitDiff(null);
        unit.setCategoryReason(null);
        replace(unit::getSignature, unit::setSignature, this::id);
        replace(unit::getName, unit::setName, this::id);

        if (Objects.nonNull(unit.getJavaInfo())) {
            hideJavaInfo(unit.getJavaInfo());
        }
        CollectionUtils.emptyIfNull(unit.getCallers()).forEach(caller -> {
            replace(caller::getName, caller::setName, this::id);
            replace(caller::getPath, caller::setPath, this::path);
            replace(caller::getSymbol, caller::setSymbol, this::id);
        });
        CollectionUtils.emptyIfNull(unit.getDiagnostics()).forEach(this::hideDiagnostic);
        if (Objects.nonNull(unit.getDuplication())) {
            replace(unit.getDuplication()::getDuplicateOf, unit.getDuplication()::setDuplicateOf, this::ids);
        }
    }
    private void hideJavaInfo(JavaInfoModel info) {
        replace(info::getPackageName, info::setPackageName, this::id);
        replace(info::getClassName, info::setClassName, this::id);
        replace(info::getEnclosingClass, info::setEnclosingClass, this::id);
        replace(info::getTypeParameters, info::setTypeParameters, this::ids);
        replace(info::getThrowsTypes, info::setThrowsTypes, this::ids);
        CollectionUtils.emptyIfNull(info.getAnnotations()).forEach(annotation -> {
            annotation.setArguments(null);
            replace(annotation::getName, annotation::setName, this::id);
            replace(annotation::getQualifiedName, annotation::setQualifiedName, this::id);
        });
        CollectionUtils.emptyIfNull(info.getInvocations()).forEach(invocation -> {
            replace(invocation::getOwner, invocation::setOwner, this::id);
            replace(invocation::getName, invocation::setName, this::id);
            replace(invocation::getDescriptor, invocation::setDescriptor, this::id);
            replace(invocation::getTargetOwner, invocation::setTargetOwner, this::id);
            replace(invocation::getTargetDescriptor, invocation::setTargetDescriptor, this::id);
        });
    }
    private void hideDiagnostic(DiagnosticModel diagnostic) {
        diagnostic.setMessage(null);
        replace(diagnostic::getFilePath, diagnostic::setFilePath, this::path);
        if (Objects.nonNull(diagnostic.getSpotbugsInfo())) {
            replace(diagnostic.getSpotbugsInfo()::getMethodSignature, diagnostic.getSpotbugsInfo()::setMethodSignature, this::id);
        }
    }
    private void hideDuplication(DuplicationReportModel duplication) {
        for (CloneModel clone : CollectionUtils.emptyIfNull(duplication.getClones())) {
            for (CloneLocationModel location : CollectionUtils.emptyIfNull(clone.getLocations())) {
                location.setSourceSlice(null);
                replace(location::getPath, location::setPath, this::path);
                replace(location::getCodeUnitSignature, location::setCodeUnitSignature, this::id);
            }
        }
        CollectionUtils.emptyIfNull(duplication.getClonesFromExisting()).forEach(existing -> {
            replace(existing::getAffectedSignature, existing::setAffectedSignature, this::id);
            replace(existing::getSourceSignatures, existing::setSourceSignatures, this::ids);
        });
        CollectionUtils.emptyIfNull(duplication.getNewClones()).forEach(group -> replace(group::getMemberSignatures, group::setMemberSignatures, this::ids));
    }
    private void hideReview(LocalReviewModel review) {
        review.setReasoning(null);
        review.setObservations(null);

        BugsModel bugs = review.getBugs();
        if (Objects.nonNull(bugs)) {
            Stream.of(bugs.getBlocking(), bugs.getMajor(), bugs.getMinor())
                    .flatMap(list -> CollectionUtils.emptyIfNull(list).stream())
                    .forEach(this::hideBug);
        }
        if (Objects.nonNull(review.getAssessment())) {
            hideAssessment(review.getAssessment());
        }
    }
    /**
     * The SOLID violations and architecture issues are priced by their number alone, so each one is kept as the hash of
     * its text: the count survives and the sentence does not. The senior-review reasons are only shown, so they go.
     */
    private void hideAssessment(LocalAssessmentModel assessment) {
        assessment.setSummary(null);
        assessment.setTaskComplexityRationale(null);
        assessment.setBlastRadiusExplanation(null);
        assessment.setSeniorReviewReasons(null);
        replace(assessment::getSolidViolations, assessment::setSolidViolations, this::ids);
        replace(assessment::getArchitectureIssues, assessment::setArchitectureIssues, this::ids);

        if (Objects.nonNull(assessment.getSignatureChanges())) {
            replace(assessment.getSignatureChanges()::getChangedSignatures, assessment.getSignatureChanges()::setChangedSignatures, this::ids);
        }
        RiskDimensionsModel dimensions = assessment.getQualityDimensions();
        if (Objects.nonNull(dimensions)) {
            Stream.of(
                    dimensions.getArchitectureImpact(),
                    dimensions.getConcurrencyRisk(),
                    dimensions.getIntegrationSurface(),
                    dimensions.getDataIntegrity(),
                    dimensions.getSecuritySensitivity(),
                    dimensions.getScalabilityImpact(),
                    dimensions.getObservability(),
                    dimensions.getResilience(),
                    dimensions.getPerformance(),
                    dimensions.getTestingCoverage())
                    .filter(Objects::nonNull)
                    .forEach(dimension -> dimension.setRationale(null));
        }
        StaticAnalysisReviewModel staticAnalysis = assessment.getStaticAnalysisReview();
        if (Objects.nonNull(staticAnalysis)) {
            Stream.of(
                    staticAnalysis.getPmdValidated(),
                    staticAnalysis.getPmdFalsePositives(),
                    staticAnalysis.getPmdInChangedLines(),
                    staticAnalysis.getPmdPreExisting(),
                    staticAnalysis.getSpotbugsValidated(),
                    staticAnalysis.getSpotbugsFalsePositives(),
                    staticAnalysis.getSpotbugsInChangedLines(),
                    staticAnalysis.getSpotbugsPreExisting(),
                    staticAnalysis.getErrorproneFalsePositives(),
                    staticAnalysis.getErrorproneInChangedLines(),
                    staticAnalysis.getErrorpronePreExisting(),
                    staticAnalysis.getPreExistingIssues())
                    .flatMap(list -> CollectionUtils.emptyIfNull(list).stream())
                    .forEach(this::hideFinding);
        }
    }
    private void hideBug(BugModel bug) {
        bug.setTitle(null);
        bug.setDescription(null);
        bug.setSuggestedFix(null);
        bug.setSuggestedBlockCode(null);
        bug.setSuggestedFileFix(null);
        replace(bug::getFile, bug::setFile, this::path);
    }
    private void hideFinding(StaticAnalysisFindingModel finding) {
        finding.setAssessment(null);
        replace(finding::getFile, finding::setFile, this::path);
    }
    private String header(String line, String oldPath, String newPath) {
        if (line.startsWith(OLD_FILE)) {
            return OLD_FILE + side(line.substring(OLD_FILE.length()), OLD_SIDE, oldPath);
        }
        if (line.startsWith(NEW_FILE)) {
            return NEW_FILE + side(line.substring(NEW_FILE.length()), NEW_SIDE, newPath);
        }
        if (line.startsWith(BINARY_FILES)) {
            String sides = StringUtils.removeEnd(line.substring(BINARY_FILES.length()), BINARY_DIFFER);
            return BINARY_FILES
                    + side(StringUtils.substringBefore(sides, BINARY_AND), OLD_SIDE, oldPath)
                    + BINARY_AND
                    + side(StringUtils.substringAfterLast(sides, BINARY_AND), NEW_SIDE, newPath)
                    + BINARY_DIFFER;
        }
        for (String prefix : OLD_PATH_HEADERS) {
            if (line.startsWith(prefix)) {
                return prefix + path(oldPath);
            }
        }
        for (String prefix : NEW_PATH_HEADERS) {
            if (line.startsWith(prefix)) {
                return prefix + path(newPath);
            }
        }
        Matcher index = INDEX_HEADER.matcher(line);
        if (index.matches()) {
            return "index " + blob(index.group(1)) + ".." + blob(index.group(2)) + index.group(3);
        }
        if (PATHLESS_HEADERS.stream().anyMatch(line::startsWith)) {
            return line;
        }
        return text(line);
    }
    private String side(String written, String prefix, String plainPath) {
        if (DEV_NULL.equals(written)) {
            return written;
        }
        return prefix + path(plainPath);
    }
    /** a hunk line keeps its prefix; git's "\ No newline at end of file" carries nothing of the file */
    private String hunkLine(String line) {
        if (line.isEmpty()) {
            return line;
        }
        char prefix = line.charAt(0);
        if (prefix == NO_NEWLINE_MARKER) {
            return line;
        }
        if (HUNK_LINE_PREFIXES.indexOf(prefix) >= 0) {
            return prefix + text(line.substring(1));
        }
        return text(line);
    }
    /**
     * Every token replaced by one of the same kind, everything between tokens kept, so that the hidden line cuts into
     * exactly as many tokens, at the same places, as the plain one. Non-ASCII text between tokens (a comment in another
     * script) is not a token, but it is source, so each run of it is hashed into private-use characters, which are no
     * token either.
     */
    private String text(String line) {
        StringBuilder toReturn = new StringBuilder(line.length());
        Matcher matcher = MovedLineDetector.WORD_TOKEN.matcher(line);
        int end = 0;
        while (matcher.find()) {
            gap(line.substring(end, matcher.start()), toReturn);
            toReturn.append(token(matcher.group()));
            end = matcher.end();
        }
        gap(line.substring(end), toReturn);
        return toReturn.toString();
    }
    /**
     * A literal keeps its quotes and has its content hidden as a line of its own, rather than hashed whole: the
     * invocation count matches call shapes inside literals as well, so {@code "retry(x)"} must keep its call. Content
     * with no token at all ({@code "--->"}, {@code "@#!%"}) would come out unchanged that way, so it is hashed into
     * private-use characters, which hold no token and no call shape either. Whitespace-only content stays, and so does
     * content holding a block comment's end marker: the comment check finds the first {@code *}{@code /} or
     * {@code -->} on a line, wherever it is, and moving it would turn a code line into a comment line.
     */
    private String token(String token) {
        char first = token.charAt(0);
        if (BooleanUtils.or(new boolean[] { first == DOUBLE_QUOTE, first == SINGLE_QUOTE })) {
            String content = token.substring(1, token.length() - 1);
            if (BooleanUtils.or(new boolean[] { MovedLineDetector.WORD_TOKEN.matcher(content).find(), StringUtils.isBlank(content),
                    StringUtils.containsAny(content, BLOCK_COMMENT_ENDS) })) {
                return first + text(content) + first;
            }
            return first + nonAscii(content) + first;
        }
        if (CharUtils.isAsciiNumeric(first)) {
            return Long.toUnsignedString(Long.parseUnsignedLong(hex(token, TOKEN_HEX), 16));
        }
        if (READABLE_WORDS.contains(token)) {
            return token;
        }
        /**
         * A leading {@code $} is not a word character, so it decides whether a call shape starts at it; kept, the hashed
         * token matches where the plain one did, and a lone {@code $} names nothing. What follows it keeps whether it
         * starts with a digit: {@code $1(} is no call shape, since no name starts at the digit, and {@code $h1(} would be.
         */
        int name = StringUtils.indexOfAnyBut(token, DOLLAR);
        if (name < 0) {
            return token;
        }
        String start = CharUtils.isAsciiNumeric(token.charAt(name)) ? DIGIT_PREFIX : IDENTIFIER_PREFIX;
        return token.substring(0, name) + start + hex(token.substring(name), TOKEN_HEX);
    }
    /**
     * Whitespace is kept whatever its script: a comment followed by an ideographic space is still a comment line,
     * because the comment check treats every whitespace character as blank, and a private-use character would not be.
     */
    private void gap(String gap, StringBuilder target) {
        int runStart = -1;
        for (int i = 0; i < gap.length(); i++) {
            char c = gap.charAt(i);
            if (BooleanUtils.or(new boolean[] { CharUtils.isAscii(c), Character.isWhitespace(c) })) {
                if (runStart >= 0) {
                    target.append(nonAscii(gap.substring(runStart, i)));
                    runStart = -1;
                }
                target.append(c);
            } else if (runStart < 0) {
                runStart = i;
            }
        }
        if (runStart >= 0) {
            target.append(nonAscii(gap.substring(runStart)));
        }
    }
    private String nonAscii(String run) {
        long bits = Long.parseUnsignedLong(hex(run, NON_ASCII_RUN_LENGTH * NON_ASCII_BITS_PER_CHAR / 4), 16);
        StringBuilder toReturn = new StringBuilder(NON_ASCII_RUN_LENGTH);
        for (int i = 0; i < NON_ASCII_RUN_LENGTH; i++) {
            toReturn.append((char) (PRIVATE_USE_AREA + (bits & ((1 << NON_ASCII_BITS_PER_CHAR) - 1))));
            bits >>>= NON_ASCII_BITS_PER_CHAR;
        }
        return toReturn.toString();
    }
    private String directory(String segment) {
        if (BooleanUtils.or(new boolean[] { StringUtils.isEmpty(segment), ".".equals(segment), "..".equals(segment) })) {
            return segment;
        }
        return hex(segment, PATH_SEGMENT_HEX);
    }
    private String fileName(String name) {
        if (BooleanUtils.or(new boolean[] { StringUtils.isEmpty(name), ConfigFiles.isConfigFile(name) })) {
            return name;
        }
        String extension = FilenameUtils.getExtension(name);
        if (extension.isEmpty()) {
            return hex(name, PATH_SEGMENT_HEX);
        }
        return hex(FilenameUtils.removeExtension(name), PATH_SEGMENT_HEX) + FilenameUtils.EXTENSION_SEPARATOR + extension;
    }
    private List<String> ids(List<String> values) {
        return values.stream().map(this::id).collect(Collectors.toList());
    }
    /**
     * A directory hashed as the directories of a file's path are, every segment whole: its last segment has no extension
     * to keep, and a module named {@code com.acme.payments} must still prefix its files' hashed paths.
     */
    private String directoryPath(String value) {
        StringBuilder toReturn = new StringBuilder();
        int segmentStart = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (BooleanUtils.or(new boolean[] { c == IOUtils.DIR_SEPARATOR_UNIX, c == IOUtils.DIR_SEPARATOR_WINDOWS })) {
                toReturn.append(directory(value.substring(segmentStart, i))).append(c);
                segmentStart = i + 1;
            }
        }
        return toReturn.append(directory(value.substring(segmentStart))).toString();
    }
    private List<String> directoryPaths(List<String> values) {
        return values.stream().map(this::directoryPath).collect(Collectors.toList());
    }
    private String blob(String id) {
        if (StringUtils.containsOnly(id, NO_BLOB)) {
            return id;
        }
        return hex(id, id.length());
    }
    private String hex(String value, int length) {
        return hmac.orElseThrow().hashString(value, StandardCharsets.UTF_8).toString().substring(0, length);
    }
    /**
     * A key is required as soon as the mode is on: a run that hid nothing because its key was missing would send the
     * source the organization asked to keep, so it fails before the build starts.
     */
    public static SourceHiding of(boolean hidden, Optional<String> key) {
        if (hidden) {
            String secret = key.filter(StringUtils::isNotBlank)
                    .orElseThrow(() -> new IllegalArgumentException("codiqo.sourceHidden needs codiqo.sourceHashKey, a secret of at least " + MIN_KEY_LENGTH + " characters"));
            if (secret.length() >= MIN_KEY_LENGTH) {
                return new SourceHiding(Optional.of(Hashing.hmacSha256(secret.getBytes(StandardCharsets.UTF_8))));
            }
            throw new IllegalArgumentException("codiqo.sourceHashKey must have at least " + MIN_KEY_LENGTH + " characters");
        }
        return NONE;
    }
    /**
     * A reason keeps its general description and loses every file name and path in it: {@code changed files:
     * [billing.sql]} becomes {@code changed files: [******.sql]} and a compiler error's path becomes
     * {@code ******.java}. A name that looks like a file keeps its extension; a qualified class name is masked whole.
     * Bare identifiers a message quotes (a symbol a compiler could not find) are not recognised and stay.
     */
    public static String reason(String reason) {
        StringBuilder toReturn = new StringBuilder();
        Matcher name = REASON_NAME.matcher(reason);
        int copied = 0;
        while (name.find()) {
            String found = name.group();
            if (HAS_LETTER.matcher(found).find()) {
                String extension = FilenameUtils.getExtension(found.substring(Math.max(found.lastIndexOf(IOUtils.DIR_SEPARATOR_UNIX), found.lastIndexOf(IOUtils.DIR_SEPARATOR_WINDOWS)) + 1));
                toReturn.append(reason, copied, wordsBefore(reason, name.start(), copied));
                toReturn.append(REASON_EXTENSION.matcher(extension).matches() ? MASK + FilenameUtils.EXTENSION_SEPARATOR + extension : MASK);
                copied = name.end();
            }
        }
        return toReturn.append(reason.substring(copied)).toString();
    }
    /** where a name's words start: back over every space-separated word up to a delimiter, the start or {@code floor} */
    private static int wordsBefore(String reason, int start, int floor) {
        int toReturn = start;
        while (toReturn > floor && Character.isWhitespace(reason.charAt(toReturn - 1))) {
            int word = toReturn - 1;
            while (word > floor && isWordOfName(reason.charAt(word - 1))) {
                word--;
            }
            if (word == toReturn - 1) {
                return toReturn;
            }
            toReturn = word;
        }
        return toReturn;
    }
    private static boolean isWordOfName(char c) {
        return BooleanUtils.and(new boolean[] { BooleanUtils.negate(Character.isWhitespace(c)), REASON_DELIMITERS.indexOf(c) < 0 });
    }
    private static <T> void replace(Supplier<T> getter, Consumer<T> setter, UnaryOperator<T> hide) {
        T value = getter.get();
        if (Objects.nonNull(value)) {
            setter.accept(hide.apply(value));
        }
    }
}
