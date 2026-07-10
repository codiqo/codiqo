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

# dev-testing baseline: 10 random code-bearing commits (>=1 .java changed) from the last
# 3 months on main (window 2026-04-10..2026-07-01, drawn 2026-07-09). Number in () = java lines changed.
COMMITS=(
  87710eacc15cb02c86aaed5593764f775af5a84d  # GH-4504: Fix silent record loss with two failing asy (48)
  ca2337ba789c5778a10197bda17a62915247ff6c  # GH-4489: Fix unbounded cache in DelegatingDeserializ (51)
  7495b376bec5e80fd86377aef60a318962ce80f9  # GH-4493: Harden retry topic headers decoding (114)
  8b4e205d6ab8958dd53705a1e6c816bcad608233  # GH-4468: Fix no-op ack detection for non-null Acknow (31)
  7c9f5c0b0dcd5f027708112fdb703e5410f705fd  # GH-4455: Fix BackOffFunction for batch listeners (190)
  3a3be04b62905b7a16c5e4fca0d3f179c2e4e438  # GH-4439: Treat empty recordList same as null in batc (96)
  5be5015a67952ab88891c05676b58489bac12ee2  # GH-4430: Wrong record content passed to KafkaStreams (292)
  7fa54e2796549bd0df778bfc220a4e7849fb9132  # Attempt to fix flaky retry test in CI (22)
  27b2b03f221ad88ebd36c7a675ae27f98a94f41e  # Fix code style in RetryableTopicAnnotationProcessorT (10)
  cecc2f9a85d39ad2d0ab5ae91d04103fb9befd26  # GH-4327: Publish share container stop and failed-sta (337)
)


export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
mkdir -p "$OUT"
cd "$SK"

for sha in "${COMMITS[@]}"; do
  echo "==================== $sha ($MODE) ===================="
  # force: prior iterations leave gradle.properties dirty (heap bump below), which aborts a plain checkout
  git checkout -f -q "$sha" || { echo "checkout failed for $sha"; continue; }
  # per-commit: bump daemon heap (each checkout resets gradle.properties); restored by final checkout
  perl -i -pe 's/-Xmx1536M/-Xmx6g/' gradle.properties
  # force fresh classes for the checked-out sources — git checkout can leave same-content classes
  # with older mtimes, which trips the engine's staleness guard
  rm -rf spring-kafka*/build/classes spring-kafka*/build/jacoco

  if [ "$MODE" = "full" ]; then
    # single invocation: the plugin makes codiqoDumpAnalysis mustRunAfter every Test task, so the dump
    # reads build/jacoco/test.exec only after tests write it (correct even under org.gradle.parallel).
    # --continue: a flaky test must not abort before the dump (mustRunAfter is ordering, not dependency).
    ./gradlew --init-script "$INIT" --continue \
      test jacocoTestReport \
      codiqoDumpAnalysis \
      -Pcodiqo.commitId="$sha" \
      -Pcodiqo.outputDirectory="$OUT" \
      --console=plain || echo "!! build failed for $sha (continuing)"
  else
    ./gradlew --init-script "$INIT" \
      testClasses \
      codiqoDumpAnalysis \
      -Pcodiqo.commitId="$sha" \
      -Pcodiqo.outputDirectory="$OUT" \
      -Pcodiqo.ignoreCoverage=true \
      --console=plain || echo "!! build failed for $sha (continuing)"
  fi
done

git checkout -f "$RESTORE_REF"
echo "restored $SK to $RESTORE_REF"
echo "dumps in: $OUT"
ls -1 "$OUT"/codiqo-submission-*.yaml 2>/dev/null | sed 's#.*/#  #'
