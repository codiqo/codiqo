# Codiqo engine

The build-tool plugins and libraries that analyse a project's commits on the developer's side, review them with agents
and submit them to Codiqo for scoring.

## Commits

**Commit outcome**:
What one run does with one commit it analysed: the commit is submitted for scoring, excluded, or the project is halted.
It is decided by the same rules whichever build tool ran the analysis.
_Avoid_: commit result, commit status

**Build verdict**:
Whose failure a commit's build is, when it failed: the author's, the environment's, a timeout, or Codiqo's own.
_Avoid_: build result, build status

**Exclusion**:
A commit recorded as not counting toward the project's figures, with a category and a reason. It counts again when
someone allowed to re-includes it, or when its analysis is deleted and runs again.
_Avoid_: skip, ignored commit

**Halt**:
A project that analyses no further commits, after a failure on Codiqo's or the model provider's side, until an
administrator resumes it.
_Avoid_: pause, block, stop

**Degraded commit**:
A commit whose build the author broke, scored from its source and its local review alone, with penalties and no bonus.
_Avoid_: failed commit, broken commit

**Held score**:
The score of a commit whose test run failed in large part, kept as an exclusion until someone allowed to accepts it.
_Avoid_: pending score, quarantined commit

## Local review

**Local review**:
The agents' review of one commit, run next to its analysis on the developer's side, which judges the commit for the
score.
_Avoid_: LLM review, server review

**Review verdict**:
How a local review ended: succeeded, failed on the commit, failed on the model provider, failed on Codiqo's side, or
stopped. Only a review that failed on the commit excludes it; a provider or Codiqo failure halts the project.
_Avoid_: review result, review status
