# Driver Score — what it is, why it exists, how to read it

This document is the canonical explanation of Codiqo's volume driver metric: what it measures, how it is computed, and how to read it on a commit. The [Reproducibility](#reproducibility) section at the end lists the inputs a score is computed from and the fields the scored result exposes.

---

## TL;DR

Every changed method or constructor gets a **driver score** — a single number that represents "how much work this code block is worth" for volume scoring. It replaces NCSS (non-comment code statements) as the primary driver.

The driver score is a **weighted average of three dimensions, each projected onto lines-equivalent units**:

1. **L** — physical non-comment lines of code **inside the body block** (the `{` … `}` span, the lines holding the braces included)
2. **S** — NCSS (logical statement count)
3. **I** — direct method invocations inside the block

> **Why body-only?** A method whose parameters are formatted one-per-line can have a 6-line signature on a 4-line body. Counting those signature lines in `L` would inflate `lines.p50` upward without inflating `ncss.p50` or `invocations.p50` (both of which ignore the signature's layout: invocations are counted in the body only, and NCSS counts the declaration as a single statement however many lines it spans), so `k_S = lines.p50 / ncss.p50` would lie about how many real *body* lines a statement projects to. Restricting `L` to the body keeps all three numerators on the same footing and makes the projection factors describe what the tooltips claim: "1 statement ≈ K body lines", "1 invocation ≈ K body lines". Signature-area edits aren't lost — on a MODIFY block, added signature lines count toward `linesChanged` (still capped at `L`), and every changed line of an eligible file also passes through the [diff classification](#diff-classification-reduction).

The projection factors `k_S` and `k_I` are derived from the baseline population's **median** (p50) — not its max — so a single outlier block can never distort the scoring of every other block. Scalers are computed **per project, per kind (method vs constructor), per scope (prod vs test)**, so the score auto-calibrates to the codebase's own norms. Factors are taken **as-is** at the bucket level (no clamp) so they faithfully reflect the project's coding style; the `driverFactorMaxDeviation` knob lives one level down and only flags **per-block** outliers (see [Driver-score abuse signals](#driver-score-abuse-signals)).

Per-block driver scores are **never individually clipped**. The size-outlier protection is a **single global ceiling on the sum of all per-block efforts**, sized as a multiple of the project's bucket-aware baseline budget.

---

## Why we moved away from NCSS

NCSS counts logical statements. For traditional imperative Java code this tracks volume well. But NCSS systematically under-counts certain styles:

| Style | Line count | NCSS | Reality |
|---|---|---|---|
| Lombok builder with 20 fields | ~40 | **1** (single chained statement) | Developer made 20 decisions |
| Protobuf message builder | ~30 | **1** | Real configuration work |
| Reactive stream `.map().filter().collect()` | ~10 | **1** | Non-trivial logic |
| Imperative loop with 5 steps | ~5 | **5** | Same volume, higher NCSS |

The last two rows are the pain point: at roughly the same real effort, the imperative loop has 5× the NCSS of the stream pipeline. Reviewers found the difference unjustifiable.

The driver score solves this by combining **three orthogonal signals** that together cover both styles. A builder's `S` is low and — because a same-type fluent run [counts as one invocation](#what-counts-as-an-invocation) — its `I` reflects the number of *distinct* operations it performs, not its setter-chain length, so its volume comes mainly from `L`. An imperative loop has medium `L`, high `S`, medium `I`. A one-line lambda stream has low `L`, low `S`, and an `I` in which only the type-changing steps, the terminal operation and the calls in its lambda bodies count — same-type steps such as `filter` or `sorted` collapse. Each dimension fires on a different kind of work, so composing them gives a balanced measure — and no fluent-chain style (proto/gRPC builders, `return this` setters, same-type stream steps) can run away with the score purely by chain length.

---

## The scaler — a worked example

Before showing the formulas, let's nail down what a "scaler" actually contains. When the Maven or Gradle plugin analyses a commit, it walks every method and every constructor in the project (both production and test code) and records three numbers per block:

- `L` = non-comment code lines **of the body block** (`bodyCodeLines` — the `{` ... `}` span, excluding signature, parameter lines, `throws` clauses, leading annotations, and javadoc)
- `S` = NCSS
- `I` = direct method invocations

These samples are bucketed into **four populations**: `method/prod`, `method/test`, `ctor/prod`, `ctor/test`.

**Trivial blocks are excluded from the populations.** A block is trivial when `NCSS ≤ 2` **and** `directInvocationCount == 0` **and** `cyclo ≤ 1` — the typical getter, setter or single-field-assigning constructor with no branching (NCSS counts the declaration itself, so a one-statement body already reaches 2). Trivial blocks are also skipped when a commit's changes are scored. Keeping them in would drag `min` to `0` and compress the useful signal at the low end of the distribution.

For each population, Codiqo computes a `DimensionStats` value per dimension: `(min, p50, p75, p90, p95, max)`. Imagine the `method/prod` bucket has 1604 non-trivial samples. The dimension stats might look like:

```
method/prod  (N=1604)
  lines:   min=1   p50=6   p75=14   p90=28   p95=40   max=87
  ncss:    min=2   p50=3   p75=8    p90=17   p95=24   max=52
  invocs:  min=0   p50=2   p75=6    p90=14   p95=20   max=41
```

`p50` reads as **"the median value of this dimension across the population"**. For lines, `p50 = 6` means "the typical prod method in this project has 6 non-comment code lines". All three counts are non-negative integers; the only special value is `0`.

From these stats the scaler derives the two **projection factors** used by the driver formula:

- `ncssFactor` (a.k.a. `k_S`) = "how many lines does one statement project to in this project?"
- `invocationsFactor` (a.k.a. `k_I`) = "how many lines does one invocation project to in this project?"

```
k_S = lines.p50 / ncss.p50 = 6 / 3 = 2.0
k_I = lines.p50 / invocs.p50 = 6 / 2 = 3.0
```

These factors are taken **as-is** at the bucket level — no clamp. They describe the project's actual style: in this hypothetical project, one NCSS statement is worth ~2 physical lines of work, one invocation is worth ~3. Suppressing this would erase the signal the projection is trying to surface.

The `driverFactorMaxDeviation` knob (default `0.75`) does **not** clip these bucket factors. It only flags **per-block outliers** — blocks whose own `S/L` or `I/L` ratio deviates more than 75% from the bucket's ratio of medians (`ncss.p50 / lines.p50`, `invocations.p50 / lines.p50`; NEW blocks only), and, when the global cap binds, any block that alone owns more than 75% of the raw effort. See [Driver-score abuse signals](#driver-score-abuse-signals).

### Degenerate dimensions

If `dim.p50 == 0` for either NCSS or invocations (i.e., half or more blocks in the population have zero of that dimension), there is no signal in this project from which to derive a projection factor (in practice only invocations can reach this, since NCSS counts the declaration itself). **The honest read is that the dimension contributes nothing to the projection** — so the factor is set to `0.0`. This is a deliberate change from a previous behavior that silently used `1.0` as a fabricated neutral; using `0.0` says "we have no evidence about this dimension here" instead of pretending each unit is one line.

If `lines.p50 == 0` (degenerate input — every block has zero non-comment lines), the entire scaler is treated as **empty** and `DriverScore.forNew/forModify` short-circuit to `0.0`. This effectively disables driver scoring for that bucket; it should never happen on a real codebase.

---

## How the score is computed

### For a NEW block — all three dimensions contribute

```
driverScore = (W_L · L + W_S · S · k_S + W_I · I · k_I) / TOTAL_WEIGHT
```

With the constants in `DriverScore.java` (`W_L = 1.0`, `W_S = 1.0`, `W_I = 0.5`, `TOTAL_WEIGHT = 2.5`) this is:

```
driverScore = (L + S · k_S + 0.5 · I · k_I) / 2.5
```

Invocations carry **half weight** because a call is already counted as a line and as a statement — they are ~1:1 collinear with NCSS and are not an independent effort axis. Their only extra signal is orchestration/fan-out density, and half weight keeps that without double-counting call-heavy glue code. See the [weights FAQ](#faq).

Each dimension is first projected into lines-equivalent units, then averaged. The result is a line-count-magnitude number directly comparable to the sizes of real methods in the codebase — no post-hoc anchoring step needed.

### For a MODIFY block — NCSS is dropped

```
linesChanged       = min(totalLinesChanged, L)     // effective added + deleted lines, capped at the block's own size
invocationsChanged = effectiveInvocationsChanged   // calls on the block's effective added lines plus calls on its effective deleted lines

driverScore = (W_L · linesChanged + W_I · invocationsChanged · k_I) / (W_L + W_I)
            = (linesChanged + 0.5 · invocationsChanged · k_I) / 1.5
```

`EffectiveChangePopulator` precomputes both inputs once per MODIFY code unit from the unified diff, skipping blank and comment lines and any nested method or constructor: `effectiveLinesChanged` counts the effective added lines in the unit's declaration span, and `effectiveInvocationsChanged` counts the calls on those added lines (same-type fluent runs collapsed) plus the calls on the effective deleted lines anchored in the unit (counted per call). `totalLinesChanged` adds the effective deleted lines anchored in the body to `effectiveLinesChanged`. A 1-line addition in a 100-line method produces `linesChanged = 1`, not an inflated floor value; a 1-line in-place edit (one deleted plus one added line) produces `linesChanged = 2` here, and the [diff classification](#diff-classification-reduction) then bills the pair once.

NCSS is intentionally dropped because lines-changed already captures modification volume — including it would double-count. For a pure refactor that rewrites the algorithm without changing line count, `S` would redundantly echo `L`. Dividing by `MODIFY_WEIGHT = (W_L + W_I) = 1.5` (instead of `TOTAL_WEIGHT = 2.5`) means a fully-modified block (`linesChanged = L`) scores the same magnitude as an equivalent NEW block (somewhat above it when the rewrite is call-heavy, since a call on a deleted line and one on an added line both count).

### What counts as an "invocation"

The invocation counter walks the method's AST and counts one for each of:

- Instance method calls — `foo.bar()`
- Static method calls — `Utility.process()`
- Constructor calls — `new Foo(...)`
- Explicit constructor invocations — `this(...)`, `super(...)`
- Method references — `Foo::bar`

**Same-type fluent runs count once.** A run of consecutive chained calls that return the **same type** — a builder / `return this` fluent run like `X.newBuilder().setA().setB()` (every call returns the builder type) — is one logical operation, so only its head is counted. Concretely: a method call whose receiver is itself a call (or a `new` constructor call) of the **same return type** is a *chained continuation* and is not counted again. A call that **changes type** mid-chain (`builder.build()` → the built message) or navigates across types (`event.getDetails().getReason()`, `LockDetails → String`) starts a new invocation and **is** counted. Calls on distinct receivers, calls in separate statements, and calls nested inside argument lists (`foo(bar())` → 2) also count separately. When a call's return type cannot be resolved, it is counted (conservative — collapse only when the run is confirmed same-type). This keeps `I` a measure of *distinct logical operations*, not fluent-chain length — without it, builder-heavy code inflated `I` far above the real number of decisions (a proto builder chains many setters that are one construction). Method references, `this()`/`super()`, and enum constants are always heads.

It **does not** recurse into nested type declarations (anonymous classes, local classes). Invocations inside an anonymous class body belong to that class's own methods, not the enclosing method. Lambdas, on the other hand, ARE walked — a lambda body's calls count for the enclosing method, because lambdas don't get their own code block.

### Why median-anchored projection instead of MinMax?

Earlier versions used min-max normalization with an anchor multiplication (`sL = (L - Lmin) / (Lmax - Lmin)`, then `driver = normalized × Lmax / 3`). That formulation had a subtle cross-dimensional leak:

- The lines dimension cancelled out cleanly: `sL × Lmax ≈ L`.
- But the NCSS and invocations contributions ended up multiplied by `Lmax / ncss.max` and `Lmax / invocations.max` — ratios determined by whichever *different* outlier block happened to own each dim's maximum. A single large constructor sitting in one class could silently halve or double the contribution of every other block's NCSS or invocation count.

Anchoring to per-dimension `p50` removes that coupling: each dimension's factor depends only on its own median and on lines' median, both of which are robust to outliers by construction. No bucket-level clamp is needed — the median itself is the protection against outlier samples, and the per-block deviation knob handles abuse at the right granularity.

---

## The global driver-score cap

Outlier protection lives at the **aggregate** level, not per block.

```
# per-(kind, scope) baseline contribution from each block:
bucketP95(block)    = P{statsQuantile} of the driver-score distribution within block's (kind, scope) population
                      (configurable; default statsQuantile = 0.95, clamped to ≥ 0.85; applied by the build plugin when it computes the bucket quantiles)
operationMult       = addMult (NEW) or modifyMult (MODIFY)
testWeight          = 1.0 for prod, testCodeScoreMultiplier (default 0.4) for test

# global cap budget:
totalBaseline       = Σ_block ( bucketP95(block) × operationMult × testWeight )
globalCap           = totalBaseline × driverScoreCapMultiplier              # default 2.5

# per-block effort, uncapped:
blockEffort         = driverScore × operationMult × testWeight              # no per-block clip
totalEffortRaw      = Σ blockEffort

# global clip:
globalCapApplied    = totalBaseline > 0 and totalEffortRaw > globalCap
blockEffortSum      = globalCap if (globalCapApplied and not driverScoreCapDryRun) else totalEffortRaw
volumeScore         = pow(blockEffortSum, volumeExponent) × filesScopeMultiplier
```

Each bucket's `P{statsQuantile}` (default `P95`) is computed over its own single-scope population, so test-code outliers — which are systematically larger than prod methods — do not inflate the baseline that gets applied to prod blocks. Methods and constructors are split for the same reason: constructor populations are usually much smaller and tighter than method populations. If a `(kind, scope)` population is empty (e.g., a repo with no tests), the calculator falls back to the same-kind opposite-scope baseline.

### Worked example — cap doesn't bind

Suppose a commit changes 5 prod methods, all of medium size:

- bucketP95 for `method/prod` = 60
- modifyMult = 1.0 (rounded for the example; the default is slightly above 1.0, and because it scales effort and budget alike here it cannot change whether the cap binds), testWeight = 1.0
- per-block driver scores: `[35, 42, 28, 50, 31]`

```
totalBaseline    = 5 × 60 × 1.0 × 1.0 = 300
globalCap        = 300 × 2.5            = 750
totalEffortRaw   = 35 + 42 + 28 + 50 + 31 = 186
blockEffortSum   = min(186, 750)        = 186
globalCapApplied = false
```

Sum of efforts is well under the budget; cap doesn't bind. Volume score is computed from `186^volumeExponent`.

### Worked example — cap binds

Same project, same five blocks, plus one giant outlier method (a 600-line legacy refactor):

- per-block driver scores: `[35, 42, 28, 50, 31, 800]`

```
totalBaseline    = 6 × 60 × 1.0 × 1.0 = 360
globalCap        = 360 × 2.5            = 900
totalEffortRaw   = 186 + 800            = 986
blockEffortSum   = min(986, 900)        = 900
globalCapApplied = true
```

The outlier's excess (800 - 60 = 740 above its bucket baseline) absorbs all the slack the other blocks left in the budget; the global cap then truncates the sum at `900`. Per-block driver scores are reported untouched in the per-block table — the cap only manifests at the aggregate level via the `globalCapApplied` flag and the difference between `totalEffortRaw` and `blockEffortSum`.

### Diff-classification reduction

Git diff exposes only ADDED and DELETED lines. There is no native concept of MODIFIED. So a one-line in-place edit (`log.info("foo")` → `log.info("bar")`) shows as 1 deletion + 1 addition = 2 lines, double-counting effort.

To compensate, the server classifies every effective changed line from the diff itself (`DiffClassificationDeriver`); no model labels lines. For each eligible file (those flagged `linesJustificationRequired: true` in the request — Java source plus the [line-count-scored config files](#config-file-efforts-build-and-schema-files); NOT other resources or generated artefacts) it first removes the lines confirmed as [moved](#moved-lines), then pairs the remaining lines of each diff hunk `min(deleted, added)` in diff order and books the longer side's excess as pure additions or deletions. The result is stored as nine arrays on `effortBreakdown.diffClassification.perFile[*]`:

- **`trueModifyPairs: List<LinePair>`** — `{deleted, added}` tuples for paired delete+add lines in the same hunk. 1 effort per pair instead of 2. Every pair the server derives lands here.
- **`pureAdd: List<Integer>`** — new-file line numbers for added lines that have **no counterpart on the deleted side** of their hunk. Full weight, 1 effort each.
- **`pureDelete: List<Integer>`** — old-file line numbers for deleted lines with no counterpart on the added side. Full weight, 1 effort each.
- **`movedAdded: List<Integer>`** / **`movedDeleted: List<Integer>`** — lines confirmed as relocated code. `movedLineCoefficient / 2` effort each (see [Moved lines](#moved-lines)).
- **`inPlaceModifyPairs: List<LinePair>`**, **`cosmeticAdded: List<Integer>`**, **`cosmeticDeleted: List<Integer>`**, **`inPlaceCollapsedAdded: List<Integer>`** — buckets that a model used to fill (same-intent pairs at 1 effort per pair; cosmetic and wrap-collapsed lines at 0 effort). The schema keeps them, and the arithmetic below still reads them, but rule-based scoring leaves them **empty**.

Each `LinePair` is a fixed `{deleted: int, added: int}` tuple, so pair balance cannot be violated.

**Per-file invariants (server-validated; one bad file is skipped, the rest still apply):**
```
len(cosmeticAdded)   + len(inPlaceModifyPairs) + len(trueModifyPairs) + len(pureAdd) + len(inPlaceCollapsedAdded) + len(movedAdded) == fileChange.linesAdded   (effective)
len(cosmeticDeleted) + len(inPlaceModifyPairs) + len(trueModifyPairs) + len(pureDelete) + len(movedDeleted)                        == fileChange.linesDeleted (effective)
```

The invariants use **effective** counts. Before classification the server removes the lines that carry no effort from `linesAdded` / `linesDeleted`, and the derivation never places them in any bucket. For Java source these are:

- **Blank lines** (empty after trimming).
- **Import lines** (trimmed content starts with `import ` — regular, static and wildcard imports).
- **Comment-only lines** (`//…`, or a block-comment line beginning with `/*`, ` *`, or `*/`).

Config files use their own filter (see [Config-file efforts](#config-file-efforts-build-and-schema-files)).

**Server derives counts from arrays.** The `cosmeticLines`, `pairsCollapsed`, `pureAddDeleteLines` and `movedLines` fields on `FileDiffClassification` and the corresponding totals on `DiffClassification` are populated by the server by tallying the arrays. The counts are returned alongside the arrays so they can be aggregated without iterating.

**Effective-line factor and how it folds into the volume score.** For each classified file the server computes:

```
effectiveLines = len(inPlaceModifyPairs) + len(trueModifyPairs) + len(pureAdd) + len(pureDelete)
               + (len(movedAdded) + len(movedDeleted)) × movedLineCoefficient / 2
rawLines       = fileChange.linesAdded + fileChange.linesDeleted    # effective counts
fileFactor     = effectiveLines / rawLines                          # 1.0 means no reduction
```

Worked examples:
- 5 added + 5 deleted in one hunk: 5 pairs, `effectiveLines = 5`, `rawLines = 10`, `fileFactor = 0.5` — half the work git double-counted.
- 3 pairs + 4 pureAdd + 1 pureDelete: `effectiveLines = 3 + 4 + 1 = 8`, `rawLines = (3+4) + (3+1) = 11`, `fileFactor ≈ 0.73`.
- 4 pureAdd, 0 deleted: `effectiveLines = 4`, `rawLines = 4`, `fileFactor = 1.0` — no reduction; all four lines are new.

`fileFactor` is then applied to **every** block in that file (both NEW and MODIFY operations) by multiplying the block's `driverScore` and `effort` (a [deletion block](#delete-reward) uses `len(pureDelete) / rawLines` instead). The block-level math then re-runs unchanged from there: `totalEffortRaw = Σ scaled effort`, `globalCap` re-tested, `volumeScore = pow(blockEffortSum, volumeExponent) × filesScopeMultiplier`. The reduction lands as a deterministic per-file scaling.

Because validated files only contribute `effectiveLines ≤ rawLines` lines to the accumulators, the bookkeeping invariant `linesChangedAdjusted ≤ linesChangedRaw` holds **by construction**.

**Failure mode (per-file).** If a file's bucket totals do not match `linesAdded` / `linesDeleted`, the server logs a `warn` skipReason (`addedTotalMismatch` / `deletedTotalMismatch`) and **skips that file's reduction only**; the file keeps its unreduced effort and other files in the same commit still get their factor applied. Because the arrays are derived from the diff, a mismatch indicates a filtering defect, not a model error.

**Pipeline position.** This runs on the server in `FinalScoreCalculator.applyRules`, from the local review's assessment; no model is called at scoring time. The diff-classification reduction and the [per-block difficulty category](#per-block-difficulty-category) coefficient are applied together in a single `VolumeScoreCalculator.recompute` pass — the per-file factor scales each block's `driverScore` and `effort`, the category coefficient scales `effort` only — producing the post-reduction `baseEffortScore`. The clamped quality multiplier and the architecture bonus are then layered on top of that base effort to yield the final commit score (`finalScore = baseEffortScore × clampedQualityMultiplier + architectureBonus`). The five mirror counters on `effortBreakdown.volumeScore` (`linesChangedRaw`, `linesChangedAdjusted`, `cosmeticLinesDropped`, `inPlaceLinesCollapsed`, `movedLinesDiscounted`) record the before/after so reviewers can see exactly how much was reclassified.

### Per-block difficulty category

The diff-classification reduction above measures *how much* changed. A judgement from the local review measures *how hard* each change was: every changed code block is assigned a **difficulty category**, which maps to an effort coefficient.

| Category | Coefficient | Typical change |
|---|---|---|
| `MECHANICAL` | `0.7` | rename / import swap / signature propagation / copied or mirrored unit / accessor / one-line delegating helper |
| `ROUTINE` | `1.0` | ordinary feature wiring — the neutral default |
| `SUBSTANTIVE` | `1.2` | genuine new logic, a non-trivial branch, real algorithmic or concurrency work |
| `INTRICATE` | `1.4` | dense, correctness-critical, or algorithmically intricate code |

The spread is deliberately narrow (`0.7`–`1.4` around the `1.0` neutral anchor): the reviewing model is not perfectly reproducible even at `temperature=0` + fixed `seed`, so borderline blocks occasionally flip one tier between identical runs. A compressed spread caps how much a single tier flip can move the final score.

This **replaces the former commit-level `complexityMultiplier`** — a single `[0.7, 1.3]` number applied to the whole commit. Difficulty is now resolved per block, so a commit that mixes a mechanical mass-rename with one intricate algorithm no longer averages the two into a middling multiplier.

**Applied to effort only, never the driver score.** In `VolumeScoreCalculator.recompute` each block is rescaled as:

```
scaledDriverScore = driverScore × effectiveLineFactor × movedFactor                  # category NOT applied
scaledEffort      = effort      × effectiveLineFactor × movedFactor × categoryCoeff  # category applied here
```

`movedFactor` is the per-block [moved-line](#moved-lines) correction. Like the per-file effective-line factor it is a *volume* correction — relocated lines are not new work — so it scales the driver score too, unlike the category coefficient.

The driver score stays a pure size measure (volume remains an honest line-count magnitude), and difficulty rides on a separate axis layered onto effort: a 200-line mechanical migration and a 200-line intricate rewrite carry the same volume but different effort. The global cap is re-tested on the category-scaled efforts in the same pass.

**Deletion blocks are always `MECHANICAL`.** A [deletion block](#delete-reward) is a file-level synthetic deliberately kept out of `codeBlockChanges`, so the review never labels one and none can ever carry a category. Left at the neutral `1.0` the most mechanical work in a cleanup would be the only work without the mechanical discount . They carry no signature, so the coefficient is keyed on the same `null` the block was built with. Like the fallback below, this applies in degraded mode too.

**Unlabelled blocks do not default to neutral, and the reason is not the model's silence.** Whether a block goes unlabelled depends on how many blocks the model was asked to label, not on what the block contains, so an unlabelled block carries no signal and the fallback must be the most common label rather than a neutral guess: most MODIFY blocks a model does label are `MECHANICAL`.

Leaving MODIFY at `1.0` would couple the score to the model's verbosity: the same commit would score higher the more blocks the model left unlabelled, because unlabelled MODIFY work would be billed at full price. Both `NEW` and `MODIFY` therefore fall back to `MECHANICAL`; that damps the variance as much as it corrects the bias. Coverage is logged per analysis and warns when the fallback covers most of a commit.

The fallback applies to every non-config NEW or MODIFY block, in every mode, including the signature-less file-level blocks of a degraded (build-failure) analysis: a file the index could not parse is the least understood work in the commit. Config blocks keep the neutral `1.0`.

The category is supplied on `response.blockCategories[*]` as `{file, signature, category, reason, source}` (`CodeBlockCategoryView`) and matched to a block by `(file, signature)`. **The label is stored with the code unit**, together with the review's reason, and its effect lands in the block's `effort`. To cross-check the two per block:

```
categoryCoeff = effort / (driverScore × operationMult × testWeight)
```

The persisted `driverScore` already carries `effectiveLineFactor`, so it cancels, and the result lands on one of `{0.7, 1.0, 1.2, 1.4}` (to the two-decimal rounding of the persisted values) — **for NEW and MODIFY blocks only**.

For a DELETE block the formula does *not* yield the coefficient. `calculateDeletionOnlyFileEfforts` stores `effort = driverScore × modifyMult × testWeight × deleteRewardWeight × deletionScale` while persisting the *unscaled* `driverScore`, so the expression above evaluates to `deleteRewardWeight × deletionScale × categoryCoeff` — at defaults `0.2 × 1.0 × 0.7 = 0.14`, not `0.7`. Divide out the deletion terms first:

```
categoryCoeff = effort / (driverScore × modifyMult × testWeight × deleteRewardWeight × deletionScale)
```

Reading `0.14` on a deletion block therefore confirms the mechanical coefficient *is* applied, not that it is missing — the opposite of what the un-adjusted formula suggests, and the precise check this section exists to support.

### Moved lines

Relocating code is not writing it. When `moveDetectionEnabled` (default `true`), deleted and added lines are paired across the whole commit — including across files — and a matched pair is charged `movedLineCoefficient` (default `0.25`) instead of full weight, split as `movedLineCoefficient / 2` to each side so a cross-file move costs the same in total as an in-file one.

A candidate is discounted only when it is a relocation: it moves between two production (or two test) files, or it has a neighbouring candidate within `movedRunMaxGap` (default `2`) lines on both sides. A lone match inside one file, or between test and production code, is treated as a repeated fragment. Lines with fewer than three tokens, or pairs whose token counts differ by more than 2×, are never candidates.

Matching uses **multiset token containment**, `|tokens(A) ∩ tokens(B)| / min(|A|, |B|)`, against `moveSimilarityThreshold` (default `0.95`) — deliberately *not* Dice or Jaccard. Relocation typically only *adds* tokens (a re-qualified receiver, `props.X` → `channel.props.X`), so a genuine move scores `1.0` under containment while a symmetric metric would drop it below the threshold and miss it.

The line side of this is handled by the per-file diff classification. The **invocation** term of the driver score is not — it is uncapped and bills relocated calls to whichever block's new-file span absorbed them. `movedFactor` corrects that, per block:

```
billedInvocations    = effectiveInvocationsChanged (MODIFY) | directInvocationCount (NEW)
driverComponents     = W_L · scaledLines + W_S · scaledNcss + W_I · scaledInvocations
invocationsFactor    = scaledInvocations / billedInvocations
movedInvocationsScaled = min(movedInvocations × invocationsFactor, scaledInvocations)

movedFactor = (driverComponents − W_I · movedInvocationsScaled × (1 − movedLineCoefficient / 2)) / driverComponents
```

Because the driver score is the weighted mean of its scaled components, removing that share of the moved invocations' weighted term scales the driver by exactly this ratio — the operation-specific denominator cancels. The factor returns `1.0` (no-op) when the block bills no invocations. The moved share is recounted by regex, matching how the deleted side was billed.

### Delete reward

Without the delete reward, a surviving file whose only effective change is deletion, and which produced no scored code block, yields **no** effort for its removed lines — deletions inside a surviving MODIFY block are already billed there, so counting them again would double-count.

When `deleteRewardWeight > 0` (default `0.2`, clamped to `[0.0, 0.20]`) those remaining deletion-only files earn one synthetic block:

```
driverScore = DriverScore.forModify(scaler, linesDeleted, 0)     # line-parity, priced like an in-place modify
effort      = driverScore × modifyMult × testWeight × deleteRewardWeight
```

So a removal earns at most that fraction of a same-size true modify. A file qualifies only when **all** of: change type `MODIFIED`, structured code (`linesJustificationRequired`), non-config, no scored code block, `linesAdded == 0`, `linesDeleted > 0`. Whole-file removals (change type `DELETED`) are excluded, and config deletions are already line-count scored.

**Imports carry no signal in either direction.** `DiffStats` filters them from both sides of the effective line counts, so a file whose only additions are imports has `linesAdded == 0` and still qualifies — whether those imports were reordered by a formatter or genuinely gained. That is deliberate: an import is boilerplate an IDE writes, and a rule that let one disqualify a removal would be reading intent into a line the scoring ignores everywhere else.

**Line count is the only volume signal a removal has.** `forModify` drops NCSS and removed code leaves no parsed unit behind to supply one, so a signature a formatter spread one parameter per line bills every one of those lines. A syntactic wrap-continuation filter (drop lines ending on a separator that close no statement) is deliberately **not** applied: it cannot tell a wrapped signature from a data literal, a collection literal or an enum constant — deleting a 500-row table would bill only a few lines — and its `;` guard is dead in Kotlin and JS, where semicolons are optional, so it degenerates to "any line ending in a comma is free" in exactly the languages targeted next. The over-count for wrapped signatures is accepted instead: it is bounded, and the `MECHANICAL` coefficient on deletion blocks already absorbs most of it.

The synthetic block carries `operation = DELETE`, `signature = null` and `bucketBaseline = 0` — so like config blocks it never inflates the global-cap budget, and it is excluded from the added/modified scaled-line split and from `totalEffectiveStatements`. Moved deletions earn nothing: `recompute` scales a deletion block by `len(pureDelete) / rawLines`, not by the general per-file factor. On a degraded (build-failure) analysis the scalers are empty and `forModify` would return `0`, so the driver score falls back to the raw deleted-line count — degraded pure-deletion files earn the same fractional reward as any other removal.

**The deletion reward has its own absolute ceiling, because the global cap cannot bound it.** A mass removal is *broad and shallow* — thousands of lines spread over dozens of individually unremarkable files — so its share of the cap budget grows with file count exactly as fast as its effort does, and [the cap never binds](#the-global-driver-score-cap). So the deletion contribution is bounded absolutely instead:

```
quantile     = (Σprod driverScore × methodCapQuantileProd + Σtest driverScore × methodCapQuantileTest) / Σ driverScore
ceiling      = deleteRewardMaxQuantileUnits × quantile
rawDeletion  = Σ driverScore      # not weighted by testWeight; modifyMult and deleteRewardWeight cancel

deletionScale = rawDeletion > ceiling ? ceiling / rawDeletion : 1.0
```

Every deletion block is scaled by the same `deletionScale`, so their relative sizes survive and only the total is clipped. The ceiling reads as "at most `K` p95-sized method removals' worth of reward, however many files you touched" — `K = deleteRewardMaxQuantileUnits`, default `10`. The quantile blends the prod and test method buckets by where the deleted driver volume sits, falling back to whichever bucket exists; if neither population exists there is no ceiling and the reward passes through unscaled rather than collapsing to zero.

The ceiling is meant to leave ordinary removals untouched and to clip only mass removals: a large legitimate cleanup earns a medium commit's credit; it cannot earn a huge one. The clip is computed in driver units, before the `MECHANICAL` coefficient is applied in `recompute`, so the delivered reward is `0.7 ×` the clipped amount.

### Dry-run mode (`driverScoreCapDryRun`)

The cap can be put into **audit-only** mode by setting `driverScoreCapDryRun = true` on the submission. In that mode the cap math runs in full — `globalCap`, `globalCapApplied`, `effortShare`, `globalCapDriver`, and the file-level abuse rollups are all computed and persisted exactly as if the cap were live — but `blockEffortSum` is **not** clipped: it is left equal to `totalEffortRaw`, and the volume score is `totalEffortRaw^volumeExponent × filesScopeMultiplier`. In other words, the score matches pre-cap behaviour while the abuse signals continue to flow into the DB.

This is intended for two use cases:

1. **Calibration** — comparing the score distribution with and without the cap on real production traffic before locking in `driverScoreCapMultiplier`.
2. **A/B against pre-cap scoring** — replaying past commits under the new abuse-detection logic without changing any of the historical scores those commits produced.

A few rules that follow from the design:

- `globalCapApplied` retains its meaning: "the cap *would* clip if enforced". Combined with `globalCapDryRun`, downstream can distinguish "cap was enforced and clipped" from "cap was audited only" — both are computable from the persisted booleans.
- `globalCapDriver` still fires under dry-run (because `globalCapApplied` still reflects "would clip"). Without that, the dry-run mode would lose its principal abuse signal.
- The `blockEffortSum == totalEffortRaw` invariant under dry-run is the only behavioural difference from enforced mode. Rescoring honours the submitted `driverScoreCapDryRun` flag, so the volume score matches the stored value.

---

## Scoring layers — which mechanism owns which axis

Several multiplicative mechanisms sit between a raw diff and the final score. Each exists to price exactly one axis; none may take over another's job. When tuning, change the owner of the axis you care about — never reach for a neighbouring layer because it "also moves the number".

| Layer | Owns | Mechanism | Applied |
|---|---|---|---|
| Driver score | volume — how much | median-projected lines/NCSS/invocations; never clipped per block | per block |
| Operation multiplier | line kind — new vs modified | `addMult = addBase + addScale/(1+s)` (≈0.88), `modifyMult = modifyBase + min(modifyScale·s/(1+s), cap)` (≈1.06; both at ~15,600 project statements) | per block |
| Test weight | purpose — test vs production | `testCodeScoreMultiplier` (0.4) | per block |
| Difficulty category | how hard | local-review label → coefficient 0.7–1.4 (effort only, never driver score) | per block |
| Diff classification / moved lines | what really changed | pair collapse, moved-line factor | per file / block |
| Volume exponent | commit size — marginal volume | `blockEffortSum^volumeExponent` (default 0.85) | per commit |
| Files scope | breadth | log bonus, ≤ ~1.1 | per commit |
| Quality multiplier | outcomes | coverage / CPD / static analysis / architecture / quality gates, clamped | per commit |
| Architecture bonus | design uplift | impactScore × baseEffort × bonusFactor × qualityFactor | per commit |

### Operation multiplier × volume exponent — orthogonal, not compounding

The exponent applies **after** the per-block multipliers, so it slightly *dilutes* the add/modify spread rather than stacking on it. For operation-pure commits with equal driver volume `D`:

```
new:    (0.88·D)^0.85 = 0.897·D^0.85
modify: (1.06·D)^0.85 = 1.051·D^0.85
```

The multipliers claim a 20.5% per-line spread (`1.06/0.88`); the realized score spread is `(1.06/0.88)^0.85 ≈ 17.1%`. Large new-code commits feel both mechanisms, but the compression comes from **size, not operation** — an equally large modify sweep is compressed harder in absolute terms, because its multiplier is higher. The two layers are orthogonal: multipliers cannot shape commit size, the exponent cannot distinguish line kinds.

### Consolidation rule — "new code is easier" must have one owner

The new-code discount currently rides in two places, and a third is planned:

1. blanket `addMultiplierBase = 0.8` — every new line, indiscriminately;
2. difficulty category — `MECHANICAL` (0.7) fires precisely on boilerplate new code, so a trivial new block already stacks `0.88 × 0.7 ≈ 0.62` per driver point;
3. the planned structural-similarity discount — adaptive, per block.

**Rule: when the structural-similarity discount ships, `addMultiplierBase` returns to `1.0`.** The adaptive signal becomes the single owner of the axis and the blanket discount retires. Until then, widen neither of the two live discounts without re-checking the combined floor.

---

## Config-file efforts (build and schema files)

Some changed files carry real work but have **no code blocks** to drive — build and schema files. Rather than ignore them, Codiqo scores them from **line count alone**: `VolumeScoreCalculator.calculateConfigFileEfforts` emits **one synthetic block per changed config file**, which then sits alongside the method/constructor blocks in the same `totalEffortRaw` / cap / volume accumulators.

A file is line-count-scored when `LanguageCapabilities.isLineCountScored` is true — currently `pom.xml`, `*.proto`, Gradle scripts (`*.gradle`, `*.gradle.kts`), `gradle.properties`, `gradle-wrapper.properties` and `*.versions.toml`. The synthetic block is built as:

```
rawLines    = fileChange.linesAdded + fileChange.linesDeleted    # skip the file if 0
driverScore = rawLines                                           # the line count IS the driver score
effort      = rawLines × modifyMult × configFileScoreMultiplier  # default configMult = 0.3
```

There is no NCSS / invocation projection and no scaler — config files have no bodies, statements, or call sites. The block is always `operation = MODIFY`, `signature = null`, `isConfig = true`.

**The `0.3` `configFileScoreMultiplier` is a deliberate discount.** A 200-line dependency bump or generated-proto edit is real work but cheaper per line than 200 lines of hand-written logic, so config lines enter `totalEffortRaw` at ~⅓ weight.

**Two structural exclusions keep config files from distorting the rest of the metric:**

- **`bucketBaseline = 0`** — config blocks contribute nothing to `totalBaseline`, so they never inflate the global-cap budget, and on a config-only commit the cap cannot bind at all (`globalCapApplied` requires `totalBaseline > 0`).
- **`isConfig = true`** — excludes the block from `totalEffectiveStatements` (which sums driver scores over non-config blocks only) and from the ratio-outlier signal (it still receives `effortShare` and can be a `globalCapDriver`).

**`linesAdded` / `linesDeleted` are already effective counts.** They come from `DiffStats` filtered by the file's `IneffectiveLineFilter` before they ever reach the config-effort math: `.proto` drops C-style comments and `import` declarations; Gradle scripts drop C-style comments; `pom.xml` drops `<!-- … -->` comment lines; `gradle.properties`, `gradle-wrapper.properties` and `*.versions.toml` drop `#` comments; blank lines are always dropped. So a commit that merely re-licenses a header or reorders imports in a `.proto` scores ~0 config effort.

**Diff classification still applies, and the seeding is deliberate.** Config files set `linesJustificationRequired = true` (via `requiresDiffClassification`), so the server classifies their changed lines just like code, and in `recompute` the per-file effective-line factor rescales the synthetic block's `driverScore` and `effort` — collapsing delete+add pairs (a re-indented XML stanza) and discounting moved lines (a moved `<dependency>`). The block is seeded from `added + deleted` (not `max(added, deleted)`) precisely so the factor `effectiveLines / (added + deleted)` collapses each delete+add pair exactly once; seeding with `max` would collapse the pair a second time. The per-block [difficulty category](#per-block-difficulty-category) does **not** apply — config blocks are excluded from labelling and from the fallback, so they always take the neutral `categoryCoeff = 1.0`.

---

## Driver-score abuse signals

These fields are **diagnostic**, not scoring inputs. They exist so Codiqo can later identify developers whose blocks repeatedly look structurally engineered to inflate volume scoring, so the team can flag specific authors for review (or, on closer look, tune the threshold). False positives are expected — the goal is data, not enforcement.

Two signal families (both per-block; the project-level scalers themselves don't carry abuse signal — they're just the project's style fingerprint):

1. **Per-block ratio outlier (NEW blocks only)** — the block's own `S/L` and `I/L` ratios measured against the bucket median ratio (`bucket.ncss.p50 / bucket.lines.p50` and `bucket.invocations.p50 / bucket.lines.p50`), plus an outlier flag when either deviation exceeds `driverFactorMaxDeviation`. Lives on `CodeBlockEffortModel`. MODIFY blocks always have deviation `0.0` and `blockRatioOutlier == false` — for MODIFY, per-line ratios are dominated by diff shape (a 1-line tweak that touches a chained `.foo().bar().baz()` call gives a meaningless I/L spike), so we don't compute deviation there. The MODIFY abuse signal lives in `globalCapDriver` instead.
2. **Per-block global-cap attribution** — `effortShare` (this block's fraction of the commit's total effort) and `globalCapDriver` (true when the cap binds and this block alone owns more than `driverFactorMaxDeviation` of the total effort). Computed for both NEW and MODIFY blocks.

Per-file rollup of the above lives on `FileEffortModel` (count of flagged blocks, max deviation observed, `fileFlaggedAsAbusive` when strict majority of blocks are outliers).

### Per-block deviation formula

For a NEW block:

```
bucketRatioNcss   = bucket.ncss.p50         / bucket.lines.p50           (or 0 if bucket.lines.p50 == 0)
bucketRatioInvocs = bucket.invocations.p50  / bucket.lines.p50

blockRatioNcss    = block.S / block.L                                    (or 0 if block.L == 0)
blockRatioInvocs  = block.I / block.L

blockRatioDeviationNcss        = |blockRatioNcss   - bucketRatioNcss|   / bucketRatioNcss     (or 0 if bucketRatioNcss == 0)
blockRatioDeviationInvocations = |blockRatioInvocs - bucketRatioInvocs| / bucketRatioInvocs

blockRatioOutlier = blockRatioDeviationNcss > driverFactorMaxDeviation
                 || blockRatioDeviationInvocations > driverFactorMaxDeviation
```

For a MODIFY block, `blockRatioDeviationNcss` is **always `0.0`** (NCSS is dropped from the MODIFY score, so the dimension is not informative). `blockRatioDeviationInvocations` is also always `0.0` (per-line ratios on a diff are diff-shape artefacts).

### About the threshold knob

`driverFactorMaxDeviation` (default `0.75`) does **one** thing: it sets the per-block deviation threshold above which a block is flagged as a ratio outlier (or as a `globalCapDriver`). It does **not** clamp the per-bucket projection factors `k_S` / `k_I` — those are taken raw from `lines.p50 / dim.p50` so the project's style is preserved.

If most of your project's blocks legitimately deviate more than 75% from the bucket median, raise the knob. If you want a stricter abuse filter, lower it. The default was raised from an earlier `0.25` to reduce false positives on small but legitimately style-divergent blocks while still catching genuinely engineered outliers.

### Worked example — block ratio outlier

Bucket median for `method/prod`: `lines.p50 = 50`, `ncss.p50 = 50`, `invocations.p50 = 50`. So `bucketRatioNcss = 50/50 = 1.0`.

Developer adds a NEW method with `L = 10, S = 20, I = 10`:

```
blockRatioNcss          = 20 / 10 = 2.0
blockRatioDeviationNcss = |2.0 - 1.0| / 1.0 = 1.0
blockRatioOutlier       = 1.0 > 0.75 → TRUE
```

The block is flagged. By itself this is just data — but if the same author shows up across many commits as the principal source of NCSS-dense outlier blocks, they merit a closer look (or it's a legitimate code style for that project and `driverFactorMaxDeviation` should be loosened).

### Worked example — globalCapDriver

Reusing the [cap-binds example](#worked-example--cap-binds) (one 800-driver outlier method alongside five reasonable ones):

```
totalEffortRaw   = 35 + 42 + 28 + 50 + 31 + 800 = 986
giant block effortShare = 800 / 986 ≈ 0.811     (above 0.75)
globalCapApplied = true
giant.globalCapDriver = true
```

The outlier method is flagged as the cap driver. The five small blocks each have `effortShare < 0.06` — none flagged. The signal makes "this commit hit the cap because of one block" attributable to that block (and its author).

---

## Reading the per-block breakdown

Two places show one row per changed code block. The Maven console summary (see [below](#the-maven-console-summary)) shows the columns up to `Driver`, computed on the build machine from the submission. The scored result (`assessment.effortBreakdown.fileEfforts[*].codeBlockEfforts[*]`) and the commit page add `Ratio` and `Effort`, which only exist once the server has scored the commit.

| Column | Where | Meaning |
|---|---|---|
| **L** | Console + commit page | Non-comment code lines in the block. In the console, for MODIFY, this is the effective `linesChanged`, not the raw body length. The commit page shows the persisted `nonCommentCodeLines` for every block (signature included), so its L is larger than the driver formula's body-only L |
| **S** | Console + commit page | NCSS (logical statements). For MODIFY, shown as the raw NCSS, but the dimension is not used in the score |
| **I** | Console + commit page | Direct method invocations inside the block. In the console, for MODIFY, this is the effective `invocationsChanged` |
| **pL** | Console | Lines-equivalent contribution of the L dimension (= L directly; included for symmetry with pS/pI) |
| **pS** | Console | Lines-equivalent contribution of the S dimension (= `S · k_S`); rendered as `—` for MODIFY blocks (dimension dropped) |
| **pI** | Console | Lines-equivalent contribution of the I dimension (= `I · k_I`) |
| **Driver** | Console + commit page | Driver score: `(pL + pS + 0.5·pI) / 2.5` for NEW, `(pL + 0.5·pI) / 1.5` for MODIFY. Note `pI` is the *unweighted* lines-equivalent contribution — `W_I = 0.5` is applied here, not in the column |
| **Ratio** | Commit page | `changeRatio` for MODIFY blocks, `1.00` for NEW |
| **Effort** | Commit page | `Driver × operationMult × testWeight × categoryCoeff` — what the block contributes to `totalEffortRaw`. On the scored result, `Driver` already carries the per-file effective-line factor, and `categoryCoeff` is the per-block [difficulty-category](#per-block-difficulty-category) coefficient (`categoryMechanicalCoeff` when the review left the block unlabelled — see that section; never the neutral default for a non-config NEW or MODIFY block) |

**The console summary computes each row the way the server does**, from the same scored blocks (`bodyCodeLines` as `L`, `min(totalLinesChanged, L)` for a MODIFY block). It runs before the diff classification and the moved-line factor, so its `Driver` column is the driver score before those per-file and per-block corrections.

**Interpretation tip:** scan the `pL / pS / pI` columns first. They're all in the same lines-equivalent unit, so you can compare them directly. If one of them is much larger than the others, that block is "big in one way only" — usually a fan-out-heavy orchestration method that calls many *distinct* collaborators (high pI), a Lombok-generated getter spray or fluent builder with many lines but few distinct call-sites (high pL, since a fluent chain counts as one invocation), or an algorithmic density method (high pS). If all three are balanced, the block is uniformly large.

---

## Four scalers, not one — why separate prod/test and method/constructor

A test method typically calls `assertEquals` 10 times in 15 lines. A production method typically has longer bodies with fewer assertion-style calls. A constructor is typically short and full of `this.x = x` assignments. Mixing these populations into one scaler would distort `k_I` (dragged up by test assertions) and `k_S` (dragged by imperative prod density), and every real change would be scored against an averaged-out ruler that matches none of the actual populations.

Separating into four scalers means each changed block ends up compared fairly to its actual peers.

---

## Stability across commits

The driver score is **re-calibrated every run**. Because the math is anchored to medians, adding a single 500-line monster method to the project barely moves `lines.p50` at all — so the projection factors (`k_S`, `k_I`) are very stable from commit to commit. Driver scores for unchanged blocks are nearly constant across commits, and adding or removing a handful of outliers does not perturb everyone else's score.

This is a deliberate choice. The alternative (freezing scalers once and reusing them) gives stable historical numbers but goes stale as the project grows. Because driver scores are consumed per commit by the scoring rules (not plotted on a dashboard), drift is invisible to the end product. If you need stable trend tracking, use the final **commit score** or the **effort** fields.

---

## Configuration

Tunable knobs that affect the driver score and the global cap. All of them except the four `category*Coeff` and `configFileScoreMultiplier` ride on the submission's `scoringConfig`, so rescoring reproduces the same numbers even if server defaults change; those five are not submitted and always take the scoring server's defaults.

**Where each one can actually be set.** Scoring resolves as *submitted config → organization override → project override*, so the submitted value wins over server defaults and an override wins over both. Only a subset is reachable from the build: `driverScoreCapMultiplier`, `driverFactorMaxDeviation`, `driverScoreCapDryRun`, `moveDetectionEnabled`, `moveSimilarityThreshold` and `movedLineCoefficient` have `codiqo.*` Maven properties (and matching `codiqo-action` inputs) on Maven only; the Gradle plugin exposes none of them, so a Gradle submission always carries their defaults. The rest — `statsQuantile`, `volumeExponent`, `testCodeScoreMultiplier`, `configFileScoreMultiplier`, the four `category*Coeff`, `deleteRewardWeight`, `deleteRewardMaxQuantileUnits`, the operation-multiplier terms and `sizeFactorDivisor` — are deliberately **not** exposed as build properties: every submission carries the engine's defaults for them (except `configFileScoreMultiplier` and the four `category*Coeff`, which are not submitted and cannot be overridden: the server's defaults apply), and recalibration happens through an organization or project override so a single decision applies to a whole history rather than drifting per repository's workflow file.

| Field | Default | Effect |
|---|---|---|
| `statsQuantile` | `0.95` | Which percentile of the per-bucket driver-score distribution to use as the baseline contribution. Clamped to `≥ 0.85` |
| `driverScoreCapMultiplier` | `2.5` | Multiplier on `totalBaseline` to get the global cap on summed effort. Higher = more permissive cap |
| `driverFactorMaxDeviation` | `0.75` | Per-block deviation threshold. A block whose own `S/L` or `I/L` deviates from the bucket median ratio by more than this fraction is flagged as a ratio outlier; same value also gates `globalCapDriver`. Does **not** affect bucket-level projection factors (which are always raw `lines.p50 / dim.p50`). |
| `driverScoreCapDryRun` | `false` | When `true`, the global cap is computed and abuse signals (`globalCapApplied`, `globalCapDriver`, `effortShare`, file-level rollups) are populated, but `blockEffortSum` is **not** clipped to `globalCap` — the score uses the uncapped `totalEffortRaw`. Lets you compare uncapped vs. capped scoring while still surfacing abuse signals. |
| `volumeExponent` | `0.85` | Applied to `blockEffortSum` (the effort sum after the global cap); compresses marginal volume so large commits gain sub-linearly |
| `testCodeScoreMultiplier` | `0.4` | Multiplier applied to test-block effort (and to the test-block contribution to `totalBaseline`) |
| `categoryMechanicalCoeff`, `categoryRoutineCoeff`, `categorySubstantiveCoeff`, `categoryIntricateCoeff` | `0.7`, `1.0`, `1.2`, `1.4` | Per-block difficulty-category effort coefficients (local-review label → coefficient). Multiply **effort only**, never the driver score. Spread deliberately narrow to limit score swing when the model flips a borderline block one tier between runs. Replace the former commit-level `complexityMultiplier` |
| `configFileScoreMultiplier` | `0.3` | Discount applied to [config-file efforts](#config-file-efforts-build-and-schema-files) (synthetic blocks for `pom.xml`, `.proto`, Gradle build scripts and version catalogs, scored by line count). `effort = rawLines × modifyMult × configFileScoreMultiplier` |
| `moveDetectionEnabled` | `true` | Enables cross-file [moved-line](#moved-lines) detection and the per-block `movedFactor` correction |
| `moveSimilarityThreshold` | `0.95` | Multiset **token containment** threshold `\|A ∩ B\| / min(\|A\|,\|B\|)` for treating a deleted/added line pair as a move. Not Dice/Jaccard — relocation usually only adds tokens |
| `movedLineCoefficient` | `0.25` | Charge for a matched moved pair instead of full weight, split `movedLineCoefficient / 2` to each side so cross-file and in-file moves cost the same in total |
| `movedRunMaxGap` | `2` | Largest line gap between two move candidates that still counts them as one relocated run (a lone in-file candidate is not discounted) |
| `deleteRewardWeight` | `0.2` | Fraction of a same-size in-place modify credited to [deletion-only files](#delete-reward) that produced no scored block. Clamped to `[0.0, 0.20]`; `0.0` disables the reward entirely |
| `deleteRewardMaxQuantileUnits` | `10` | Absolute ceiling on a commit's **total** deletion reward, in bucket-quantile units. Exists because the global cap structurally cannot bound a broad-and-shallow deletion sweep. `0` disables the ceiling |
| `addMultiplierBase`, `addMultiplierScale`, `modifyMultiplierBase`, `modifyMultiplierScale`, `modifyMultiplierCap`, `sizeFactorDivisor` | `0.8`, `0.1`, `1.0`, `0.3`, `0.2`, `100` | `addMult = addMultiplierBase + addMultiplierScale / (1 + s)`, `modifyMult = modifyMultiplierBase + min(modifyMultiplierScale · s / (1 + s), modifyMultiplierCap)` with `s = cbrt(totalStatements) / sizeFactorDivisor` — see `VolumeScoreCalculator.calculate` |

---

## The Maven console summary

When a `codiqo-maven-plugin` analysis goal assembles its submission, it logs a calibration block (the Gradle plugin prints none):

```
Codiqo — Driver Score Calibration
---------------------------------
project:         com.acme:widget-service
commit:          a1b2c3d4e5f6
author:          Jane Doe

files:
  total:    12   (prod: 9, test: 3)
  by_type:  add: 2, modify: 9, delete: 0, rename: 1

changed_blocks:
  total:    27
  by_op:    new: 6, modify: 21   (trivial_skipped: 4)
  by_scope: prod: 22, test: 5

baseline_populations:
  method/prod:       N=1604  trivials_excluded=212
  method/test:       N=318   trivials_excluded=41
  constructor/prod:  N=238   trivials_excluded=5
  constructor/test:  N=42    trivials_excluded=0

driver_score_cap (global ceiling on total code-block effort):
  bucket_baseline = P95(driver_scores) × operationMult × testWeight, per (kind, scope)
  total_baseline  = Σ_block bucket_baseline (summed over all changed non-trivial blocks)
  global_cap      = total_baseline × 2.50
  total_effort    = min(Σ_block effort, global_cap)
  P95 = 95th percentile of driver-score distribution within each (kind, scope) population
  2.50 = driver score cap multiplier (caps the total summed effort at this multiple of the baseline budget)
  per-block driver scores are not individually clipped; the cap binds only on their sum
  method/prod:       quantile=52    bucket_budget_per_block=130
  method/test:       quantile=71    bucket_budget_per_block=178
  constructor/prod:  quantile=24    bucket_budget_per_block=60
  constructor/test:  quantile=13    bucket_budget_per_block=33

scalers:
  method/prod: (N=1604)
    lines:   min=1    p50=6.0    p75=14.0   p90=28.0   p95=40.0   max=87
    ncss:    min=2    p50=3.0    p75=8.0    p90=17.0   p95=24.0   max=52
    invocs:  min=0    p50=2.0    p75=6.0    p90=14.0   p95=20.0   max=41
  ... (other three buckets) ...

driver_formula:
  weights:          W_L=1.00  W_S=1.00  W_I=0.50   (total=2.50)
  formula_new:      (W_L·lines + W_S·ncss·k_S + W_I·invocs·k_I) / total
  formula_modify:   (W_L·linesChanged + W_I·invocsChanged·k_I) / (W_L + W_I)
  factors (k_S = lines.p50 / ncss.p50, k_I = lines.p50 / invocs.p50; raw bucket-level — no clamp):
    method/prod:       k_S=2.000  k_I=3.000   (lines.p50=6.0, ncss.p50=3.0, invocs.p50=2.0)
    ... (other three) ...
  ... (then: max contributors per bucket, the changed-blocks table L/S/I/pL/pS/pI/Driver, trivial changed blocks, ratio outliers and outlier counts) ...
```

Reading notes:

- `baseline_populations` shows the `N` each scaler was built from plus how many trivial blocks were filtered out.
- `driver_score_cap` shows the global formula and per-bucket `bucket_budget_per_block = quantile × driverScoreCapMultiplier`. This is the contribution **a single block in that bucket** makes to `globalCap` (before operation/test multipliers); its contribution to `totalBaseline` is `quantile` alone, and the actual cap is the sum across all changed blocks.
- Each scaler prints `min / p50 / p75 / p90 / p95 / max` per dimension. Only the `p50` rows participate in the formula (via `k_S` and `k_I`); the other percentiles are informational.
- `driver_formula` shows the weights, the per-scope raw projection factors, and the formulas. Invocations carry half weight (`W_I=0.50`) because a call is already counted as a line and a statement — see the weights FAQ below. Each `k_S` / `k_I` is annotated with the three p50 numbers it was derived from — useful for sanity-checking that the project's style is being captured faithfully.
- The non-trivial blocks table stops at `Driver` — `Effort` is computed by the server when it scores the commit and shows on the commit page, not in the Maven summary. Its `Driver` column is the driver score before the per-file and moved-line corrections; see [Reading the per-block breakdown](#reading-the-per-block-breakdown).
- If the changed blocks cannot be mapped as the server maps them (for example a diff that cannot be parsed), the summary logs a warning naming the cause and prints `unavailable` in place of the block counts, the blocks table and the outlier sections; the analysis itself continues. A block whose bucket has no baseline samples prints `0.00 (no baseline samples in this bucket)`.

---

## FAQ

**Q: Why full weight on lines and NCSS but half weight on invocations?**
A: The projection factors (`k_S`, `k_I`) put all three on commensurable lines-equivalent scales, so a median block scores the same regardless of weights — the weights only decide how above/below-median *deviations* in each dimension combine. Lines and NCSS are two views of size and carry full weight. Invocations are different: a call almost always sits on a line and in a statement that are *already* counted, so invocations track NCSS closely rather than forming an independent axis. Their only extra signal is orchestration/fan-out density (e.g. `a(b(c(d())))` is one statement but four calls). Full weight double-counts call-heavy glue code and penalizes dense compute that makes few calls; half weight (`W_I=0.50`) keeps the fan-out signal without the double-count. The weights are constants in `DriverScore.java` and easy to change.

**Q: Why median-anchored projection instead of raw counts (`L + S + I`) or MinMax?**
A: Raw counts would give each unit equal weight, which is wrong — one line of code is not the same amount of work as one NCSS statement or one method invocation, and the ratio depends on the project's coding style. The median-based factors let the project's own baseline say "on average, one S is worth roughly X lines in *this* codebase". The medians themselves are robust to outliers, so we take the resulting factors as-is — abuse protection lives one level down, on individual blocks via `driverFactorMaxDeviation`.

**Q: Why drop NCSS for MODIFY but keep it for NEW?**
A: For a brand-new block, `linesChanged = totalLines`, so all three dimensions carry independent information. For a modification, lines-changed already scales the block by how much of it was touched; adding NCSS on top would double-count that scaling.

**Q: What happens to test blocks' weight?**
A: Test blocks still get the `testCodeScoreMultiplier` (default `0.4`) applied to both their effort and their contribution to `totalBaseline`. The driver score itself is fair (test calibrated against test); the deweighting is a product decision applied uniformly at the effort step.

**Q: Why is the cap global instead of per-block?**
A: Per-block clipping conflates two different concerns — outlier-block protection and aggregate size protection. The global-cap design isolates the latter: the **global cap on the summed effort** handles size-outlier protection at the aggregate, while the **per-block deviation flag (`blockRatioOutlier`)** handles per-developer abuse signal as data — without distorting the score. A typical commit (no outliers) is unaffected by the cap; a commit with one giant block sees only the *aggregate* truncation, so a 600-line legacy refactor doesn't lose the contributions of the well-sized blocks alongside it.

**Q: Can I see the raw L, S, I for a specific block?**
A: Yes. The submission carries them on each code unit's `metrics` (`bodyCodeLines` = L, `nonCommentCodeStatements` = S, `directInvocationCount` = I), and the Maven console summary prints them per block. The scored result's `assessment.effortBreakdown.fileEfforts[*].codeBlockEfforts[*]` repeats S and I, but reports `nonCommentCodeLines` (signature included) rather than L.

---

## Reproducibility

The score of a commit is a deterministic function of what the build plugin submits and of the local review's assessment. The submission carries everything the formulas above read:

- **Project calibration** — the four scalers (`driverScalers.*`, each dimension as `{min, p50, p75, p90, p95, max}`, with the derived `ncssFactor` and `invocationsFactor`) and the four per-bucket cap quantiles (`projectMetrics.*CapQuantile*`).
- **Per-block raw counts** — `bodyCodeLines` (L), `nonCommentCodeStatements` (S), `directInvocationCount` (I), and for MODIFY blocks `effectiveLinesChanged` and `effectiveInvocationsChanged` (`totalLinesChanged` is derived from the diff when the commit is scored), plus the block's kind, operation and whether its file is test code.
- **Settings** — the submitted `scoringConfig` (see [Configuration](#configuration)). The four `category*Coeff` and `configFileScoreMultiplier` are not submitted; the scoring server's defaults apply to them.
- **The diff** — from which the [diff classification](#diff-classification-reduction) and [moved lines](#moved-lines) are derived.

The scored result exposes the per-block values derived from these inputs, so they never need to be recomputed to be read:

| Field | Value |
|---|---|
| `driverScore` | NEW: `(W_L · L + W_S · S · k_S + W_I · I · k_I) / TOTAL_WEIGHT`; MODIFY: `(W_L · linesChanged + W_I · invocationsChanged · k_I) / (W_L + W_I)`, after the per-file and moved-line factors |
| `scaledLines` / `scaledNcss` / `scaledInvocations` | `pL`, `pS` (`0` for MODIFY), `pI` |
| `effort` | `driverScore · operationMult · testWeight · categoryCoeff` |
| `changeRatio` | `min(1.0, totalLinesChanged / bodyCodeLines)` for MODIFY, `1.0` for NEW. On the scored result the per-block `effectiveLinesChanged` field carries this `totalLinesChanged` (effective added plus deleted lines) |
| `effortShare` | `effort / totalEffortRaw` |
| `blockRatioDeviationNcss`, `blockRatioDeviationInvocations`, `blockRatioOutlier`, `globalCapDriver` | See [Driver-score abuse signals](#driver-score-abuse-signals) |

Per file, `fileEfforts[*]` adds the abuse-signal rollups (`blocksFlaggedAsRatioOutlier`, `blocksFlaggedAsGlobalCapDriver`, `maxBlockRatioDeviationNcss`, `maxBlockRatioDeviationInvocations`, `fileFlaggedAsAbusive`), and per commit `effortBreakdown.volumeScore` carries `totalEffortRaw`, `totalBaseline`, `globalCap`, `globalCapApplied`, `globalCapDryRun` and the [diff-classification](#diff-classification-reduction) mirror counters.

Rescoring a commit from the same inputs yields the same score, provided the server defaults for the unsubmitted settings have not changed in between.

---

## Glossary

| Term | Meaning |
|---|---|
| **Driver score** | The composite line-count-magnitude number that drives volume scoring. Computed per changed block as a weighted average of L, S·k_S, I·k_I (NEW) or linesChanged + invocsChanged·k_I (MODIFY) |
| **Scaler** | A bucket-level statistical summary: `(min, p50, p75, p90, p95, max)` per dimension, computed from a project population. `p50` drives the projection factors; the rest are diagnostics. `p95` is also used as the per-block contribution to the global cap baseline |
| **Projection factor** | A median-anchored conversion factor from one dimension's count into lines-equivalent units. `k_S = lines.p50 / ncss.p50`, `k_I = lines.p50 / invocations.p50`. Computed raw at the bucket level — no clamp. A factor of `0.0` means the dimension has no signal in the population (`dim.p50 == 0`) and contributes nothing to the projection |
| **`driverFactorMaxDeviation`** | Per-block deviation threshold, default `0.75`. Gates `blockRatioOutlier` and `globalCapDriver`. Has no effect on bucket-level projection factors. |
| **`driverScoreCapDryRun`** | Audit-only flag, default `false`. When `true`, the cap is computed and `globalCapApplied`/`globalCapDriver` still fire, but `blockEffortSum` is **not** clipped — the volume score uses the uncapped `totalEffortRaw`, matching uncapped behaviour. Used to compare scoring with and without the cap while still collecting abuse signals. |
| **pL, pS, pI** | Lines-equivalent contributions of each dimension to the driver: `pL = L`, `pS = S · k_S`, `pI = I · k_I` (or, for MODIFY, the `*Changed` variants with `pS = —`) |
| **Scope** | prod vs test — which source set a block belongs to |
| **Kind** | method vs constructor |
| **L** | Non-comment code lines in the block **body** (`bodyCodeLines` — `{` ... `}`, excluding signature) |
| **S** | NCSS — logical statement count |
| **I** | Direct method invocations inside the block (excluding nested types) |
| **Driver-score cap (global budget)** | A single ceiling on the sum of all per-block efforts, sized as `totalBaseline × driverScoreCapMultiplier` (default `2.5`). Per-block driver scores are *not* individually clipped; the cap binds only on the sum |
| **`totalBaseline`** | `Σ_block (bucketP95 × operationMult × testWeight)` — the bucket-aware budget that the global cap multiplies |
| **`totalEffortRaw`** | `Σ_block effort` (`driverScore × operationMult × testWeight × categoryCoeff`, after the per-file and moved-line factors) — sum of per-block efforts before the global cap |
| **`globalCapApplied`** | `true` iff `totalBaseline > 0 && totalEffortRaw > globalCap` (i.e., the cap clipped the sum, or would have under `driverScoreCapDryRun`). The baseline guard means a config-only commit — whose blocks all carry `bucketBaseline = 0` — can never report the cap as binding |
| **Trivial block** | A method or constructor with `NCSS ≤ 2` **and** `directInvocationCount == 0` **and** `cyclo ≤ 1`. Excluded from scaler populations and skipped when scoring changes |
| **Block ratio outlier** | A code block whose own `S/L` (or `I/L`) ratio deviates from the bucket median ratio by more than `driverFactorMaxDeviation` (default `75%`). Per-developer abuse-signal candidate. |
| **`effortShare`** | A code block's fraction of the commit's total raw effort: `block.effort / totalEffortRaw`. Always populated; used for cap-driver attribution and stacked visualizations. |
| **`globalCapDriver`** | A code block flagged when `globalCapApplied == true` AND its `effortShare` exceeds `driverFactorMaxDeviation`. Identifies the principal cause of a cap event, attributable to a specific block (and author). |
| **`fileFlaggedAsAbusive`** | A file where strict majority (`outliers × 2 > total`) of its blocks are ratio outliers. File-level rollup of the per-block abuse signal. |
| **Difficulty category** | Per-block effort coefficient from the local review's label — `MECHANICAL` (`0.7`), `ROUTINE` (`1.0`, the neutral anchor), `SUBSTANTIVE` (`1.2`), `INTRICATE` (`1.4`). Multiplies the block's `effort` only — never its driver score — in `recompute`. A non-config NEW or MODIFY block the review left unlabelled falls back to `MECHANICAL`, **not** to `ROUTINE`; only config blocks take the neutral `1.0`. Replaces the former commit-level `complexityMultiplier`. Supplied on `response.blockCategories[*]` as `{file, signature, category, reason, source}`; stored with the code unit, and also recoverable as `effort / (driverScore × operationMult × testWeight)` for NEW/MODIFY (a DELETE block also carries `deleteRewardWeight × deletionScale`). |
| **Config-file effort** | The synthetic effort of a line-count-scored file (`pom.xml`, `.proto`, Gradle build scripts, `gradle.properties`, `*.versions.toml`) that has no code blocks. One block per file with `isConfig = true`, `signature = null`, `driverScore = linesAdded + linesDeleted` (effective), `effort = driverScore × modifyMult × configFileScoreMultiplier` (default `0.3`). `bucketBaseline = 0` so it stays out of the global-cap budget and the effective-statements metric. Still subject to diff-classification reduction; not subject to a difficulty category. |
| **`isConfig`** | Per-block flag marking a synthetic [config-file effort](#config-file-efforts-build-and-schema-files) block. Excludes the block from `totalEffectiveStatements` and the ratio-outlier signal; such blocks carry a null signature and a zero `bucketBaseline`. |
| **Diff classification** | Server-derived per-line classification of changed lines. The server builds nine arrays per eligible file — `trueModifyPairs`, `pureAdd`, `pureDelete`, `movedAdded`, `movedDeleted`, and the model-era `inPlaceModifyPairs`, `cosmeticAdded`, `cosmeticDeleted`, `inPlaceCollapsedAdded` (empty under rule-based scoring) — that together cover every effective changed line. Compensates for git's lack of a "modified" line concept. Lives on `effortBreakdown.diffClassification` of the scoring response. |
| **`LinePair`** | Tuple `{deleted: int, added: int}` representing one paired modification — an old-file line removed and a new-file line added that together count as one logical edit. Replaces the previous shape of two parallel arrays for paired modifications; the tuple makes pair balance structurally impossible to violate. |
| **`cosmeticAdded` / `cosmeticDeleted`** | Flat line-number arrays — `cosmeticAdded` uses new-file numbers, `cosmeticDeleted` uses old-file numbers. Lines whose presence/absence does not change behavior (whitespace, comments, log strings, license headers, brace style flips). Effort weight 0. No pairing requirement. Empty under rule-based scoring. |
| **`inPlaceModifyPairs`** | List of `LinePair` tuples — paired delete+add where the **same intent survives** in a different form (renamed local, swapped operand, tweaked expression). 1 effort per pair. Empty under rule-based scoring, which books every pair as `trueModifyPairs`. |
| **`trueModifyPairs`** | List of `LinePair` tuples — paired delete+add lines in one diff hunk. 1 effort per pair. |
| **`pureAdd` / `pureDelete`** | Flat line-number arrays for added / deleted lines with **no counterpart on the other side** (real new logic, real removal). Full weight, 1 effort each. |
| **`cosmeticLines` / `pairsCollapsed` / `pureAddDeleteLines`** | Server-derived count fields on `FileDiffClassification` — tallied by the server from the arrays (`movedLines` as well). Returned alongside the arrays for fast aggregation. `pairsCollapsed = len(inPlaceModifyPairs) + len(trueModifyPairs)`. |
| **`effectiveLineFactor`** | Per-file scaling factor applied to every block's `driverScore` and `effort` after the diff classification: `(pairsCollapsed + pureAddDeleteLines + movedLines × movedLineCoefficient / 2) / (linesAdded + linesDeleted)`. `1.0` means no reduction. |
| **`linesChangedRaw` / `linesChangedAdjusted`** | Volume-score mirror counters that record the line totals before/after diff-classification reduction. Both `0` when no classification was applied. Invariant when applied: `linesChangedAdjusted ≤ linesChangedRaw` (by construction — validated files only contribute `effectiveLines ≤ rawLines` to the accumulators). |
| **`cosmeticLinesDropped` / `inPlaceLinesCollapsed` / `movedLinesDiscounted`** | Volume-score mirror counters that summarize how the reduction shifted lines: total cosmetic lines treated as 0-effort, number of pairs collapsed from 2 raw lines to 1 effective line, and moved lines discounted. `inPlaceLinesCollapsed` field name is preserved for schema stability — both in-place *and* true-modify pairs contribute to it. |
