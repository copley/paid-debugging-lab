# Case Study 036 — GitHub Actions runner orphan-process snapshot race

## Symptom

A persistent self-hosted GitHub Actions runner can finish a cancelled job while leaving a process from that job running indefinitely. The leaked process still carries the job's `RUNNER_TRACKING_ID`, but it was created after the runner took its one process-table snapshot during final cleanup.

Upstream issue: https://github.com/actions/runner/issues/4601

## Source-level diagnosis

The runner's final job cleanup currently performs this lifecycle:

1. Take one `SnapshotProcesses()` result.
2. Iterate that fixed dictionary.
3. Ignore processes that existed before the job.
4. Read `RUNNER_TRACKING_ID` from each candidate process environment.
5. Kill matching processes.
6. End cleanup without taking another snapshot.

The relevant code is in `src/Runner.Worker/JobExtension.cs`.

That creates a time-of-check/time-of-use gap. A job process can spawn a child after `SnapshotProcesses()` has completed but before its parent is killed. The child is not present in the captured dictionary, so it is never inspected. Once the parent is killed, the child can be re-parented and survive the job indefinitely.

The window is wider than a single process-table syscall because cleanup performs per-process environment inspection serially before killing matching processes.

## Important boundary

A second process-table pass is a contained mitigation, but a fixed number of passes does not provide a mathematical guarantee if a tracked process can continue spawning children during cleanup.

`Process.Kill(entireProcessTree: true)` is useful but is also not sufficient alone: a child created after a parent's tree has already been killed, or a process whose environment cannot be inspected, can still escape the tracking-ID scan.

A Linux cgroup/job-object style ownership boundary would be the stronger architecture because membership is maintained by the OS rather than rediscovered from process environment variables. That is a substantially larger change, however.

## Practical PR scope

For a small compatibility-preserving patch, keep the existing tracking-ID mechanism and make cleanup converge instead of performing one pass:

- extract one scan-and-kill pass into a helper;
- retain the original `_existingProcesses` snapshot so pre-job processes remain protected;
- repeat the scan after any matching process was terminated;
- bound the loop to prevent malicious or pathological workloads from keeping teardown alive forever;
- optionally use `Kill(entireProcessTree: true)` where supported to narrow the spawn window further;
- emit a warning if the final bounded pass still finds tracked processes.

A stronger future design can move Linux self-hosted job processes into a dedicated cgroup and terminate the cgroup at job teardown.

## Regression strategy

A useful regression should not rely only on timing sleeps. Introduce a seam around process enumeration/environment lookup so a test can return:

- pass 1: tracked parent A;
- pass 2: newly observed tracked child B;
- pass 3: no tracked processes.

Then assert:

- A and B are both terminated;
- processes captured in `_existingProcesses` are never killed;
- cleanup stops after the first quiescent pass;
- the maximum-pass bound is honoured for a continuously replenished process set;
- an environment-read failure remains non-fatal and is logged.

An integration test on Linux can additionally spawn a helper that creates a child during teardown and verify that no process with the test tracking ID remains after cleanup.

## Diagnostic comment draft

> The source confirms the race is in the cleanup lifecycle rather than in process identification: `JobExtension` takes one `SnapshotProcesses()` result and then performs every environment read and kill against that fixed dictionary. A child created after the snapshot can therefore inherit the job tracking ID, outlive its parent, and never be inspected.
>
> For a contained fix I would extract a scan-and-kill pass and repeat it until one pass kills nothing, with a small hard upper bound so teardown cannot be held open indefinitely. The original `_existingProcesses` set must remain the exclusion baseline across every pass. `Kill(entireProcessTree: true)` can narrow the window further, but I would not use it as the sole fix because it cannot recover a child created after its parent's kill point.
>
> The regression can be deterministic by injecting process snapshots: parent on pass 1, newly-created child on pass 2, empty on pass 3. That directly tests the missing lifecycle edge without depending on scheduler timing.

## Verification status

This is a source-inspection diagnosis and PR plan. No upstream patch has been executed against the GitHub Actions runner test suite in this case study.