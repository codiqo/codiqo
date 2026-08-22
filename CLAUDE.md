# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

# Codiqo Development Guidelines

## Multi-Language by Design

**Codiqo analyzes Java today, but is designed as a multi-language tool.** Kotlin, Scala, and JavaScript/TypeScript are expected next, and most analysis logic should generalize. Engineer with that constraint in mind:

- **Name by capability, not by language.** Prefer `requiresLineFiltering`, `isStructuredLanguage`, `hasJvmTypes` over `isJava` whenever the underlying logic isn't Java-exclusive. A predicate that happens to return true only for Java today, but conceptually applies to Kotlin/Scala tomorrow, must not be named after Java.
- **Avoid hard-coded language branches in shared code.** Don't sprinkle `if (isJava)` through general modules — push the language-specific behavior behind a capability check (`file.getLanguage().requiresLineFiltering()`) or into the language-specific module.
- **Keep language-specific code in language-specific modules.** Java-only logic belongs in `lang-java/` (e.g., bytecode parsing, JDT integration, PMD rules). Shared modules (`api/`, `llm/`, `maven-plugin/`) should treat language as data, not as a hardcoded assumption.
- **Question every Java reference.** When you write `Java`, `java`, or `.java` in a name, type, or string in shared code, stop and ask: "Would this same logic apply to Kotlin or JavaScript?" If yes, rename or abstract before committing.
- **Bad:** `boolean isJava = isJavaFile(file);` used to gate line-filtering.
- **Good:** `boolean requiresLineFiltering = LanguageCapabilities.requiresLineFiltering(file);` — the predicate names the capability, and the per-language answer lives in one place (a `LanguageCapabilities` `@UtilityClass`, since `LanguageEnum` is generated from OpenAPI and can't host instance methods).

## Build Facts Come From the Build Tool

**Never infer a build fact from the directory layout.** Where a module's classes, coverage data or test reports live is
a build-tool decision, and every one of those locations is configurable. Ask Maven or Gradle for it and propagate the
answer through `ProjectSpec`/`JvmProjectSpec` — the way `coverage()` and `getTestReportDirectories()` already do. The
analysis side must never rebuild one path from another.

- **Maven:** read the effective POM — `MavenProject.getPlugin(...)`, then the plugin-level and per-execution `Xpp3Dom`
  configuration, falling back to the plugin's documented default. `SurefirePlugins` and `TestReportDirectories` are the
  pattern to copy. Remember that a plugin parameter with no `<expression>` in its descriptor (surefire's
  `reportsDirectory`, for one) cannot be overridden with `-D`, so the configured value is the only source of truth.
- **Gradle:** read the task or extension that owns the answer — `test.getReports().getJunitXml().getOutputLocation()`,
  `sourceSet.getJava().getClassesDirectory()` — in `GradleModelCollector`, and carry it across the process boundary in
  `ModuleData`.
- If codiqo depends on a build setting being enabled, pin it where codiqo already owns the task (`ownJacoco`,
  `ownTestExecution`) instead of hoping the project left it alone.

```java
// Bad - walks up from target/classes and straight off the end of the repository
File buildDir = outputDir.getParentFile();
File gradleBuild = buildDir.getParentFile().getParentFile();   // for Maven this is ABOVE the module
if (containsJunitReports(new File(gradleBuild, "test-results"))) { ... }

// Good - the build tool said where the reports are, so only look there
return jvm.getTestReportDirectories().stream().anyMatch(JavaLanguageSpec::containsJunitReports);
```

**A corner-case patch is not a fix.** When a guard exists to paper over a wrong model — a path-suffix comparison
standing in for a real relativization, a fallback that derives a file name from a class name — correct the model and
delete the guard. Prefer the fundamental change even when it touches more files than the workaround would.

## Code Organization

### Method Ordering in Classes
1. **Non-static methods first**, then static methods
2. Within each group, order by importance: **complex/high-level → utility/helper**
3. Public API methods come before private implementation details

```java
public class Example {
    private static final int THRESHOLD = 10;
    private final Config config;
    public Example(Config config) { ... }
    public Result process(Input input) { ... }
    private void doComplexWork() { ... }
    private void doSimpleWork() { ... }
    private static Result compute(Data data) { ... }
    private static boolean isValid(String s) { ... }
    private static class Helper { ... }
}
```

### Static Methods
**If a method can be declared static, it should be**. Methods that don't access instance state should be static:

```java
// Good - method doesn't use instance fields, so it's static
private static String formatScore(double value) {
    return String.format("%.2f", value);
}

// Bad - method could be static but isn't
private String formatScore(double value) {
    return String.format("%.2f", value);
}
```

### Class Body Spacing
**No blank line after class declaration**. The first member should immediately follow the opening brace.

**One blank line before the first method/constructor** to separate fields from behavior:

```java
// Good
public class Example {
    private static final int THRESHOLD = 10;
    private final Config config;

    public Example(Config config) { ... }
    public void process() { ... }
}

// Bad - blank line after class declaration
public class Example {

    private static final int THRESHOLD = 10;
}

// Bad - no blank line before first method
public class Example {
    private final Config config;
    public Example(Config config) { ... }
}
```

### Field Spacing
**Blank line between fields when at least one has an annotation**. When fields carry annotations (e.g. `@Inject`, `@Parameter`, `@Nullable`), separate them with blank lines for readability. Plain fields without annotations stay together.

**Blank line between static and non-static field groups**:

```java
// Good - static constants together, blank line before non-static, annotated fields separated
public static final String JAR_EXTENSION = "jar";
public static final String LOMBOK_GROUP_ID = "org.projectlombok";

@Inject
private RuntimeInformation runtimeInformation;

@Inject
protected RepositorySystem repositorySystem;

@Parameter(property = "codiqo.javaHome")
protected File javaHome;

@Parameter(property = "codiqo.mavenHome")
protected File mavenHome;

// Good - plain non-static fields without annotations stay together
private final Config config;
private final String name;

// Bad - no separation between static and non-static
public static final String JAR_EXTENSION = "jar";
@Inject
private RuntimeInformation runtimeInformation;
@Inject
protected RepositorySystem repositorySystem;

// Bad - annotated fields crammed together
@Inject
private RuntimeInformation runtimeInformation;
@Inject
protected RepositorySystem repositorySystem;
@Parameter(property = "codiqo.javaHome")
protected File javaHome;
```

### Method Spacing
**No blank lines between methods**. Methods should follow each other directly:

```java
// Good
public void methodOne() {
    // implementation
}
private void methodTwo() {
    // implementation
}
private void methodThree() {
    // implementation
}

// Bad - unnecessary blank lines
public void methodOne() {
    // implementation
}

private void methodTwo() {
    // implementation
}

private void methodThree() {
    // implementation
}
```

### Method/Constructor Body Grouping
**Use blank lines inside method and constructor bodies to separate logical groups of statements.** Each group typically creates/configures a distinct object or performs a distinct conceptual step. This creates visual "paragraphs" that make the flow readable:

```java
// Good - blank lines separate logical groups
public HtmlReportBuilder(RunArgs args) {
    this.args = Objects.requireNonNull(args);

    ClassLoaderTemplateResolver htmlResolver = new ClassLoaderTemplateResolver();
    htmlResolver.setPrefix("thymeleaf/html/");
    htmlResolver.setSuffix(".html");
    htmlResolver.setTemplateMode(TemplateMode.HTML);
    htmlResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
    htmlResolver.setCacheable(true);

    templateEngine = new TemplateEngine();
    templateEngine.setTemplateResolver(htmlResolver);

    ClassLoaderTemplateResolver textResolver = new ClassLoaderTemplateResolver();
    textResolver.setPrefix("thymeleaf/templates/");
    textResolver.setSuffix(".txt");
    textResolver.setTemplateMode(TemplateMode.TEXT);
    textResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
    textResolver.setCacheable(true);

    textTemplateEngine = new TemplateEngine();
    textTemplateEngine.setTemplateResolver(textResolver);
}

// Bad - wall of code without visual separation
public HtmlReportBuilder(RunArgs args) {
    this.args = Objects.requireNonNull(args);
    ClassLoaderTemplateResolver htmlResolver = new ClassLoaderTemplateResolver();
    htmlResolver.setPrefix("thymeleaf/html/");
    htmlResolver.setSuffix(".html");
    htmlResolver.setTemplateMode(TemplateMode.HTML);
    htmlResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
    htmlResolver.setCacheable(true);
    templateEngine = new TemplateEngine();
    templateEngine.setTemplateResolver(htmlResolver);
    ClassLoaderTemplateResolver textResolver = new ClassLoaderTemplateResolver();
    textResolver.setPrefix("thymeleaf/templates/");
    textResolver.setSuffix(".txt");
    textResolver.setTemplateMode(TemplateMode.TEXT);
    textResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
    textResolver.setCacheable(true);
    textTemplateEngine = new TemplateEngine();
    textTemplateEngine.setTemplateResolver(textResolver);
}
```

Group boundaries typically occur:
- After field assignments / `this.x = ...` blocks
- Between creation + configuration of different objects
- Before a return statement when preceded by computation
- Between setup, execution, and result-processing phases
- Between delegate method calls grouped by parameter affinity
- Between each "default + conditional override" block

**Delegate calls grouped by parameter affinity** — when calling multiple helper methods, group them by what they operate on and separate groups with blank lines:

```java
// Good - response-only populators together, request-dependent ones separate
populateEffortBreakdown(ctx, response, result.getPreComputedScores());
populateQualityMultiplier(ctx, response);
populateArchitectureBonus(ctx, response);
populateRiskAssessment(ctx, response);
populateStaticAnalysisReview(ctx, response);

populateBlastRadius(ctx, response, request);
populateCpdDetails(ctx, request);

// Bad - all mixed together without grouping
populateEffortBreakdown(ctx, response, result.getPreComputedScores());
populateQualityMultiplier(ctx, response);
populateBlastRadius(ctx, response, request);
populateArchitectureBonus(ctx, response);
populateCpdDetails(ctx, request);
populateRiskAssessment(ctx, response);
populateStaticAnalysisReview(ctx, response);
```

**Each "default + conditional override" block is its own paragraph** — when a variable has a default value and a conditional override, that entire unit (declaration + if + setVariable) forms one paragraph:

```java
// Good - blank line between each self-contained block
List<String> techTags = Collections.emptyList();
if (Objects.nonNull(response.getTags()) && Objects.nonNull(response.getTags().getTechnical())) {
    techTags = response.getTags().getTechnical();
}
ctx.setVariable("technicalTags", techTags);

List<String> funcTags = Collections.emptyList();
if (Objects.nonNull(response.getTags()) && Objects.nonNull(response.getTags().getFunctional())) {
    funcTags = response.getTags().getFunctional();
}
ctx.setVariable("functionalTags", funcTags);

// Bad - no separation between blocks
List<String> techTags = Collections.emptyList();
if (Objects.nonNull(response.getTags()) && Objects.nonNull(response.getTags().getTechnical())) {
    techTags = response.getTags().getTechnical();
}
ctx.setVariable("technicalTags", techTags);
List<String> funcTags = Collections.emptyList();
if (Objects.nonNull(response.getTags()) && Objects.nonNull(response.getTags().getFunctional())) {
    funcTags = response.getTags().getFunctional();
}
ctx.setVariable("functionalTags", funcTags);
```

**Batch of simple direct assignments stays together** — consecutive single-line assignments without logic don't need blank lines between them:

```java
// Good - direct assignments form one continuous block
ctx.setVariable("commitId", reportContext.getCommitId());
ctx.setVariable("author", reportContext.getAuthor());
ctx.setVariable("authorEmail", reportContext.getAuthorEmail());
ctx.setVariable("timestamp", reportContext.getTimestamp());
ctx.setVariable("message", reportContext.getCommitMessage());
```

### Inline Single-Use Variables
**Don't create intermediate variables that are only used once as a direct argument.** Inline the expression:

```java
// Good - inline single-use value
ctx.setVariable("findings", buildFindings(response));
ctx.setVariable("recommendations", Optional.ofNullable(response.getReasons()).orElse(Collections.emptyList()));
ctx.setVariable("totalFilesChanged", request.getFileChanges().size());

// Bad - unnecessary intermediate variables
List<FindingView> findings = buildFindings(response);
ctx.setVariable("findings", findings);
List<String> recommendations = Optional.ofNullable(response.getReasons()).orElse(Collections.emptyList());
ctx.setVariable("recommendations", recommendations);
int totalFiles = request.getFileChanges().size();
ctx.setVariable("totalFilesChanged", totalFiles);
```

Exception: Keep the variable when used more than once, when the expression is very long and naming aids readability, or when debugging requires inspecting the value.

## Magic Numbers

**Extract magic numbers into named constants** to improve readability:

```java
// Good
private static final double TEST_CODE_PENALTY_WEIGHT = 0.2;
private static final double OVERLAP_THRESHOLD = 0.4;

effectivePenalty += clone.isAllTestCode() ? TEST_CODE_PENALTY_WEIGHT : 1.0;
boolean introduced = overlapRatio > OVERLAP_THRESHOLD;

// Bad - magic numbers inline
effectivePenalty += clone.isAllTestCode() ? 0.2 : 1.0;
boolean introduced = overlapRatio > 0.4;
```

Exception: Common values like `0`, `1`, `-1`, `100` in obvious contexts (loop bounds, percentages) don't need constants.

## Null Checks

Use `Objects.isNull()` and `Objects.nonNull()` for null checks:

```java
// Good
if (Objects.isNull(value)) { ... }
if (Objects.nonNull(value)) { ... }

// Avoid
if (value == null) { ... }
if (value != null) { ... }
```

For simple null fallback values, use `Optional.ofNullable`:

```java
// Good
String name = Optional.ofNullable(user.getName()).orElse("Unknown");
Integer count = Optional.ofNullable(getCount()).orElse(0);

// Avoid
String name = user.getName() != null ? user.getName() : "Unknown";
Integer count = getCount() != null ? getCount() : 0;
```

## Complex Null-Check Ternaries

**For multi-level null checks with fallback defaults, use default assignment + if statement** instead of complex ternary expressions:

```java
// Good - default + if for multi-level null checks
List<String> tags = Collections.emptyList();
if (Objects.nonNull(response.getTags()) && Objects.nonNull(response.getTags().getItems())) {
    tags = response.getTags().getItems();
}

// Good - Optional.ofNullable for simple single-level null fallback (see Null Checks above)
ctx.setVariable("items", Optional.ofNullable(data.getItems()).orElse(Collections.emptyList()));

// Bad - complex ternary with chained null checks
List<String> tags = Objects.nonNull(response.getTags()) && Objects.nonNull(response.getTags().getItems())
        ? response.getTags().getItems()
        : Collections.emptyList();
```

### Never Re-Walk a Getter Chain Inside a Condition

**A compound condition must not evaluate the same getter chain twice.** Operand *count* is not the problem —
`if (enabled && Objects.nonNull(request))` is fine. The problem is re-deriving the same value, which makes the
condition long, hides what is actually being tested, and forces the reader to diff two near-identical chains.

Bind the intermediate once. Where the outer hop is a null check, nesting is what makes the binding possible:

```java
// Bad - the chain is walked 5 times across 6 lines
if (Objects.nonNull(response.getEffortBreakdown())
        && Objects.nonNull(response.getEffortBreakdown().getDiffClassification())
        && CollectionUtils.isNotEmpty(response.getEffortBreakdown().getDiffClassification().getMovedPairs())) {
    log.warn("count=%d", response.getEffortBreakdown().getDiffClassification().getMovedPairs().size());
    response.getEffortBreakdown().getDiffClassification().setMovedPairs(new ArrayList<>());
}

// Good - nest the outer null check so the intermediate can be named
if (Objects.nonNull(submission.getProject())) {
    for (ModuleModel module : CollectionUtils.emptyIfNull(submission.getProject().getModules())) {
        ModuleQualityModel quality = module.getQuality();
        if (Objects.nonNull(quality) && CollectionUtils.isNotEmpty(quality.getCriticalViolations())) { ... }
    }
}
```

When three or more call sites need the same two-hop walk, give it one accessor returning `Optional` rather than
repeating the guard:

```java
private static Optional<DiffClassification> diffClassification(LlmScoringResponse response) {
    return Optional.ofNullable(response.getEffortBreakdown()).map(EffortBreakdown::getDiffClassification);
}

// call sites then read as
diffClassification(response).filter(c -> CollectionUtils.isNotEmpty(c.getMovedPairs())).ifPresent(c -> { ... });
if (diffClassification(response).isEmpty()) { return PerFileResult.empty(); }
```

This does **not** apply to a condition that tests the same cheap accessor with two different predicates
(`block.getLocation().getStartLine() <= start && end <= block.getLocation().getEndLine()`) — that is one range
test, and splitting it obscures the range. Leave those alone.

For nested ternaries, use if/else if:

```java
// Good - if/else if
RiskLevel risk;
if (callers > HIGH_THRESHOLD) {
    risk = RiskLevel.HIGH;
} else if (callers > MODERATE_THRESHOLD) {
    risk = RiskLevel.MODERATE;
} else {
    risk = RiskLevel.LOW;
}

// Bad - nested ternary
RiskLevel risk = callers > HIGH_THRESHOLD ? RiskLevel.HIGH :
        callers > MODERATE_THRESHOLD ? RiskLevel.MODERATE : RiskLevel.LOW;
```

When multiple fields share the same parent null check, restructure with an outer if block or early return:

```java
// Good - outer null check eliminates repeated && chains
if (Objects.isNull(review)) {
    ctx.setVariable("items", Collections.emptyList());
    ctx.setVariable("count", 0);
    return;
}
ctx.setVariable("items", Optional.ofNullable(review.getItems()).orElse(Collections.emptyList()));
ctx.setVariable("count", CollectionUtils.size(review.getItems()));

// Bad - repeated && chains
ctx.setVariable("items", Objects.nonNull(review) && Objects.nonNull(review.getItems()) ? review.getItems() : Collections.emptyList());
ctx.setVariable("count", Objects.nonNull(review) && Objects.nonNull(review.getItems()) ? review.getItems().size() : 0);
```

## Fail Fast - No Defensive Programming

**CRITICAL: NEVER add null/empty checks at the start of methods that silently return.** The caller is responsible for passing valid data. If invalid data is passed, let it fail fast (NPE) so bugs are discovered immediately.

**Forbidden patterns - NEVER do this:**

```java
// FORBIDDEN - defensive null check on method argument
private Result mapSomething(Input input) {
    if (Objects.isNull(input)) {
        return null;  // WRONG! Silent failure hides bugs
    }
    // ... actual logic
}

// FORBIDDEN - defensive null check that returns early
public void mapToResult(Response response, Result result) {
    if (Objects.isNull(response) || Objects.isNull(result)) {
        return;  // WRONG! Caller should ensure valid args
    }
    // ... actual logic
}

// FORBIDDEN - defensive empty check
private void processItems(List<Item> items) {
    if (CollectionUtils.isEmpty(items)) {
        return;  // WRONG! Let caller decide whether to call
    }
    // ... actual logic
}
```

**Correct approach - trust the caller, fail fast:**

```java
// CORRECT - just do the work, NPE if contract violated
private Result mapSomething(Input input) {
    return Result.builder()
            .value(input.getValue())  // NPE if input is null - that's correct!
            .build();
}

// CORRECT - caller ensures valid arguments
public void mapToResult(Response response, Result result) {
    result.setScore(response.getScore());  // NPE if null - caller's fault
}

// CORRECT - works with empty list (no items processed), NPE on null
private void processItems(List<Item> items) {
    for (Item item : items) {
        process(item);
    }
}
```

**The only exceptions:**
1. System boundaries (user input, external APIs, public library interfaces)
2. Checking optional **data fields** (not method arguments) from external sources like LLM responses where fields may legitimately be absent

**Mapper methods — null checks belong at call sites, not inside mappers:**

```java
// CORRECT - check the data field at the call site before invoking mapper
if (Objects.nonNull(llmResponse.getRiskAssessment())) {
    result.setRiskAssessment(mapRiskAssessment(llmResponse.getRiskAssessment()));
}

// CORRECT - for enum fields with a default, use Optional at call site
result.setRiskLevel(Optional.ofNullable(riskAssessment.getRiskLevel())
        .map(Mapper::mapRiskLevel).orElse(RiskLevelEnum.LOW));

// CORRECT - for list fields, use Optional at call site
toReturn.setItems(Optional.ofNullable(source.getItems())
        .map(Mapper::mapItems).orElse(Collections.emptyList()));

// FORBIDDEN - null/empty guard inside the mapper method itself
private static RiskAssessmentModel mapRiskAssessment(RiskAssessment riskAssessment) {
    if (Objects.isNull(riskAssessment)) { return null; }  // WRONG! Check at call site
    // ...
}
private static List<Item> mapItems(List<SourceItem> items) {
    if (CollectionUtils.isEmpty(items)) { return Collections.emptyList(); }  // WRONG!
    // ...
}
```

## Return Variable Naming

Use `toReturn` for method return variables:

```java
// Good
public Result buildResult() {
    Result toReturn = new Result();
    toReturn.setValue(compute());
    return toReturn;
}

// Also acceptable for simple cases
public int calculate() {
    return a + b;
}
```

## Utility Libraries - Apache Commons & Lombok

Apache Commons and Lombok are always on the classpath. **Prefer these utilities over manual implementations**.

Before hand-rolling anything — a file filter, a path join, a separator character, a null-safe default, a value class —
check whether commons-lang3, commons-io, commons-collections4, commons-math3, ASM or Lombok already ships it. They
usually do, and the shipped version already handles the edge case the hand-rolled one is about to get wrong.

### Math - Use Apache Commons Math

```java
// Good
import org.apache.commons.math3.util.Precision;
double rounded = Precision.round(value, 2);

// Avoid
double rounded = Math.round(value * 100.0) / 100.0;
```

### Collections - JDK Constructors & Apache Commons

Plain collections use the JDK constructors directly; immutable ones use the JDK factories:

```java
// Good
List<String> items = new ArrayList<>();
Set<String> unique = new HashSet<>();
Map<String, Integer> counts = new HashMap<>();
Set<String> fixed = Set.of("a", "b");
```

For the structures the JDK does not provide, use commons-collections4:

```java
// Good
BidiMap<String, String> bidi = new DualHashBidiMap<>();   // bidirectional lookup, .getKey() for the reverse
MultiValuedMap<String, Item> byKey = new HashSetValuedHashMap<>();
MultiSet<String> counts = new HashMultiSet<>();           // .getCount(x), .setCount(x, n), .uniqueSet()
```

`Bag`/`HashBag` are **deprecated since commons-collections4 4.6.0** — `Bag` violated the `Collection`
contract. Use `MultiSet`/`HashMultiSet` instead.

### Lombok @UtilityClass for Static-Only Classes

**Any class whose members are all static must be annotated with Lombok `@UtilityClass`.** The annotation marks the class final, hides the implicit default constructor, and makes every method/field static automatically — so you don't have to repeat `static` on each member or hand-write a private constructor.

```java
// Good - @UtilityClass handles final + private constructor + static promotion
import lombok.experimental.UtilityClass;

@UtilityClass
public class GitRefs {
    private static final Pattern HEAD_PATTERN = Pattern.compile(...);

    public String stripPrefix(String refName) { ... }
    public List<String> parentShas(RevCommit commit) { ... }
}

// Bad - hand-rolled utility class (noisy, easy to forget the private constructor)
public final class GitRefs {
    private GitRefs() {
    }

    public static String stripPrefix(String refName) { ... }
    public static List<String> parentShas(RevCommit commit) { ... }
}
```

Apply this to every existing and new utility class. Examples already in the codebase: [CommitRevertDetector](api/src/main/java/io/codiqo/util/CommitRevertDetector.java), [DriverScore](api/src/main/java/io/codiqo/api/metrics/DriverScore.java), [CodeLineCounter](api/src/main/java/io/codiqo/api/metrics/CodeLineCounter.java).

### Lombok @Builder with Collections

**When using Lombok `@Builder`, all collection fields (List, Set, Map) must have `@Builder.Default` initialized to an empty collection**:

```java
// Good - @Builder.Default with an empty collection
@Data
@Builder
public class Response {
    @Builder.Default
    private List<String> items = new ArrayList<>();
    @Builder.Default
    private Set<Integer> ids = new HashSet<>();
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}

// Bad - no @Builder.Default (builder creates null collections)
@Data
@Builder
public class Response {
    private List<String> items;  // Will be null when built!
    private Set<Integer> ids;
    private Map<String, Object> metadata;
}
```

This ensures:
- Collections are never null when using the builder
- Safe iteration without null checks

### Lombok @Value Instead of Java Records

**Do not declare Java `record`s.** Immutable value objects use Lombok `@Value`; mutable holders use `@Data`. Every data
class in the codebase is then generated the same way.

```java
// Good
@Value
private static class CoverageTotals {
    int classes;
    int packages;
    int lines;
    int coveredLines;
}

// Bad
private record CoverageTotals(int classes, int packages, int lines, int coveredLines) {}
```

`@Value` supplies final fields, getters, `equals`/`hashCode`/`toString` and an all-args constructor, so it is a direct
replacement — but the accessors are named differently and three details bite:

- **Accessors become `getXxx()`, and a `boolean` field becomes `isXxx()`** — `timedOut` reads as `isTimedOut()`, never
  `getTimedOut()`. Method *references* break as well (`FileRow::path` becomes `FileRow::getPath`), and javac reports
  those as "invalid method reference" rather than "cannot find symbol", so grepping for `.method()` misses them.
- **A hand-written constructor suppresses the generated one.** `@Value` omits its all-args constructor as soon as any
  constructor exists, so a class that keeps a convenience constructor must add `@AllArgsConstructor` explicitly.
- **In a `sealed` hierarchy, write `final` yourself.** A permitted subclass has to be `final`, `sealed` or `non-sealed`
  when javac checks the permits clause; do not depend on Lombok adding `final` during annotation processing.

Nested classes need an explicit `static` (records were implicitly static). A hand-written `toString()` is fine — Lombok
skips generating any method that already exists.

The one exception is Java source embedded in a test fixture *as a string* (the PMD type-inference crash reproduction,
for example): that is input data, not project code.

### Strings - Use Apache Commons

**Use Apache Commons for null-safe checks and defaults:**

```java
// Good
if (StringUtils.isBlank(input)) { ... }
String result = StringUtils.defaultIfEmpty(value, "default");

// Avoid
if (input == null || input.trim().isEmpty()) { ... }
```

**Use `io.codiqo.util.Split` for splitting strings** — it trims every part and drops the empty ones, so a
caller never has to post-process the result:

```java
// Good - Split trims and omits empties
List<String> items = Split.on(input, ',');
List<String> lines = Split.on(text, '\n');
List<String> words = Split.onWhitespace(input);

// Avoid - manual split + trim
String[] items = input.split(",");
Arrays.stream(items).map(String::trim).filter(s -> !s.isEmpty())...
```

**Use Apache Commons for joining strings** — `StringUtils.join` is null-safe on the collection itself:

```java
// Good
String joined = StringUtils.join(items, ", ");
String joined = StringUtils.joinWith(", ", first, second);
String joined = items.stream().map(Item::getName).collect(Collectors.joining(", "));
```

### Character and String Constants

**Never write a bare `'\n'`, `"\n"` or `""`.** commons-lang3 publishes both the `char` and the `String` form of each,
and mixing literals with constants is how one separator ends up spelled three different ways in a single module.

| Literal | Constant |
| --- | --- |
| `'\n'` (char argument) | `CharUtils.LF` |
| `"\n"` (String argument) | `StringUtils.LF` |
| `'\r'` / `"\r"` | `CharUtils.CR` / `StringUtils.CR` |
| `""` | `StringUtils.EMPTY` |
| `" "` | `StringUtils.SPACE` |
| `"."` in front of an extension | `FilenameUtils.EXTENSION_SEPARATOR_STR` |

```java
// Good - pick the form the API actually takes
StringUtils.splitPreserveAllTokens(report, CharUtils.LF);        // takes a char
StringUtils.substringBefore(message, StringUtils.LF);            // takes a String
String.join(StringUtils.LF, lines);
FileFilterUtils.suffixFileFilter(FilenameUtils.EXTENSION_SEPARATOR_STR + CLASS_EXTENSION);

// Bad - raw literals
StringUtils.splitPreserveAllTokens(report, '\n');
String.join("\n", lines);
log.info("");

// Bad - rebuilding a constant that already exists
private static final String LF = CharUtils.toString(CharUtils.LF);
```

`IOUtils.LINE_SEPARATOR` is deprecated: use `StringUtils.LF` for the unix form, and `System.lineSeparator()` only when
the platform's own separator is genuinely what is wanted.

Exception: a `\n` inside a larger literal is content, not a separator — leave `"line one\nline two"` alone.

### File Paths - Use Apache Commons IO

Use `FilenameUtils` for file path operations like getting extensions:

```java
// Good
import org.apache.commons.io.FilenameUtils;
String extension = FilenameUtils.getExtension(path);  // returns "java" for "File.java"
String baseName = FilenameUtils.getBaseName(path);    // returns "File" for "File.java"
String name = FilenameUtils.getName(path);            // returns "File.java" for "/path/to/File.java"

// Avoid
int dotIdx = path.lastIndexOf('.');
String extension = dotIdx > 0 ? path.substring(dotIdx + 1) : null;
```

For checking file types, prefer extension comparison over `endsWith`:

```java
// Good
if ("java".equals(FilenameUtils.getExtension(path))) { ... }

// Avoid
if (path.endsWith(".java")) { ... }
```

**Compose paths with the `Path` API, never with string concatenation.** A path is not a string. An empty component —
JaCoCo reports the default package as `""` — turns `packageName + '/' + fileName` into `/Foo.java`, which matches
nothing, while `Paths.get(packageName, fileName)` correctly yields `Foo.java`. Convert to the unix form with commons-io
where a comparison needs it, so both sides of that comparison are built by the same two primitives:

```java
// Good - the JDK composes, commons-io normalizes the separators
Path relative = Paths.get(packageName, fileName);
String key = FilenameUtils.separatorsToUnix(relative.toString());
String key = FilenameUtils.separatorsToUnix(sourceRoot.relativize(file).toString());   // the same key, other side

// Bad - hand-joined, and wrong for an empty component
String key = packageName + '/' + fileName;

// Bad - String.join has the identical empty-component bug; the problem is treating a path as a string
String key = String.join("/", packageName, fileName);

// Bad - FilenameUtils.concat emits SYSTEM separators and applies `..` normalization, neither of which a lookup key wants
String key = FilenameUtils.concat(packageName, fileName);
```

**Match paths by relativizing against a known root, not by comparing suffixes.**
`path.endsWith("com/example/Foo.java")` also matches `.../notcom/example/Foo.java`, and it cannot tell two same-named
files in different modules apart — which is precisely the situation duplicate-name handling runs in.

```java
// Good - exact, and the key form both JaCoCo and SpotBugs report locations in
Map<String, File> byRelativePath = indexBySourceRootRelativePath(files, sourceRoots);

// Bad
FilenameUtils.separatorsToUnix(location.getPath()).endsWith(packageName + "/" + fileName)
```

**Convert bytecode names with ASM, not with `replace`.** `org.ow2.asm` is a direct dependency and
`io.codiqo.core.java.JavaBinaryFormat` owns both directions:

```java
// Good
JavaBinaryFormat.getBinaryName(internalName);    // Type.getObjectType(n).getClassName()
JavaBinaryFormat.getInternalName(binaryName);

// Bad
cls.getName().replace('/', '.');
```

### Directory Creation - Use Apache Commons IO

Use `FileUtils.forceMkdir()` instead of `File.mkdirs()`. It creates directories recursively and throws `IOException` on failure instead of silently returning `false`:

```java
// Good
import org.apache.commons.io.FileUtils;
FileUtils.forceMkdir(outputDirectory);

// Avoid - silently returns false on failure
outputDirectory.mkdirs();
```

### Collections/Maps/Arrays - Use Apache Commons

Use `isEmpty`/`isNotEmpty` globally for all collection, map, and array checks:

```java
// Good
if (CollectionUtils.isEmpty(items)) { ... }
if (CollectionUtils.isNotEmpty(items)) { ... }
if (MapUtils.isEmpty(map)) { ... }
if (MapUtils.isNotEmpty(map)) { ... }
if (ArrayUtils.isEmpty(array)) { ... }
if (ArrayUtils.isNotEmpty(array)) { ... }
Collection<String> union = CollectionUtils.union(list1, list2);

// Avoid
if (items == null || items.isEmpty()) { ... }
if (items != null && !items.isEmpty()) { ... }
if (array == null || array.length == 0) { ... }
```

## Comments and JavaDoc

**Don't write obvious comments or JavaDoc**. Code should be self-documenting.

```java
// Bad - obvious comment
/** Returns the user's name */
public String getName() { return name; }

// Bad - restating the code
// Increment counter by one
counter++;

// Good - explains non-obvious business logic
// CPD clones in test code get 1/5 penalty weight
effectivePenalty += clone.isAllTestCode() ? 0.2 : 1.0;

// Good - explains "why" not "what"
// Using 40% threshold because minor edits to existing clones
// shouldn't mark the whole clone as "introduced"
boolean introduced = overlapRatio > 0.4;
```

Only add comments when:
- Business logic is non-obvious
- There's a specific reason for an unusual implementation
- External context is needed (e.g., referencing a spec or algorithm)

**No section dividers** - don't use decorative comment blocks to separate code sections:

```java
// Bad - unnecessary section dividers
// ========== CONSTANTS ==========
private static final int THRESHOLD = 10;

// ========== METHODS ==========
public void process() { ... }

// Good - let the code structure speak for itself
private static final int THRESHOLD = 10;

public void process() { ... }
```

**Use Javadoc-style block comments (`/** ... */`) for multiline comments** — including implementation comments inside method bodies. Each continuation line is aligned with a leading `*`. Single-line comments stay as `//`:

```java
// Good - multiline comment uses a Javadoc-style block
/**
 * entries are server-derived from the diff (DiffClassificationDeriver), so totals match
 * the effective targets by construction — a mismatch means candidate filtering drifted
 */
if (addedTotal != fc.getLinesAdded()) { ... }

// Good - single-line comment stays as //
// explicit "perFile": null from the LLM bypasses the @Builder.Default empty list

// Bad - stacked single-line comments for one multiline note
// entries are server-derived from the diff (DiffClassificationDeriver), so totals match
// the effective targets by construction — a mismatch means candidate filtering drifted
if (addedTotal != fc.getLinesAdded()) { ... }
```

## Boolean Checks

Use `Boolean.TRUE.equals()` for nullable Boolean fields:

```java
// Good - safe for nullable Boolean
if (Boolean.TRUE.equals(file.getIsTest())) { ... }

// Risky - NPE if getIsTest() returns null
if (file.getIsTest()) { ... }
```

**Use Apache `BooleanUtils` for combining multiple independent boolean conditions** - even for just 2 conditions:

```java
// Good - clear intent with BooleanUtils for independent conditions
if (BooleanUtils.or(new boolean[]{isDeleted, isEmpty})) { ... }
if (BooleanUtils.or(new boolean[]{condition1, condition2, condition3})) { ... }
if (BooleanUtils.and(new boolean[]{isValid, isEnabled, hasPermission})) { ... }

// Bad - use BooleanUtils instead of operators for independent conditions
if (isDeleted || isEmpty) { ... }
if (condition1 || condition2 || condition3) { ... }
if (isValid && isEnabled && hasPermission) { ... }
```

**Exception:** Keep `&&`/`||` when short-circuit evaluation is required for null safety:

```java
// OK - short-circuit needed: second condition depends on first being non-null
if (Objects.nonNull(response.getData()) && Objects.nonNull(response.getData().getValue())) { ... }

// WRONG - BooleanUtils evaluates all conditions eagerly, causing NPE
if (BooleanUtils.and(new boolean[]{
        Objects.nonNull(response.getData()),
        Objects.nonNull(response.getData().getValue())})) { ... }  // NPE if getData() is null!
```

## Avoid Negated Conditions

**Always prefer positive/straight conditions over negated ones.** Negated conditions (`if (!something)`) are harder to read. Use these strategies in order of preference:

### 1. Use positive-form APIs when available

```java
// Good - positive API exists
if (opt.isEmpty()) { ... }
if (CollectionUtils.isNotEmpty(items)) { ... }
if (Objects.nonNull(value)) { ... }
if (StringUtils.isNotBlank(input)) { ... }

// Bad - negated condition when positive API exists
if (!opt.isPresent()) { ... }
if (!CollectionUtils.isEmpty(items)) { ... }
if (!Objects.isNull(value)) { ... }
if (!StringUtils.isBlank(input)) { ... }
```

### 2. Restructure logic — success case first, fallback second

**Always test the positive/success condition first**, handle error or fallback after. Prefer restructuring over `Boolean.FALSE.equals()`:

```java
// Good - success case first, error is the fallback
try (Response response = client.execute(request)) {
    if (response.isSuccessful()) {
        return parseResponse(response);
    }
    throw new IOException("Request failed: " + response.code());
}

// Bad - testing failure first
try (Response response = client.execute(request)) {
    if (Boolean.FALSE.equals(response.isSuccessful())) {
        throw new IOException("Request failed: " + response.code());
    }
    return parseResponse(response);
}

// Good - wrap method body in positive condition
public void accept(Context ctx) {
    if (ctx.getArgs().isEnabled()) {
        // ... main logic
    }
}

// Bad - negated guard clause
public void accept(Context ctx) {
    if (Boolean.FALSE.equals(ctx.getArgs().isEnabled())) {
        return;
    }
    // ... main logic
}

// Good - swap branches to use positive condition
if (field.getType().equals(boolean.class)) {
    builder = builder.hasArg(false);
} else {
    builder = builder.hasArg();
}

// Good - wrap in positive condition (when body is short)
for (CodeUnitModel codeUnit : file.getCodeUnits()) {
    if (isMethodOrConstructor(codeUnit.getKind())) {
        toReturn.add(mapMethodChange(file, codeUnit, fileContext));
    }
}

// Good - wrap logic in positive null check instead of bare return
private static void populateBonus(Context ctx, Bonus bonus) {
    ctx.setVariable("bonus", bonus);
    if (Objects.nonNull(bonus)) {
        ctx.setVariable("score", bonus.getScore());
        ctx.setVariable("calculation", bonus.getCalculation());
    }
}

// Bad - null-check guard clause with bare return
private static void populateBonus(Context ctx, Bonus bonus) {
    ctx.setVariable("bonus", bonus);
    if (Objects.isNull(bonus)) {
        return;
    }
    ctx.setVariable("score", bonus.getScore());
    ctx.setVariable("calculation", bonus.getCalculation());
}
```

**Exception:** Early return is acceptable when the null case has meaningful work (setting default values for multiple variables):

```java
// OK - null case sets multiple default values before returning
private static void populateReview(Context ctx, Review review) {
    if (Objects.isNull(review)) {
        ctx.setVariable("items", Collections.emptyList());
        ctx.setVariable("count", 0);
        return;
    }
    // ... main logic using review
}
```

### 3. Stream filters — use `Predicate.not()` instead of lambda negation

For negated stream filters (and `removeIf`/`anyMatch` predicates), use a statically imported `Predicate.not()` with a method reference instead of a negating lambda. Compound negations become chained filters:

```java
import static java.util.function.Predicate.not;

// Good - negation is explicit and up front, no lambda variable
blocks.stream().filter(not(CodeBlockEffort::isConfig))...
callers.stream().filter(not(CallerInfo::isTestCaller)).count();

// Good - compound negation becomes chained not() filters
lines.stream()
        .filter(not(cosmeticDeleted::contains))
        .filter(not(movedDeleted::contains))
        .toList();

// Bad - negation buried inside a lambda
blocks.stream().filter(cbe -> !cbe.isConfig())...
lines.stream().filter(n -> !cosmeticDeleted.contains(n) && !movedDeleted.contains(n))...
```

Exception: when no method reference exists (the call takes an argument computed from the element, e.g. `name -> !name.contains("$")`), `Predicate.not` adds nothing — prefer a positive-form API (`StringUtils.isNotEmpty`, etc.) or keep the lambda.

### 4. Use `Boolean.FALSE.equals()` only as last resort

Only use when **both** conditions are met:
- No positive-form API exists
- Restructuring would add excessive nesting (2+ levels) to already deeply nested code

```java
// Acceptable ONLY when deeply nested and no restructure possible
if (Boolean.FALSE.equals(isMethodOrConstructor(codeUnit.getKind()))) {
    continue;
}
```

## Enum Comparisons - Use EnumSet

**Never chain enum comparisons with `||`**. Instead, use a constant `EnumSet` and check via `contains()`:

```java
// Good - EnumSet constant with contains()
private static final EnumSet<RiskLevel> HIGH_RISK_LEVELS = EnumSet.of(RiskLevel.HIGH, RiskLevel.VERY_HIGH, RiskLevel.CRITICAL);
private static final EnumSet<ModuleType> SHARED_MODULE_TYPES = EnumSet.of(ModuleType.CORE_LIBRARY, ModuleType.SHARED_UTILITY);

public boolean isHighRisk() {
    return HIGH_RISK_LEVELS.contains(riskAssessment.getRiskLevel());
}

public boolean isSharedModule() {
    return SHARED_MODULE_TYPES.contains(moduleType);
}

// Bad - chained enum comparisons
public boolean isHighRisk() {
    RiskLevel level = riskAssessment.getRiskLevel();
    return level == RiskLevel.HIGH || level == RiskLevel.VERY_HIGH || level == RiskLevel.CRITICAL;
}

public boolean isSharedModule() {
    return moduleType == ModuleType.CORE_LIBRARY || moduleType == ModuleType.SHARED_UTILITY;
}
```

Benefits:
- More readable and maintainable
- Easy to add/remove values from the set
- `EnumSet` is highly optimized (bit vector internally)

## Switch Statements

**Switch statements on enums must throw on unknown values**, never return null or silently ignore:

```java
// Good - explicit failure on unknown values
switch (changeType) {
    case ADD:
        return FileChangeType.ADDED;
    case MODIFY:
        return FileChangeType.MODIFIED;
    case DELETE:
        return FileChangeType.DELETED;
    default:
        throw new IllegalArgumentException("Unknown change type: " + changeType);
}

// Bad - silent null return hides bugs
switch (changeType) {
    case ADD:
        return FileChangeType.ADDED;
    // ...
    default:
        return null;
}
```

## Optional vs Null

**Prefer `Optional` over returning null** from methods. This makes the contract explicit:

```java
// Good - explicit optional contract
private static Optional<String> extractClassName(String signature) {
    int lastDot = signature.lastIndexOf('.');
    if (lastDot <= 0) {
        return Optional.empty();
    }
    return Optional.of(signature.substring(0, lastDot));
}

// Bad - null return hides optional nature
private static String extractClassName(String signature) {
    int lastDot = signature.lastIndexOf('.');
    if (lastDot <= 0) {
        return null;
    }
    return signature.substring(0, lastDot);
}
```

Exception: When implementing interfaces or working with APIs that expect null.

### NEVER `.orElse(null)`

**`.orElse(null)` is forbidden.** It unwraps an `Optional` straight back into the null it exists to eliminate, so the caller gains nothing and the reader is misled into thinking the value is safe. Consume the `Optional` instead — `ifPresent`, `map`, `filter`, `orElseThrow`, or a real default.

For a builder field that is legitimately absent, set it conditionally rather than passing null into it:

```java
// Good - the field is simply not set when the value is absent
DiagnosticInfo.DiagnosticInfoBuilder toReturn = DiagnosticInfo.builder()
        .ruleId(diag.getRuleId())
        .message(diag.getMessage());

Optional.ofNullable(diag.getSeverity()).map(Mapper::mapDiagnosticSeverity).ifPresent(toReturn::severity);
return toReturn.build();

// Good - consume the Optional at the call site
extractClassName(codeUnit).ifPresent(builder::className);

// Good - a real default, not null
String name = Optional.ofNullable(user.getName()).orElse("Unknown");

// FORBIDDEN - laundering a null through the Optional API
.severity(Optional.ofNullable(diag.getSeverity()).map(Mapper::mapDiagnosticSeverity).orElse(null))
.className(extractClassName(codeUnit).orElse(null))
Repository git = openWorkTree().orElse(null);
```

If a call site can only accept a nullable value (a language construct such as the try-with-resources resource slot, or a third-party API), restructure so the `Optional` never appears — do not bridge the gap with `.orElse(null)`.

## Exception Handling

**Never swallow exceptions silently**:

```java
// Bad
try {
    riskyOperation();
} catch (Exception e) {
    // silent failure
}

// Good - propagate or handle meaningfully
try {
    riskyOperation();
} catch (IOException e) {
    throw new ProcessingException("Failed to process: " + context, e);
}
```

### ExceptionUtils.wrapAndThrow — Always Wrap with `for (;;) {}`

`ExceptionUtils.wrapAndThrow(err)` always throws, but the compiler doesn't know that. **Always** wrap the entire try-catch in `for (;;) {}` — both for methods returning a value and void methods. Void methods add `return;` at the end of the try block. The `for (;;)` gets its own `{}` block with `try` properly indented inside:

```java
// Good - non-void: for(;;) {} wraps the try-catch
public Result apply(Input input) {
    for (;;) {
        try {
            return doWork(input);
        } catch (Exception err) {
            ExceptionUtils.wrapAndThrow(err);
        }
    }
}

// Good - void: for(;;) {} wraps the try-catch, return; at end of try block
public void accept(Context ctx) {
    for (;;) {
        try {
            doWork(ctx);
            return;
        } catch (Exception err) {
            ExceptionUtils.wrapAndThrow(err);
        }
    }
}

// Bad - for(;;) try on same line without {} block
public Result apply(Input input) {
    for (;;) try {
        return doWork(input);
    } catch (Exception err) {
        ExceptionUtils.wrapAndThrow(err);
    }
}

// Bad - fake return value after wrapAndThrow, triggers PMD violations
public Result apply(Input input) {
    try {
        return doWork(input);
    } catch (Exception err) {
        ExceptionUtils.wrapAndThrow(err);
        return null;
    }
}

// Bad - plain try-catch without for(;;), even for void methods
public void accept(Context ctx) {
    try {
        doWork(ctx);
    } catch (Exception err) {
        ExceptionUtils.wrapAndThrow(err);
    }
}
```

**When the try-catch wraps only part of the method body** (setup code before try, or code after), `for (;;) {}` still applies to the try-catch block itself — just ensure `return;` is placed correctly within the try block.

## Avoid Over-Engineering

- Don't add features, refactor code, or make "improvements" beyond what was asked
- Don't add error handling for scenarios that can't happen
- Don't create abstractions for one-time operations
- Three similar lines of code is better than a premature abstraction
- Don't create utility methods with only one usage - inline the code instead. Only extract a method if it's complex enough to warrant it or reused multiple times

```java
// Bad - unnecessary method for single usage
private boolean isValidInput(String s) {
    return StringUtils.isNotBlank(s);
}

public void process(String input) {
    if (isValidInput(input)) { ... }
}

// Good - inline simple logic
public void process(String input) {
    if (StringUtils.isNotBlank(input)) { ... }
}

// Good - extract when complex or reused
private Score calculateWeightedScore(List<Metric> metrics, Map<String, Double> weights) {
    // Complex logic worth extracting
}
```
