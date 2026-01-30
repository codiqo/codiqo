# Codiqo Development Guidelines

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

## Utility Libraries - Guava, Apache Commons & Lombok

Guava, Apache Commons, and Lombok are always on the classpath. **Prefer these utilities over manual implementations**.

### Math - Use Apache Commons Math

```java
// Good
import org.apache.commons.math3.util.Precision;
double rounded = Precision.round(value, 2);

// Avoid
double rounded = Math.round(value * 100.0) / 100.0;
```

### Collections - Use Guava

Prefer Guava factory methods over `new` constructors:

```java
// Good
List<String> items = Lists.newArrayList();
Set<String> unique = Sets.newHashSet();
Map<String, Integer> counts = Maps.newHashMap();

// Avoid
List<String> items = new ArrayList<>();
Set<String> unique = new HashSet<>();
Map<String, Integer> counts = new HashMap<>();
```

### Lombok @Builder with Collections

**When using Lombok `@Builder`, all collection fields (List, Set, Map) must have `@Builder.Default` with Guava factory initialization**:

```java
// Good - @Builder.Default with Guava factory
@Data
@Builder
public class Response {
    @Builder.Default
    private List<String> items = Lists.newArrayList();
    @Builder.Default
    private Set<Integer> ids = Sets.newHashSet();
    @Builder.Default
    private Map<String, Object> metadata = Maps.newHashMap();
}

// Bad - no @Builder.Default (builder creates null collections)
@Data
@Builder
public class Response {
    private List<String> items;  // Will be null when built!
    private Set<Integer> ids;
    private Map<String, Object> metadata;
}

// Bad - @Builder.Default without Guava
@Data
@Builder
public class Response {
    @Builder.Default
    private List<String> items = new ArrayList<>();  // Use Lists.newArrayList()
}
```

This ensures:
- Collections are never null when using the builder
- Consistent use of Guava factories
- Safe iteration without null checks

### Strings - Use Apache Commons

```java
// Good
if (StringUtils.isBlank(input)) { ... }
String result = StringUtils.defaultIfEmpty(value, "default");
String joined = StringUtils.join(items, ", ");

// Avoid
if (input == null || input.trim().isEmpty()) { ... }
```

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

### 3. Use `Boolean.FALSE.equals()` only as last resort

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
