# Case Study 044 — GitHub Actions background-step event.json locking

## Problem

GitHub Actions runner issue `actions/runner#4641` reports intermittent Windows failures when several action-backed background steps start in parallel. The failing steps all try to write the same job webhook payload file:

```text
_temp/_github_workflow/event.json
```

Windows then rejects one of the concurrent writes because another process/thread has the file open:

```text
The process cannot access the file '...\_github_workflow\event.json'
because it is being used by another process.
```

The same reproduction is stable on Linux, where the filesystem sharing semantics make the race less visible.

## Root cause

The runner currently invokes:

```csharp
ExecutionContext.WriteWebhookPayload();
```

from `ActionRunner.RunAsync()` for every action execution.

`WriteWebhookPayload()` always targets the same job-scoped path and, when `github.event` exists, performs an unconditional overwrite:

```csharp
var workflowFile = Path.Combine(workflowDirectory, "event.json");
File.WriteAllText(workflowFile, gitHubEvent, new UTF8Encoding(false));
SetGitHubContext("event_path", workflowFile);
```

That design was harmless while action steps were effectively serialized. Parallel/background action execution changes the concurrency assumption: multiple child execution contexts can now enter the method at the same time, but all of them write the identical physical file.

The important distinction is that two concerns have been coupled:

1. materializing the job webhook payload on disk;
2. exposing its path through the current execution context as `github.event_path`.

The first operation only needs to happen once per job. The second may still need to happen for each child execution context.

## Why a per-step lock is insufficient

Putting a new lock directly on each child `ExecutionContext` would not solve the race. Parallel actions use different child execution-context objects, while the contested file belongs to their shared root job.

The synchronization and the "already written" state therefore need to be owned by the root execution context.

The invariant should be:

```text
one job
  -> one immutable webhook payload
  -> one physical event.json write
  -> many child contexts may reference the same path
```

## Likely fix path

A contained fix is to make payload materialization root-job idempotent while preserving the existing `event_path` assignment for each caller.

Conceptually:

```csharp
private readonly object _webhookPayloadLock = new();
private bool _webhookPayloadWritten;

public void WriteWebhookPayload()
{
    var tempDirectory = HostContext.GetDirectory(WellKnownDirectory.Temp);
    var workflowDirectory = Path.Combine(tempDirectory, "_github_workflow");
    Directory.CreateDirectory(workflowDirectory);

    var gitHubContext = ExpressionValues["github"] as GitHubContext;
    var gitHubEvent = gitHubContext?.GetGitHubContext("event");

    if (gitHubEvent != null)
    {
        var workflowFile = Path.Combine(workflowDirectory, "event.json");

        lock (Root._webhookPayloadLock)
        {
            if (!Root._webhookPayloadWritten)
            {
                File.WriteAllText(
                    workflowFile,
                    gitHubEvent,
                    new UTF8Encoding(false));

                Root._webhookPayloadWritten = true;
            }
        }

        SetGitHubContext("event_path", workflowFile);
    }
}
```

The exact production patch may use a different synchronization primitive, but two properties matter:

- every child must synchronize against the same root-owned state;
- the written flag must only be committed after the file write succeeds.

Checking only `File.Exists(event.json)` would be weaker. A self-hosted runner could theoretically encounter stale state from an abnormal prior lifecycle, whereas a root-owned boolean explicitly represents "this job successfully wrote its payload".

Moving the write entirely to job initialization is another possible design, but it changes more lifecycle behavior. Root-level idempotence is the narrower patch because existing action and hook call sites can remain intact.

## Regression strategy

The existing `ActionRunnerL0.WriteEventPayload` test only verifies that one action calls `WriteWebhookPayload()` once. It does not test concurrent calls or the job-level ownership of the file.

A stronger regression belongs around the real `ExecutionContext` implementation:

1. initialize one root job execution context with a known `github.event` value;
2. create multiple child execution contexts from that root;
3. invoke `WriteWebhookPayload()` concurrently from those children;
4. await all calls;
5. assert no call throws;
6. assert the resulting `event.json` contains the complete expected JSON;
7. assert every child resolves the same `github.event_path`;
8. assert the physical write occurs once.

If direct write-count observation is awkward, extract the payload-file write behind a small seam or verify root state after the concurrent run. The test should not depend on reproducing Windows file-lock timing probabilistically.

Also retain coverage for:

- a job with no webhook payload;
- job hooks that call `WriteWebhookPayload()`;
- sequential actions;
- a failed first write followed by a successful retry;
- separate jobs receiving separate root-owned write state.

## General debugging lesson

Parallelizing an execution model often exposes operations that were accidentally safe only because callers used to be serialized.

When diagnosing a new concurrency failure, classify each resource by ownership scope:

```text
step-scoped       -> child execution context
job-scoped        -> root execution context
runner-scoped     -> runner process
machine-scoped    -> filesystem / OS resource
```

Then make synchronization live at the narrowest scope shared by every contender. In this case, the callers are step-scoped but the file is job-scoped, so job-root synchronization is the correct boundary.

## Verification checklist

- Parallel action-backed background steps no longer contend on `event.json`.
- `event.json` is written exactly once per job when a webhook payload exists.
- Every child action still receives a valid `github.event_path`.
- Job-hook execution retains access to the payload.
- A failed initial write does not permanently mark the payload as written.
- Sequential action behavior is unchanged.
- Separate jobs do not share the idempotence flag.
- Windows and Linux L0 suites remain green.
