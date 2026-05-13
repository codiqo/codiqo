# Driver Score — what it is, why it exists, how to read it

This document is the canonical explanation of Codiqo's volume driver metric. It is designed to be copy-pasted as-is into the two downstream projects that consume the metric:

- the **backend replay service**, which persists offline-plugin submissions to a database and later reconstructs them for server-side LLM callbacks (so it needs to know exactly which inputs must be persisted and how the driver score is re-derived from them);
- the **UI**, which renders the per-block breakdown in reports and therefore needs to know what each column means and what fields it can read off the LLM scoring request/response.

The first part of the document explains the metric for a developer reviewing a Codiqo report. The [Persistence and replay](#persistence-and-replay) section at the end covers the schema-level details needed by the backend and UI projects.

---

## TL;DR

Every changed method or constructor gets a **driver score** — a single number that represents "how much work this code block is worth" for volume scoring. It replaces NCSS (non-comment code statements) as the primary driver.

The driver score is a **weighted average of three dimensions, each projected onto lines-equivalent units**:

1. **L** — physical non-comment lines of code **inside the body block** (between `{` and `}`)
2. **S** — NCSS (logical statement count)
3. **I** — direct method invocations inside the block

> **Why body-only?** A method whose parameters are formatted one-per-line can have a 6-line signature on a 4-line body. Counting those signature lines in `L` would inflate `lines.p50` upward without inflating `ncss.p50` or `invocations.p50` (both of which are body-only by construction), so `k_S = lines.p50 / ncss.p50` would lie about how many real *body* lines a statement projects to. Restricting `L` to the body keeps all three numerators on the same footing and makes the projection factors describe what the tooltips claim: "1 statement ≈ K body lines", "1 invocation ≈ K body lines". Signature-area edits aren't lost — they still appear in the file's overall `linesAdded`/`linesDeleted` and are subject to the LLM's `cosmetic` / `inPlaceModify` / `trueDeleteAdd` classification.

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

The last two rows are the pain point: at roughly the same real effort, imperative code scored 5× higher than functional code. Reviewers found the difference unjustifiable.

The driver score solves this by combining **three orthogonal signals** that together cover both styles. A builder has high `L`, low `S`, high `I`. An imperative loop has medium `L`, high `S`, medium `I`. A one-line lambda stream has low `L`, low `S`, medium `I`. Each dimension fires on a different kind of work, so composing them gives a balanced measure.

---

## The scaler — a worked example

Before showing the formulas, let's nail down what a "scaler" actually contains. When the Maven plugin runs, it walks every method and every constructor in the project (both production and test code) and records three numbers per block:

- `L` = non-comment code lines **of the body block** (`bodyCodeLines` — the `{` ... `}` span, excluding signature, parameter lines, `throws` clauses, leading annotations, and javadoc)
- `S` = NCSS
- `I` = direct method invocations

These samples are bucketed into **four populations**: `method/prod`, `method/test`, `ctor/prod`, `ctor/test`.

**Trivial blocks are excluded from the populations.** A block is trivial when `NCSS ≤ 2` **and** `directInvocationCount == 0` **and** `cyclo ≤ 1` — the typical getter / setter / field-assigning constructor with no branching. Keeping them in would drag `min` to `0` and compress the useful signal at the low end of the distribution.

For each population, Codiqo computes a `DimensionStats` record per dimension: `(min, p50, p75, p90, p95, max)`. Imagine the `method/prod` bucket has 1604 non-trivial samples. The dimension stats might look like:

```
method/prod  (N=1604)
  lines:   min=1   p50=6   p75=14   p90=28   p95=40   max=87
  ncss:    min=0   p50=3   p75=8    p90=17   p95=24   max=52
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

The `driverFactorMaxDeviation` knob (default `0.75`) does **not** clip these bucket factors. It only flags **per-block outliers** — blocks whose own `S/L` or `I/L` ratio deviates more than 75% from the bucket's median ratio. See [Driver-score abuse signals](#driver-score-abuse-signals).

### Degenerate dimensions

If `dim.p50 == 0` for either NCSS or invocations (i.e., half or more blocks in the population have zero of that dimension), there is no signal in this project from which to derive a projection factor. **The honest read is that the dimension contributes nothing to the projection** — so the factor is set to `0.0`. This is a deliberate change from a previous behavior that silently used `1.0` as a fabricated neutral; using `0.0` says "we have no evidence about this dimension here" instead of pretending each unit is one line.

If `lines.p50 == 0` (degenerate input — every block has zero non-comment lines), the entire scaler is treated as **empty** and `DriverScore.forNew/forModify` short-circuit to `0.0`. This effectively disables driver scoring for that bucket; it should never happen on a real codebase.

---

## How the score is computed

### For a NEW block — all three dimensions contribute

```
driverScore = (W_L · L + W_S · S · k_S + W_I · I · k_I) / TOTAL_WEIGHT
```

With default equal weights (`W_L = W_S = W_I = 1`, `TOTAL_WEIGHT = 3`) this simplifies to:

```
driverScore = (L + S · k_S + I · k_I) / 3
```

Each dimension is first projected into lines-equivalent units, then averaged. The result is a line-count-magnitude number directly comparable to the sizes of real methods in the codebase — no post-hoc anchoring step needed.

### For a MODIFY block — NCSS is dropped

```
linesChanged       = min(totalLinesChanged, L)     // capped at the block's own size
invocationsChanged = effectiveInvocationsChanged   // invocations whose source line is in the changed-line set

driverScore = (W_L · linesChanged + W_I · invocationsChanged · k_I) / (W_L + W_I)
            = (linesChanged + invocationsChanged · k_I) / 2
```

Both `effectiveLinesChanged` and `effectiveInvocationsChanged` are precomputed once per MODIFY code unit by `EffectiveChangePopulator`, which intersects the unit's body with the effective added lines parsed from the unified diff. A 1-line tweak in a 100-line method produces `linesChanged = 1`, not an inflated floor value.

NCSS is intentionally dropped because lines-changed already captures modification volume — including it would double-count. For a pure refactor that rewrites the algorithm without changing line count, `S` would redundantly echo `L`. Dividing by `(W_L + W_I) = 2` (instead of `TOTAL_WEIGHT = 3`) means a fully-modified block (`linesChanged = L`) scores the same magnitude as an equivalent NEW block.

### What counts as an "invocation"

The invocation counter walks the method's AST and counts one for each of:

- Instance method calls — `foo.bar()`
- Static method calls — `Utility.process()`
- Constructor calls — `new Foo(...)`
- Explicit constructor invocations — `this(...)`, `super(...)`
- Method references — `Foo::bar`

It **does not** recurse into nested type declarations (anonymous classes, local classes). Invocations inside an anonymous class body belong to that class's own methods, not the enclosing method. Lambdas, on the other hand, ARE walked — a lambda body's calls count for the enclosing method, because lambdas don't get their own code block.

### Why median-anchored projection instead of MinMax?

Earlier versions used min-max normalization with an anchor multiplication (`sL = (L - Lmin) / (Lmax - Lmin)`, then `driver = normalized × Lmax / 3`). That formulation had a subtle cross-dimensional leak:

- The lines dimension cancelled out cleanly: `sL × Lmax ≈ L`.
- But the NCSS and invocations contributions ended up multiplied by `Lmax / ncss.max` and `Lmax / invocations.max` — ratios determined by whichever *different* outlier block happened to own each dim's maximum. A 310-line constructor sitting in one class could silently halve or double the contribution of every other block's NCSS or invocation count.

Anchoring to per-dimension `p50` removes that coupling: each dimension's factor depends only on its own median and on lines' median, both of which are robust to outliers by construction. No bucket-level clamp is needed — the median itself is the protection against outlier samples, and the per-block deviation knob handles abuse at the right granularity.

---

## The global driver-score cap

Outlier protection lives at the **aggregate** level, not per block.

```
# per-(kind, scope) baseline contribution from each block:
bucketP95(block)    = P{statsQuantile} of the driver-score distribution within block's (kind, scope) population
                      (configurable; default statsQuantile = 0.95, clamped to ≥ 0.85)
operationMult       = addMult (NEW) or modifyMult (MODIFY)
testWeight          = 1.0 for prod, testCodeScoreMultiplier (default 0.4) for test

# global cap budget:
totalBaseline       = Σ_block ( bucketP95(block) × operationMult × testWeight )
globalCap           = totalBaseline × driverScoreCapMultiplier              # default 2.5

# per-block effort, uncapped:
blockEffort         = driverScore × operationMult × testWeight              # no per-block clip
totalEffortRaw      = Σ blockEffort

# global clip:
totalEffort         = min(totalEffortRaw, globalCap)
volumeScore         = pow(totalEffort, volumeExponent) × filesScopeMultiplier
```

Each bucket's `P{statsQuantile}` (default `P95`) is computed over its own single-scope population, so test-code outliers — which are systematically larger than prod methods — do not inflate the baseline that gets applied to prod blocks. Methods and constructors are split for the same reason: constructor populations are usually much smaller and tighter than method populations. If a `(kind, scope)` population is empty (e.g., a repo with no tests), the calculator falls back to the same-kind opposite-scope baseline.

### Worked example — cap doesn't bind

Suppose a commit changes 5 prod methods, all of medium size:

- bucketP95 for `method/prod` = 60
- modifyMult = 1.0, testWeight = 1.0
- per-block driver scores: `[35, 42, 28, 50, 31]`

```
totalBaseline    = 5 × 60 × 1.0 × 1.0 = 300
globalCap        = 300 × 2.5            = 750
totalEffortRaw   = 35 + 42 + 28 + 50 + 31 = 186
totalEffort      = min(186, 750)        = 186
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
totalEffort      = min(986, 900)        = 900
globalCapApplied = true
```

The outlier's excess (800 - 60 = 740 above its bucket baseline) absorbs all the slack the other blocks left in the budget; the global cap then truncates the sum at `900`. Per-block driver scores are reported untouched in the per-block table — the cap only manifests at the aggregate level via the `globalCapApplied` flag and the difference between `totalEffortRaw` and `blockEffortSum`.

### LLM diff-classification reduction

Git diff exposes only ADDED and DELETED lines. There is no native concept of MODIFIED. So a one-line in-place edit (`log.info("foo")` → `log.info("bar")`) shows as 1 deletion + 1 addition = 2 lines, double-counting effort. Pure-addition cosmetic content (new javadoc, log statements, formatting, license headers) similarly inflates volume even when nothing was deleted.

To compensate, the LLM is asked to classify EVERY changed line in the commit. For each eligible file (those flagged `linesJustificationRequired: true` in the request — production code, NOT POMs / config / generated artefacts) the LLM returns two `LineGroups` objects on `effortBreakdown.diffClassification.perFile[*]`:

- **`added: LineGroups`** — line numbers grouped by classification, using **new-file** line numbers.
- **`deleted: LineGroups`** — line numbers grouped by classification, using **old-file** line numbers.

Each `LineGroups` has three line-number arrays:

- **`cosmetic: List<Integer>`** — line numbers whose presence or absence does not change program behavior. Covers paired delete/add cosmetic edits (whitespace re-indent, comment-text changes, brace style flips, log-string tweaks) AND pure-addition cosmetic content (new javadoc, log statements, blank lines, formatting normalizations) AND pure-deletion cosmetic content (comments removed). Server treats these as 0 effort.
- **`inPlaceModify: List<Integer>`** — paired delete/add line numbers where the same intent survives in a different form (renamed local, swapped operand, tweaked expression). The LLM puts both halves of the pair in the corresponding `inPlaceModify` array on each side. Server collapses each pair to 1 line of effort instead of 2.
- **`trueDeleteAdd: List<Integer>`** — genuine work: real new logic, real removed logic, paired delete+add of different intent. Server keeps each line as 1 effort line.

**Per-file invariants (server-validated):**
```
len(added.cosmetic)   + len(added.inPlaceModify)   + len(added.trueDeleteAdd)   == linesAdded   for that file
len(deleted.cosmetic) + len(deleted.inPlaceModify) + len(deleted.trueDeleteAdd) == linesDeleted for that file
len(added.inPlaceModify) == len(deleted.inPlaceModify)
no line number repeats across the three arrays on the same side
```

**Server derives counts from arrays.** The `cosmeticLines`, `inPlaceModifyLines`, `trueDeleteAddLines` fields on `FileDiffClassification` and the corresponding totals on `DiffClassification` are populated by the server during validation by tallying the line-number arrays — any LLM-supplied values in those count fields are overwritten. Counts are persisted alongside the arrays so the DB layer / UI can aggregate without iterating the arrays.

**Effective-line factor and how it folds into the volume score.** For each classified file the server computes (using the derived counts):

```
effectiveLines      = inPlaceModifyLines / 2 + trueDeleteAddLines    # 0 effort for cosmetic, half for in-place, full for true
fileFactor          = effectiveLines / (linesAdded + linesDeleted)   # 1.0 means no reduction
```

`fileFactor` is then applied to **every** block in that file (both NEW and MODIFY operations) by multiplying the block's `driverScore` and `effort`. The block-level math then re-runs unchanged from there: `totalEffortRaw = Σ scaled effort`, `globalCap` re-tested, `volumeScore = pow(blockEffortSum, volumeExponent) × filesScopeMultiplier`. So the reduction lands as a deterministic per-file scaling of effort, not as a black-box LLM-decided number.

**Failure mode.** If the LLM omits `effortBreakdown.diffClassification`, returns line-number arrays whose total size doesn't match `linesAdded`/`linesDeleted` for that file, returns mismatched `inPlaceModify` array lengths between the two sides (broken pairing), repeats a line number across the three arrays on a side (double-counting), or names a file the request never touched, the server logs a warning and falls back to the unreduced pre-computed volume score. The feature is fail-safe.

**Pipeline position.** This step runs in `FinalScoreCalculator` *after* the LLM call returns and *before* the `complexityMultiplier` step. So the LLM's `combinedMultiplier` operates on the already-reduced `volumeScore`. The four mirror counters on `effortBreakdown.volumeScore` (`linesChangedRaw`, `linesChangedAdjusted`, `cosmeticLinesDropped`, `inPlaceLinesCollapsed`) record the before/after so reviewers can see exactly how much was reclassified.

### Dry-run mode (`driverScoreCapDryRun`)

The cap can be put into **audit-only** mode by setting `driverScoreCapDryRun = true` on the submission. In that mode the cap math runs in full — `globalCap`, `globalCapApplied`, `effortShare`, `globalCapDriver`, and the file-level abuse rollups are all computed and persisted exactly as if the cap were live — but `blockEffortSum` is **not** clipped: it is left equal to `totalEffortRaw`, and the volume score is `totalEffortRaw^volumeExponent × filesScopeMultiplier`. In other words, the score matches pre-cap (HEAD) behavior while the abuse signals continue to flow into the DB.

This is intended for two use cases:

1. **Calibration** — comparing the score distribution with and without the cap on real production traffic before locking in `driverScoreCapMultiplier`.
2. **A/B with HEAD** — replaying past commits under the new abuse-detection logic without changing any of the historical scores those commits produced.

A few rules that follow from the design:

- `globalCapApplied` retains its meaning: "the cap *would* clip if enforced". Combined with `globalCapDryRun`, downstream can distinguish "cap was enforced and clipped" from "cap was audited only" — both are computable from the persisted booleans.
- `globalCapDriver` still fires under dry-run (because `globalCapApplied` still reflects "would clip"). Without that, the dry-run mode would lose its principal abuse signal.
- The `blockEffortSum == totalEffortRaw` invariant under dry-run is the only behavioural difference from enforced mode. Replay must honor the persisted `driverScoreCapDryRun` flag verbatim, otherwise the volume score will diverge from the offline value.

---

## Driver-score abuse signals

These fields are **diagnostic**, not scoring inputs. They exist so a downstream DB can later identify developers whose blocks repeatedly look structurally engineered to inflate volume scoring, so the team can flag specific authors for review (or, on closer look, tune the threshold). False positives are expected — the goal is data, not enforcement.

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

For a MODIFY block, `blockRatioDeviationNcss` is **always `0.0`** (NCSS is dropped from the MODIFY score, so the dimension is not informative). `blockRatioDeviationInvocations` is computed using `effectiveInvocationsChanged / linesChanged` instead.

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

Reusing the L194-204 cap-binds example (one 800-driver outlier method alongside five reasonable ones):

```
totalEffortRaw   = 35 + 42 + 28 + 50 + 31 + 800 = 986
giant block effortShare = 800 / 986 ≈ 0.811     (above 0.75)
globalCapApplied = true
giant.globalCapDriver = true
```

The outlier method is flagged as the cap driver. The five small blocks each have `effortShare < 0.06` — none flagged. The signal makes "this commit hit the cap because of one block" attributable to that block (and its author).

---

## Reading the per-block breakdown in the report

The HTML report shows one row per changed code block with the full pipeline; the Maven console summary shows the same columns up to `Driver` (cap/effort are LLM-side concepts and live in the LLM scoring result, not the plugin output):

| Column | Where | Meaning |
|---|---|---|
| **L** | HTML + Maven | Non-comment code lines in the block. For MODIFY, this is the effective `linesChanged`, not the raw body length |
| **S** | HTML + Maven | NCSS (logical statements). For MODIFY, shown as the raw NCSS, but the dimension is not used in the score |
| **I** | HTML + Maven | Direct method invocations inside the block. For MODIFY, this is the effective `invocationsChanged` |
| **pL** | HTML + Maven | Lines-equivalent contribution of the L dimension (= L directly; included for symmetry with pS/pI) |
| **pS** | HTML + Maven | Lines-equivalent contribution of the S dimension (= `S · k_S`); rendered as `—` for MODIFY blocks (dimension dropped) |
| **pI** | HTML + Maven | Lines-equivalent contribution of the I dimension (= `I · k_I`) |
| **Driver** | HTML + Maven | Final driver score: `(pL + pS + pI) / 3` for NEW, `(pL + pI) / 2` for MODIFY |
| **Ratio** | HTML only | `changeRatio` for MODIFY blocks, `1.00` for NEW |
| **Effort** | HTML only | `Driver × operationMult × testWeight` — what the block contributes to `totalEffortRaw` |

**Interpretation tip:** scan the `pL / pS / pI` columns first. They're all in the same lines-equivalent unit, so you can compare them directly. If one of them is much larger than the others, that block is "big in one way only" — usually a builder (high pI), a Lombok-generated getter spray (high pL), or an algorithmic density method (high pS). If all three are balanced, the block is uniformly large.

---

## Four scalers, not one — why separate prod/test and method/constructor

A test method typically calls `assertEquals` 10 times in 15 lines. A production method typically has longer bodies with fewer assertion-style calls. A constructor is typically short and full of `this.x = x` assignments. Mixing these populations into one scaler would distort `k_I` (dragged up by test assertions) and `k_S` (dragged by imperative prod density), and every real change would be scored against an averaged-out ruler that matches none of the actual populations.

Separating into four scalers means each changed block ends up compared fairly to its actual peers.

---

## Stability across commits

The driver score is **re-calibrated every run**. Because the math is anchored to medians, adding a single 500-line monster method to the project barely moves `lines.p50` at all — so the projection factors (`k_S`, `k_I`) are very stable from commit to commit. Driver scores for unchanged blocks are nearly constant across commits, and adding or removing a handful of outliers does not perturb everyone else's score.

This is a deliberate choice. The alternative (freezing scalers once and reusing them) gives stable historical numbers but goes stale as the project grows. Because driver scores are consumed by a per-commit LLM pass (not plotted on a dashboard), drift is invisible to the end product. If you need stable trend tracking, use the final **commit score** or the **effort** fields.

---

## Configuration

Tunable knobs that affect the driver score and the global cap. All are persisted on the submission so server-side replay reproduces the same numbers regardless of server-side defaults.

| Field | Default | Effect |
|---|---|---|
| `statsQuantile` | `0.95` | Which percentile of the per-bucket driver-score distribution to use as the baseline contribution. Clamped to `≥ 0.85` |
| `driverScoreCapMultiplier` | `2.5` | Multiplier on `totalBaseline` to get the global cap on summed effort. Higher = more permissive cap |
| `driverFactorMaxDeviation` | `0.75` | Per-block deviation threshold. A block whose own `S/L` or `I/L` deviates from the bucket median ratio by more than this fraction is flagged as a ratio outlier; same value also gates `globalCapDriver`. Does **not** affect bucket-level projection factors (which are always raw `lines.p50 / dim.p50`). |
| `driverScoreCapDryRun` | `false` | When `true`, the global cap is computed and abuse signals (`globalCapApplied`, `globalCapDriver`, `effortShare`, file-level rollups) are populated, but `blockEffortSum` is **not** clipped to `globalCap` — the score uses the uncapped `totalEffortRaw`. Lets you compare uncapped vs. capped scoring while still surfacing abuse signals. |
| `volumeExponent` | `0.98` | Applied after `totalEffort` is computed; gentle compression (small/medium commits retain ~95%, large commits 500+ reduced ~25%) |
| `testCodeScoreMultiplier` | `0.4` | Multiplier applied to test-block effort (and to the test-block contribution to `totalBaseline`) |
| `addMultiplierScale`, `modifyMultiplierScale`, `modifyMultiplierCap`, `sizeFactorDivisor` | various | Together compute `addMult` and `modifyMult` based on project size — see `VolumeScoreCalculator.calculate` |

---

## The Maven console summary

When `codiqo-maven-plugin` runs, it prints a calibration block at the end:

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
  per-block driver scores are not individually clipped; the cap binds only on their sum
  method/prod:       quantile=52    bucket_budget_per_block=130
  method/test:       quantile=71    bucket_budget_per_block=178
  constructor/prod:  quantile=24    bucket_budget_per_block=60
  constructor/test:  quantile=13    bucket_budget_per_block=33

scalers:
  method/prod: (N=1604)
    lines:   min=1    p50=6    p75=14   p90=28   max=87
    ncss:    min=0    p50=3    p75=8    p90=17   max=52
    invocs:  min=0    p50=2    p75=6    p90=14   max=41
  ... (other three buckets) ...

driver_formula:
  weights:          W_L=1.00  W_S=1.00  W_I=1.00   (total=3.00)
  formula_new:      (W_L·lines + W_S·ncss·k_S + W_I·invocs·k_I) / total
  formula_modify:   (W_L·linesChanged + W_I·invocsChanged·k_I) / (W_L + W_I)
  factors (k_S = lines.p50 / ncss.p50, k_I = lines.p50 / invocs.p50; raw bucket-level — no clamp):
    method/prod:       k_S=1.250  k_I=1.250   (lines.p50=6, ncss.p50=3, invocs.p50=2)
    ... (other three) ...
```

Reading notes:

- `baseline_populations` shows the `N` each scaler was built from plus how many trivial blocks were filtered out.
- `driver_score_cap` shows the global formula and per-bucket `bucket_budget_per_block = quantile × driverScoreCapMultiplier`. This is the contribution **a single block in that bucket** makes to `totalBaseline` (before operation/test multipliers); the actual cap is the sum across all changed blocks.
- Each scaler prints `min / p50 / p75 / p90 / max` per dimension. Only the `p50` rows participate in the formula (via `k_S` and `k_I`); the other percentiles are informational.
- `driver_formula` shows the equal weights, the per-scope raw projection factors, and the formulas. Each `k_S` / `k_I` is annotated with the three p50 numbers it was derived from — useful for sanity-checking that the project's style is being captured faithfully.
- The non-trivial blocks table stops at `Driver` — `Effort` is applied later in the LLM scoring pipeline and shows up in the HTML report, not the Maven summary.

---

## FAQ

**Q: Why equal weights on all three dimensions?**
A: Because the projection factors (`k_S`, `k_I`) already put them on commensurable lines-equivalent scales. Unequal weights would be a second-order tuning on top of the projection, and we didn't want to hide a preference inside the formula. The weights are constants in `DriverScore.java` and easy to change.

**Q: Why median-anchored projection instead of raw counts (`L + S + I`) or MinMax?**
A: Raw counts would give each unit equal weight, which is wrong — one line of code is not the same amount of work as one NCSS statement or one method invocation, and the ratio depends on the project's coding style. The median-based factors let the project's own baseline say "on average, one S is worth roughly X lines in *this* codebase". The medians themselves are robust to outliers, so we take the resulting factors as-is — abuse protection lives one level down, on individual blocks via `driverFactorMaxDeviation`.

**Q: Why drop NCSS for MODIFY but keep it for NEW?**
A: For a brand-new block, `linesChanged = totalLines`, so all three dimensions carry independent information. For a modification, lines-changed already scales the block by how much of it was touched; adding NCSS on top would double-count that scaling.

**Q: What happens to test blocks' weight?**
A: Test blocks still get the `testCodeScoreMultiplier` (default `0.4`) applied to both their effort and their contribution to `totalBaseline`. The driver score itself is fair (test calibrated against test); the deweighting is a product decision applied uniformly at the effort step.

**Q: Why is the cap global instead of per-block?**
A: Per-block clipping conflates two different concerns — outlier-block protection and aggregate size protection. The global-cap design isolates the latter: the **global cap on `totalEffort`** handles size-outlier protection at the aggregate, while the **per-block deviation flag (`blockRatioOutlier`)** handles per-developer abuse signal as data — without distorting the score. A typical commit (no outliers) is unaffected by the cap; a commit with one giant block sees only the *aggregate* truncation, so a 600-line legacy refactor doesn't lose the contributions of the well-sized blocks alongside it.

**Q: Can I see the raw L, S, I for a specific block?**
A: Yes. They are on every `CodeBlockEffortView` row in the LLM scoring response JSON (`directInvocationCount`, `nonCommentCodeStatements`, `nonCommentCodeLines`) and in the per-block HTML report table.

---

## Persistence and replay

This section is written for the **backend replay service** (persists offline-plugin submissions to a database, then reconstructs them server-side and triggers an LLM callback) and for the **UI** (reads the same submission payload to render per-block tables and distribution charts). Both consume the same set of fields — the backend writes them, the UI reads them, and the replay step must reproduce identical driver scores from what was persisted.

### What must be persisted

Driver-score math is deterministic given the scalers + per-block raw counts + settings. Everything the offline plugin computes fits into the buckets below. Buckets (1)–(6) feed driver-score / effort / abuse-signal computation; bucket (7) is auxiliary per-call-site data consumed only by the diff-viewer UI.

**1. Project-wide calibration (per submission, once)** — enough to reconstruct every scaler on replay:

| Field | Schema location | Purpose |
|---|---|---|
| `driverScalers.methodScalerProd` | `DriverScalersModel` → `DriverScalerModel` | Four independent scalers; each is `{population, lines, ncss, invocations}` where each dimension is a `DimensionStatsModel` of `{min, p50, p75, p90, p95, max}` |
| `driverScalers.methodScalerTest` | same | — |
| `driverScalers.constructorScalerProd` | same | — |
| `driverScalers.constructorScalerTest` | same | — |
| `driverScalers.trivialMethodsProdExcluded`, `trivialMethodsTestExcluded`, `trivialConstructorsProdExcluded`, `trivialConstructorsTestExcluded` | `DriverScalersModel` | Diagnostic counts of blocks filtered from the populations; not used in the formula |
| `methodCapQuantileProd`, `methodCapQuantileTest`, `constructorCapQuantileProd`, `constructorCapQuantileTest` | submission root | `P{statsQuantile}` of the driver-score distribution per bucket. Used as the per-block contribution to `totalBaseline` on replay |
| `driverScalers.*.ncssFactor`, `invocationsFactor` | `DriverScalerModel` | Raw projection factors `lines.p50 / dim.p50` actually used in scoring (no clamp). `0.0` when `dim.p50 == 0`. Persisted on the request payload at submission time so consumers don't need to recompute. |

**2. Submission-level settings** — configurable knobs that tune the formula. These must ride with the payload so replay reproduces the same numbers even if server defaults change later:

| Field | Default | Notes |
|---|---|---|
| `statsQuantile` | `0.95` | Clamped to `≥ 0.85` |
| `driverScoreCapMultiplier` | `2.5` | Multiplier on `totalBaseline` for the global effort cap |
| `driverFactorMaxDeviation` | `0.75` | Per-block deviation threshold for `blockRatioOutlier` and `globalCapDriver`. Must match the offline plugin's value to reproduce these flags. Has no effect on bucket-level projection factors. |
| `driverScoreCapDryRun` | `false` | Audit-only mode for the cap: when `true`, `globalCapApplied` and the per-block abuse signals are still computed and persisted, but `blockEffortSum` is left equal to `totalEffortRaw` (cap is **not** applied to the score). Persisted on the submission and mirrored on `effortBreakdown.volumeScore.globalCapDryRun` so analytics can distinguish "cap was enforced" from "cap was only audited". Replay must honor this flag verbatim or volume scores will diverge. |
| `volumeExponent` | `0.98` | Compression exponent applied to `totalEffort` |
| `testCodeScoreMultiplier` | `0.4` | Test-block effort weight |

**3. Per-block raw counts (per changed code block)** — sufficient to re-derive `driverScore`, `scaledLines`, `scaledNcss`, `scaledInvocations` on replay:

| Field | Meaning | Used by formula for… |
|---|---|---|
| `bodyCodeLines` (`L`) | Body-only physical non-comment lines (`{` ... `}`, excluding signature). Used as `L` in driver-score calibration and per-block scoring | NEW; MODIFY uses `effectiveLinesChanged` capped to `bodyCodeLines` |
| `nonCommentCodeLines` | Full physical non-comment lines (signature + body). Reported for the report's full-block view; not used by the driver formula | display only |
| `location.bodyStartLine` / `location.bodyEndLine` | Lines of the body's `{` and `}`. Used to intersect the diff with the body for `effectiveLinesChanged`. Absent for code units without a body | MODIFY |
| `nonCommentCodeStatements` (`S`) | NCSS logical statement count | NEW only (dropped for MODIFY) |
| `directInvocationCount` (`I`) | Direct method invocations inside the block | NEW; MODIFY uses `effectiveInvocationsChanged` |
| `effectiveLinesChanged` | Lines in the block body intersected with the diff's added-line set | MODIFY |
| `effectiveInvocationsChanged` | Invocations whose source line is in the diff's added-line set | MODIFY |
| `changeRatio` | `effectiveLinesChanged / nonCommentCodeLines` (1.0 for NEW) | Reported in UI only |
| `kind` | `method` or `constructor` | Picks which scaler applies |
| `scope` | `prod` or `test` | Same |
| `operation` | `NEW` or `MODIFY` | Picks the formula |

**4. Per-block derived fields** — **redundant** given (1), (2), (3), but exposed on the request/response schema so the UI can render without re-running the math:

| Field | Formula | Notes |
|---|---|---|
| `driverScore` | NEW: `(W_L · L + W_S · S · k_S + W_I · I · k_I) / total`<br>MODIFY: `(W_L · linesChanged + W_I · invocsChanged · k_I) / (W_L + W_I)` | Recomputable on replay from scalers + raw counts |
| `scaledLines` (= `pL`) | NEW: `L`; MODIFY: `effectiveLinesChanged` | Lines-equivalent contribution of the L dimension |
| `scaledNcss` (= `pS`) | NEW: `S · k_S`; MODIFY: `0` | Lines-equivalent contribution of the S dimension. Field name retained for schema stability; the value is lines-equivalent, not a `[0, 1]` ratio |
| `scaledInvocations` (= `pI`) | NEW: `I · k_I`; MODIFY: `effectiveInvocationsChanged · k_I` | Lines-equivalent contribution of the I dimension |
| `cappedStatements` | `round(driverScore)` | No longer carries a per-block cap; equals the rounded raw driver score. Field name retained for schema stability |
| `effort` | `driverScore · operationMult · testWeight` | Per-block contribution to `totalEffortRaw`. Not individually clipped; the global cap binds on the sum |
| `bucketBaseline` | `bucketP95(block) · operationMult · testWeight` | Per-block contribution to `totalBaseline` |
| `blockRatioDeviationNcss` | NEW: `|block.S/block.L − bucket.ncss.p50/bucket.lines.p50| / (bucket.ncss.p50/bucket.lines.p50)`<br>MODIFY: `0.0` (NCSS dropped from MODIFY score) | **For backend:** computed during server-side replay; persisted on the response payload. Per-developer abuse signal candidate. |
| `blockRatioDeviationInvocations` | NEW: same shape using invocations dimension.<br>MODIFY: `0.0` (per-line ratios on a small diff are diff-shape artifacts, not block structure). | Same persistence semantics as above. |
| `blockRatioOutlier` | `blockRatioDeviationNcss > driverFactorMaxDeviation \|\| blockRatioDeviationInvocations > driverFactorMaxDeviation`. Always `false` for MODIFY since both deviations are 0. | Boolean flag — easy DB index for "show me commits with N or more outlier blocks". |
| `effortShare` | `block.effort / totalEffortRaw` (`0.0` if denominator is 0) | Always populated — used by UI for stacked-bar visualizations and by abuse analytics. |
| `globalCapDriver` | `globalCapApplied && effortShare > driverFactorMaxDeviation` | True iff this block alone owns more than `driverFactorMaxDeviation` (default `75%`) of total effort during a cap event — i.e. principal cause of cap binding. Still fires when `driverScoreCapDryRun == true` (signal must reach analytics regardless of whether the cap was enforced). |

**5. LLM diff-classification fields** — provided by the LLM and consumed by the server-side reduction step (see [LLM diff-classification reduction](#llm-diff-classification-reduction) above). All live on the LLM scoring response; the backend just persists them through.

| Field | Schema location | Meaning |
|---|---|---|
| `effortBreakdown.diffClassification` | `EffortBreakdownModel` → `DiffClassificationModel` | **Nullable.** Whole-commit roll-up + per-file array. Null when the LLM omitted it; in that case the volume score was not reduced and the mirror counters below are zero. |
| `diffClassification.totalLinesAddedRaw` | `DiffClassificationModel` | Sum of `linesAdded` across the files the LLM classified. |
| `diffClassification.totalLinesDeletedRaw` | `DiffClassificationModel` | Sum of `linesDeleted` across the same files. |
| `diffClassification.cosmeticLines` | `DiffClassificationModel` | Total lines reclassified as cosmetic (paired or unpaired). Effort weight 0. |
| `diffClassification.inPlaceModifyLines` | `DiffClassificationModel` | Total paired delete/add lines collapsed from 2 effort-lines per pair to 1. Always even. |
| `diffClassification.trueDeleteAddLines` | `DiffClassificationModel` | Total lines kept at full weight (1 effort-line each). |
| `diffClassification.perFile[*]` | `FileDiffClassificationModel` | Per-file breakdown — `{file, cosmeticLines, inPlaceModifyLines, trueDeleteAddLines, added, deleted}`. The two `LineGroups` objects are the **source of truth** supplied by the LLM; the three count fields are server-derived. The reduction is applied per-file from the line-number arrays; persist everything as-is so the classification is auditable line-by-line. |
| `diffClassification.perFile[*].added` | `LineGroupsModel` | Three line-number arrays (`cosmetic`, `inPlaceModify`, `trueDeleteAdd`) for the `+` side of this file's diff. All numbers are NEW-file line numbers. Sum of array sizes equals the request's `fileChanges[*].linesAdded`. No line number appears in more than one of the three arrays. |
| `diffClassification.perFile[*].deleted` | `LineGroupsModel` | Same shape, for the `-` side. All numbers are OLD-file line numbers. Sum equals `fileChanges[*].linesDeleted`. `len(added.inPlaceModify) == len(deleted.inPlaceModify)` (every in-place pair has both halves recorded). |
| `diffClassification.rationale` | `DiffClassificationModel` | One- or two-sentence explanation from the LLM. Display-only. |
| `effortBreakdown.volumeScore.linesChangedRaw` | `VolumeScoreModel` | Sum of `linesAdded + linesDeleted` across the classified files BEFORE reduction. `0` when no reduction was applied. |
| `effortBreakdown.volumeScore.linesChangedAdjusted` | `VolumeScoreModel` | Effective-line total after reduction (`Σ inPlace/2 + trueDeleteAdd`). `0` when no reduction was applied. |
| `effortBreakdown.volumeScore.cosmeticLinesDropped` | `VolumeScoreModel` | Total `cosmeticLines` excluded from the volume score. Mirrors `diffClassification.cosmeticLines` for files that were actually present in the request. `0` when no reduction was applied. |
| `effortBreakdown.volumeScore.inPlaceLinesCollapsed` | `VolumeScoreModel` | Number of pairs collapsed (`inPlaceModifyLines / 2`). Each pair removed 1 unit of effort versus the raw count. `0` when no reduction was applied. |

The reduction itself (the per-file factor that scaled each block's `driverScore` and `effort`) is **not persisted as a separate field** — it is derivable on replay from `(per-file FileDiffClassification, request.fileChanges[*].linesAdded/linesDeleted)`. The persisted block-level fields (`driverScore`, `effort`, `bucketBaseline`, `effortShare`, etc.) and the volume aggregates already reflect the post-reduction values, so a downstream consumer that only needs to render the score doesn't have to redo the math.

**For backend (replay):** treat `effortBreakdown.diffClassification` and the four `volumeScore.*` mirror counters as **derived outputs of the LLM scoring step**, not inputs. The replay path of the offline plugin doesn't need them — the offline plugin doesn't run the LLM. They appear only on the LLM response. Persist them through to the DB exactly as received so the UI can render them and so analytics can aggregate "how often the LLM reclassifies this team's commits as cosmetic". When server-side replay re-runs the LLM, the new response will produce its own classification — accept whatever the model returns provided it satisfies the per-file invariant; the post-LLM score (`baseEffortScore`, final `score`) is the source of truth.

**For UI:** see the rendering notes in section [What the UI can render from the submission payload](#what-the-ui-can-render-from-the-submission-payload).

**6. Per-file abuse-signal fields** — aggregations of the per-block fields above, also persisted on the response payload (one record per file in `EffortBreakdown.fileEfforts[*]`):

| Field | Formula | Notes |
|---|---|---|
| `blocksFlaggedAsRatioOutlier` | `count(blocks where blockRatioOutlier == true)` | **For backend:** persisted on the response payload after server-side replay. |
| `blocksFlaggedAsGlobalCapDriver` | `count(blocks where globalCapDriver == true)` | Same. |
| `maxBlockRatioDeviationNcss` | `max(blockRatioDeviationNcss across blocks)` | `0.0` if file has no blocks. |
| `maxBlockRatioDeviationInvocations` | `max(blockRatioDeviationInvocations across blocks)` | Same. |
| `fileFlaggedAsAbusive` | `blocksFlaggedAsRatioOutlier × 2 > totalBlocksInFile` | Strict-majority rule. File-level summary signal. |

`k_S` and `k_I` are derived inside `DriverScaler` at construction time as raw `lines.p50 / dim.p50` (with `0.0` when `dim.p50 == 0`), and persisted on `DriverScalerModel` as `ncssFactor` and `invocationsFactor`. No clamp is applied at this level — the medians are themselves robust to outliers, and `driverFactorMaxDeviation` only affects per-block flagging.

**7. Per-call-site invocation records (per `InvocationModel` within a code block)** — every direct method invocation inside a code block ships as an `InvocationModel` on `JavaInfoModel.invocations`, and is persisted as one row in `codiqo.method_calls` (joined to a single interned `codiqo.method_targets` row via `target_id`). These rows do **not** feed the driver-score formula — `directInvocationCount` (the per-block count) is what scoring uses. They exist exclusively for the diff viewer's per-line invocation badges and tooltips.

| Field | DB column | Meaning |
|---|---|---|
| `owner` / `name` / `descriptor` | `method_targets.owner` / `name` / `descriptor` | JVM bytecode coordinates of the resolved target. Interned: one `method_targets` row per unique `(owner, name, descriptor)` triple, referenced by many `method_calls` rows |
| `isStatic` / `isConstructor` | `method_targets.static_call` / `constructor_call` | Resolved-target traits (mirror) |
| `targetOwner` / `targetDescriptor` | `method_targets.target_owner` / `target_descriptor` | Declaring-class internal name and erased descriptor from the ClassGraph bytecode scan |
| `isMethodReference` / `isExplicitConstructor` / `isEnumConstant` | `method_calls.method_reference` / `explicit_constructor` / `enum_constant` | Per-call-site flags (which `MethodUsage` subtype) |
| `invocationKind` | `method_calls.invocation_kind` | `invokevirtual` / `invokeinterface` / `invokespecial` / `invokestatic` / `invokedynamic` / `reference` |
| `location.startLine` / `startColumn` / `endLine` / `endColumn` | `method_calls.loc_start_line` / `loc_start_column` / `loc_end_line` / `loc_end_column` | Source range of the **whole invocation expression**. For a chained call `Counter.builder().tag(...)`, `startLine` is the line of the chain's leftmost token (`Counter`), not of `tag`. Useful for full-expression highlighting; **not** the right bucket key for per-line counts |
| `nameStartLine` / `nameStartColumn` | `method_calls.name_start_line` / `name_start_column` | Source line/column of the **method-name token itself** (the `tag` IDENTIFIER in `.tag(...)`). Set by the offline plugin from the resolved `MethodUsage` AST: for `ASTMethodCall`, the IDENTIFIER token immediately before the args' `(`; for `ASTConstructorCall` (`new Foo(...)`), the type-name token; for `ASTExplicitConstructorInvocation`, the `this`/`super` keyword token; for `ASTMethodReference`, the trailing IDENTIFIER (or `new` for ctor refs); for `ASTEnumConstant`, the constant-name IDENTIFIER. **This is the correct bucket key for per-line invocation badges in the diff viewer** — see UI rendering notes below |
| `artifact` | `method_calls.artifact_id` (FK → `artifacts`) | Maven artifact in which the resolved target lives, when ClassGraph could attribute it to a dependency JAR |

**Why two locations per row?** PMD's `MethodUsage.getBeginLine()` reports the leftmost token of the whole invocation expression. For a chained call

```java
Counter.builder(METRIC_NAME)        // line 47
        .tag(TAG_TYPE, ...)         // line 48
        .tag(TAG_REASON, ...)       // line 49
        ...
        .increment();               // line 58
```

every chained `.tag(...)` is its own `ASTMethodCall` whose begin position is `Counter` (line 47/48). Bucketing the diff-viewer's per-line invocation badges by `loc_start_line` collapses all 10+ chained calls onto the first line and leaves lines 49–58 visually empty. `nameStartLine` instead points at the line of the method-name IDENTIFIER (`tag`, then `tag`, then `increment`), giving each chained call its own line. The `loc_*` range is retained for any consumer that wants to highlight the full expression (it's the only place the chain's true span is recorded).

**For backend (replay):** the per-call-site rows are recomputed from scratch by the offline plugin on every analysis; replay does not need to derive them. Persist whatever the submission carries. The current plugin populates `nameStartLine` / `nameStartColumn` for every invocation (with a `usage.getReportLocation()` fallback if a future `MethodUsage` subtype is unrecognized), so consumers can rely on the columns being non-null in newly ingested data; the columns remain `nullable` for symmetry with the other location columns.

### Replaying a submission

A server-side replay of an offline submission should:

1. Load the persisted `DriverScalersModel` and rebuild four `DriverScaler` instances via `DriverScaler.fromPersisted(population, lines, ncss, invocations)`. Bucket factors are derived from the persisted `(lines, ncss, invocations)` medians as raw `lines.p50 / dim.p50` — no clamp parameter needed.
2. For each changed block, pick the matching scaler by `(kind, scope)`, then call `DriverScore.forNew` or `DriverScore.forModify` with the persisted raw counts (`L`, `S`, `I` for NEW; `effectiveLinesChanged`, `effectiveInvocationsChanged` for MODIFY).
3. Compute `bucketBaseline` per block as `bucketP95 × operationMult × testWeight`. Fall back to the same-kind opposite-scope `bucketP95` if a population was empty at submission time.
4. Sum to `totalBaseline` and `totalEffortRaw`. Compute `globalCap = totalBaseline × driverScoreCapMultiplier` and `globalCapApplied = totalEffortRaw > globalCap`. If the persisted `driverScoreCapDryRun` is `false`, set `totalEffort = min(totalEffortRaw, globalCap)`; if it is `true`, set `totalEffort = totalEffortRaw` (cap audited, not enforced). Raise to `volumeExponent` and multiply by `filesScopeMultiplier`. The abuse signals (`globalCapDriver`, file-level rollups) are derived from `globalCapApplied` regardless of the dry-run flag.

Because each step only reads persisted fields and deterministic constants, the replayed volume score must match the offline value exactly. If it doesn't, either a scaler field, a raw block count, or one of the knobs in bucket (2) was lost or mutated during persistence.

**For backend (replay & abuse signals):** the abuse-signal fields in buckets (4) and (6) are *derived* from `(scaler + raw block counts + driverFactorMaxDeviation)`. Two valid implementation strategies on replay:

- **Trust the persisted values** (simpler) — the offline plugin already computed them; just write them through to your DB tables.
- **Recompute and assert equality** (paranoid) — derive `blockRatioDeviation*`, `blockRatioOutlier`, `effortShare`, `globalCapDriver`, and the file rollups using the formulas in section (4)/(5) above, and assert they match the persisted values within rounding tolerance. This catches any persistence corruption before it hits analytics.

Both are correct. Pick based on how much you trust the persistence layer.

### What the UI can render from the submission payload

The UI never re-runs the math — it reads the precomputed per-block fields and presents them. Per-block table:

- `nonCommentCodeLines` → column **L**
- `nonCommentCodeStatements` → column **S**
- `directInvocationCount` → column **I**
- `scaledLines` → column **pL**
- `scaledNcss` → column **pS** (render as `—` when `operation == MODIFY`)
- `scaledInvocations` → column **pI**
- `driverScore` → column **Driver**
- `changeRatio` → column **Ratio**
- `effort` → column **Effort**
- `effortShare` → column **Share** (or stacked-bar visualization across the file/commit)
- `blockRatioOutlier` → inline badge on the row (e.g. red dot)
- `blockRatioDeviationNcss`, `blockRatioDeviationInvocations` → tooltip / detail-view values when the badge fires
- `globalCapDriver` → distinct badge (e.g. "cap driver") rendered only when `effortBreakdown.globalCapApplied` is true
- `operation`, `kind`, `scope` → for row classification and MODIFY/NEW badging

Per-file row (above the per-block table):

- `blocksFlaggedAsRatioOutlier`, `blocksFlaggedAsGlobalCapDriver` → small counters
- `maxBlockRatioDeviationNcss`, `maxBlockRatioDeviationInvocations` → "worst block deviation" indicators
- `fileFlaggedAsAbusive` → file-level badge (e.g. amber row highlight)
- `effortBreakdown.diffClassification.perFile[file == this.path]` (look up by file path) → render the three counts as a small `cosmetic / in-place / true` chip when the file appears in the LLM classification. Files with `cosmeticLines > 0` should get a subdued indicator that "the LLM reclassified N lines as cosmetic"; files with `inPlaceModifyLines > 0` should indicate "M pairs collapsed". When `effortBreakdown.diffClassification` is null, omit the chip entirely.
- `added` / `deleted` (`LineGroupsModel`) → use these for **line-level rendering in the diff view**. Build a lookup `Map<Integer, Classification>` per side by walking each of the three arrays once: every line number in `added.cosmetic` is COSMETIC, every line number in `added.inPlaceModify` is IN_PLACE_MODIFY, etc. Then for each `+` line at file line `N` color/badge it from the map (e.g. dim grey for `cosmetic`, soft yellow for `inPlaceModify`, normal for `trueDeleteAdd`); same for `-` lines on the deleted side using the deleted-side map. Hovering should show a tooltip with the classification name. This is purely visual — the score is already adjusted server-side.

Volume-score panel (the existing `volumeScore` summary view):

- When `linesChangedRaw > 0` (i.e. the reduction actually fired), surface a small "raw → adjusted" indicator — e.g. `linesChangedRaw → linesChangedAdjusted` with a tooltip listing `cosmeticLinesDropped` and `inPlaceLinesCollapsed`. This explains to the reviewer why the volume score is lower than the line-count alone would suggest.
- When `linesChangedRaw == 0`, the LLM did not classify (or didn't return classification); render the panel as before with no reduction indicator.
- Optional: a "diff classification rationale" details strip that displays `effortBreakdown.diffClassification.rationale` verbatim. Useful for senior-review explainers; safe to hide behind a "Why was this reduced?" disclosure.

Diff-viewer per-line invocation badges (the per-line "N invocations" chip rendered in the file content view):

- **Bucket key:** `method_calls.name_start_line`. Do **not** bucket by `method_calls.loc_start_line` — that column reports the leftmost token of the whole invocation expression, so chained calls (`Counter.builder().tag().tag()...`) collapse onto the chain's first line and produce inflated badges there with empty lines underneath.
- For each `code_unit_id` belonging to the file being rendered, group the unit's `method_calls` rows by `name_start_line` and emit one badge per non-empty line. Order ties by `name_start_column ASC` so multiple invocations on the same line render in source order.
- Filter: `WHERE name_start_line IS NOT NULL` (steady-state data always populates it; the predicate guards against a hypothetical unrecognized `MethodUsage` subtype where the plugin's fallback also could not resolve a token).
- Tooltip per badge: list the joined `method_targets.target_owner.target_name(target_descriptor)` for each row in the bucket, with `invocation_kind` and the per-call-site flags (`method_reference` / `explicit_constructor` / `enum_constant`) shown as small modifier chips. The full-expression range from `loc_start_*` / `loc_end_*` may be used to underline / highlight the underlying expression on hover.

For the calibration panel, the UI reads `driverScalers.*.lines / ncss / invocations` (each `{min, p50, p75, p90, p95, max}`) and the four `*CapQuantile*` fields at submission root. Aggregate fields shown alongside (`totalEffortRaw`, `totalBaseline`, `globalCap`, `globalCapApplied`) come from the LLM scoring response's `effortBreakdown.volumeScore` payload.

The calibration panel should **also** render the new factor diagnostics from each `driverScalers.*` (4 buckets):

- `ncssFactor` (`k_S`), `invocationsFactor` (`k_I`) — raw projection factors per bucket. Render alongside `lines.p50`, `ncss.p50`, `invocations.p50` so reviewers can see how the project's medians produced the factors.

**For UI:** the UI must **not** re-derive any of these values — read them from the response payload exactly as the backend persisted them. The backend already applied the formulas; recomputing in the UI risks drift if the offline plugin's interpretation changes in a future release.

---

## Glossary

| Term | Meaning |
|---|---|
| **Driver score** | The composite line-count-magnitude number that drives volume scoring. Computed per changed block as a weighted average of L, S·k_S, I·k_I (NEW) or linesChanged + invocsChanged·k_I (MODIFY) |
| **Scaler** | A bucket-level statistical summary: `(min, p50, p75, p90, p95, max)` per dimension, computed from a project population. `p50` drives the projection factors; the rest are diagnostics. `p95` is also used as the per-block contribution to the global cap baseline |
| **Projection factor** | A median-anchored conversion factor from one dimension's count into lines-equivalent units. `k_S = lines.p50 / ncss.p50`, `k_I = lines.p50 / invocations.p50`. Computed raw at the bucket level — no clamp. A factor of `0.0` means the dimension has no signal in the population (`dim.p50 == 0`) and contributes nothing to the projection |
| **`driverFactorMaxDeviation`** | Per-block deviation threshold, default `0.75`. Gates `blockRatioOutlier` and `globalCapDriver`. Has no effect on bucket-level projection factors. |
| **`driverScoreCapDryRun`** | Audit-only flag, default `false`. When `true`, the cap is computed and `globalCapApplied`/`globalCapDriver` still fire, but `blockEffortSum` is **not** clipped — the volume score uses the uncapped `totalEffortRaw`, matching pre-cap (HEAD) behavior. Used to compare scoring with and without the cap while still collecting abuse signals. |
| **pL, pS, pI** | Lines-equivalent contributions of each dimension to the driver: `pL = L`, `pS = S · k_S`, `pI = I · k_I` (or, for MODIFY, the `*Changed` variants with `pS = —`) |
| **Scope** | prod vs test — which source set a block belongs to |
| **Kind** | method vs constructor |
| **L** | Non-comment code lines in the block **body** (`bodyCodeLines` — `{` ... `}`, excluding signature) |
| **S** | NCSS — logical statement count |
| **I** | Direct method invocations inside the block (excluding nested types) |
| **Driver-score cap (global budget)** | A single ceiling on the sum of all per-block efforts, sized as `totalBaseline × driverScoreCapMultiplier` (default `2.5`). Per-block driver scores are *not* individually clipped; the cap binds only on the sum |
| **`totalBaseline`** | `Σ_block (bucketP95 × operationMult × testWeight)` — the bucket-aware budget that the global cap multiplies |
| **`totalEffortRaw`** | `Σ_block (driverScore × operationMult × testWeight)` — sum of per-block efforts before the global cap |
| **`globalCapApplied`** | `true` iff `totalEffortRaw > globalCap` (i.e., the cap clipped the sum) |
| **Trivial block** | A method or constructor with `NCSS ≤ 2` **and** `directInvocationCount == 0` **and** `cyclo ≤ 1`. Excluded from scaler populations and skipped when scoring changes |
| **Block ratio outlier** | A code block whose own `S/L` (or `I/L`) ratio deviates from the bucket median ratio by more than `driverFactorMaxDeviation` (default `75%`). Per-developer abuse-signal candidate. |
| **`effortShare`** | A code block's fraction of the commit's total raw effort: `block.effort / totalEffortRaw`. Always populated; used for cap-driver attribution and stacked visualizations. |
| **`globalCapDriver`** | A code block flagged when `globalCapApplied == true` AND its `effortShare` exceeds `driverFactorMaxDeviation`. Identifies the principal cause of a cap event, attributable to a specific block (and author). |
| **`fileFlaggedAsAbusive`** | A file where strict majority (`outliers × 2 > total`) of its blocks are ratio outliers. File-level rollup of the per-block abuse signal. |
| **Diff classification** | LLM-provided per-line reclassification of changed lines. The LLM returns two `LineGroups` per eligible file (`added` for `+` lines using new-file line numbers, `deleted` for `-` lines using old-file line numbers), each grouping line numbers into `cosmetic`, `inPlaceModify`, `trueDeleteAdd`. Compensates for git's lack of a "modified" line concept. Lives on `effortBreakdown.diffClassification` of the LLM scoring response. |
| **`LineGroups` (`added` / `deleted`)** | Per-side container with three line-number arrays — `cosmetic`, `inPlaceModify`, `trueDeleteAdd` — that together cover every changed line on that side without duplicates. Source of truth for both the per-file count fields (server-derived) and for line-level UI rendering. |
| **`cosmetic`** | Line-numbers list within `LineGroups` — lines whose presence/absence does not change behavior (whitespace, comments, log strings, license headers, brace style flips). Effort weight 0. |
| **`inPlaceModify`** | Line-numbers list within `LineGroups` — paired delete/add lines where the same intent survives in a different form (renamed local, swapped operand). Each pair has one entry on each side; counts must match across `added.inPlaceModify` and `deleted.inPlaceModify`. Each pair collapses from 2 effort lines to 1. |
| **`trueDeleteAdd`** | Line-numbers list within `LineGroups` — lines that represent genuine work (real removal, real new logic, real delete+add of different intent). Full weight. |
| **`cosmeticLines` / `inPlaceModifyLines` / `trueDeleteAddLines`** | Server-derived count fields on `FileDiffClassification` — tallied by the server from the per-side `LineGroups` arrays during validation. Persisted alongside the arrays for fast aggregation. |
| **`effectiveLineFactor`** | Per-file scaling factor applied to every block's `driverScore` and `effort` after the LLM classification: `(inPlaceModifyLines/2 + trueDeleteAddLines) / (linesAdded + linesDeleted)`. `1.0` means no reduction; `0.0` means the file's entire change was cosmetic. |
| **`linesChangedRaw` / `linesChangedAdjusted`** | Volume-score mirror counters that record the line totals before/after diff-classification reduction. Both `0` when no classification was applied. |
| **`cosmeticLinesDropped` / `inPlaceLinesCollapsed`** | Volume-score mirror counters that summarize how the reduction shifted lines: total lines treated as 0-effort, and number of pairs collapsed from 2 lines to 1. |
| **`InvocationModel` / `method_calls`** | Per-call-site record. One `InvocationModel` per direct method invocation inside a code block, persisted as one `codiqo.method_calls` row joined to an interned `codiqo.method_targets` row. Carries owner/name/descriptor of the resolved target plus two source locations (`location.*` for the full expression, `nameStart*` for the method-name token). Drives the diff viewer's per-line invocation badges; not used by the driver-score formula (which uses the per-block `directInvocationCount` count instead). |
| **`location.startLine` (on `InvocationModel`)** | Line of the leftmost token of the whole invocation expression. For a chained call, this is the line of the chain's first qualifier — same value across every chained `.method()` call sharing the chain. Persisted as `method_calls.loc_start_line`. Not the right bucket key for per-line counts. |
| **`nameStartLine` / `nameStartColumn`** | Source line/column of the method-name IDENTIFIER token of an invocation (the `tag` in `.tag(...)`, the type-name in `new Foo(...)`, the keyword in `this(...)`/`super(...)`, the trailing identifier in `Foo::bar`, the constant name in an enum-constant constructor). Persisted as `method_calls.name_start_line` / `name_start_column`. **The correct bucket key for the diff viewer's per-line invocation badges** — using `loc_start_line` instead would collapse all chained calls onto one line. |
