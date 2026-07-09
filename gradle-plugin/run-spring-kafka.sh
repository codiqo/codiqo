#!/usr/bin/env bash
#
# Dump Codiqo analysis for a set of spring-kafka commits using the Codiqo Gradle plugin.
# Mirrors the Maven per-commit dump loop, adapted to Gradle:
#   - the plugin analyzes the CURRENT checkout, so we git-checkout each commit first
#   - spring-kafka ships no jacoco -> the init script force-applies it
#   - spring-kafka's daemon heap (1536M) is too small for the in-process engine -> bumped to 6g
#   - the root task resolves subproject configs -> Gradle 9 requires --no-parallel
#
# Prereqs: `mvn install` in the codiqo repo (publishes io.codiqo:* to ~/.m2), JDK 25 installed.
#
# MODE=fast (default): compile only, no coverage  -> minutes per commit
# MODE=full          : run tests + jacoco          -> real changed-line coverage, slow (large suite)

set -uo pipefail

SK="${SK:-/Users/andreyborisov/dev/spring-kafka}"
INIT="${INIT:-/Users/andreyborisov/dev/codiqo/gradle-plugin/codiqo.init.gradle}"
OUT="${OUT:-/Users/andreyborisov/dev/tmp/reports-gradle}"
MODE="${MODE:-fast}"
RESTORE_REF="${RESTORE_REF:-main}"

COMMITS=(
  cecc2f9a85d39ad2d0ab5ae91d04103fb9befd26  # GH-4327 share container stop/failed-start events (337)
  5be5015a67952ab88891c05676b58489bac12ee2  # GH-4430 KafkaStreams DLQ record content (292)
  51427d9c55066786063ba241a93ae3697a7ebd53  # GH-4380 AcknowledgementCommitCallback share consumer (200)
  7c9f5c0b0dcd5f027708112fdb703e5410f705fd  # GH-4455 BackOffFunction batch listeners (190)
  7dab9351ce54b37fa71a77123fab7dfb0e3f7e8b  # GH-4504 bound async retry (189)
  7495b376bec5e80fd86377aef60a318962ce80f9  # GH-4493 retry topic headers decoding (114)
  9439fbb9ec9f3ccdca205cf0bda446a4afa57531  # GH-4496 trusted header types (100)
  890cef92764817ef4395346e9037b22e66b3927b  # await share consumer startup (97)
  3a3be04b62905b7a16c5e4fca0d3f179c2e4e438  # GH-4439 batchRollback empty recordList (96)
  f62f0bc27be8e03162ec1d95469f760cabaa2290  # GH-4384 override properties consistency (93)
)

if [ "$MODE" = "full" ]; then
  BUILD_TASKS=(test jacocoTestReport)
  COVERAGE_ARGS=()
else
  BUILD_TASKS=(testClasses)
  COVERAGE_ARGS=(-Pcodiqo.ignoreCoverage=true)
fi

export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
mkdir -p "$OUT"
cd "$SK"

for sha in "${COMMITS[@]}"; do
  echo "==================== $sha ($MODE) ===================="
  git checkout -q "$sha" || { echo "checkout failed for $sha (dirty tree?)"; continue; }
  # per-commit: bump daemon heap (each checkout resets gradle.properties); restored by final checkout
  perl -i -pe 's/-Xmx1536M/-Xmx6g/' gradle.properties
  # force fresh classes for the checked-out sources — git checkout can leave same-content classes
  # with older mtimes, which trips the engine's staleness guard
  rm -rf spring-kafka*/build/classes spring-kafka*/build/jacoco

  ./gradlew --init-script "$INIT" \
    "${BUILD_TASKS[@]}" \
    codiqoDumpAnalysis \
    -Pcodiqo.commitId="$sha" \
    -Pcodiqo.outputDirectory="$OUT" \
    "${COVERAGE_ARGS[@]}" \
    --console=plain || echo "!! build failed for $sha (continuing)"
done

git checkout -f "$RESTORE_REF"
echo "restored $SK to $RESTORE_REF"
echo "dumps in: $OUT"
ls -1 "$OUT"/codiqo-submission-*.yaml 2>/dev/null | sed 's#.*/#  #'
