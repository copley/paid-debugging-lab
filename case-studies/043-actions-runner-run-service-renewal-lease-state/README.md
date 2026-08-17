# Case Study 043 — GitHub Actions Run Service renewal lease-state bug

## Problem

A GitHub Actions runner using the Run Service path can successfully renew an active job lease, encounter a later transient renewal failure, and then abandon the job with a `NullReferenceException` even though the most recently acquired lease is still valid.

Upstream issue: `actions/runner#4635`.

The operational symptom is severe because a network or TLS fault that should enter the existing retry window can instead cancel a healthy worker and report the job as `Abandoned`.

## Root cause

The Run Service overload of `RenewJobRequestAsync()` declares:

```csharp
TaskAgentJobRequest request = null;
```

but Run Service renewal state is returned through a different object:

```csharp
var renewResponse = await runServer.RenewJobAsync(planId, jobId, token);
```

The successful path logs `renewResponse.LockedUntil` and marks the first renewal complete. If a later generic exception occurs, however, the retry branch calculates its remaining lease window from:

```csharp
request.LockedUntil.Value
```

`request` is never assigned in this overload, so the post-success retry path dereferences `null`.

This is a state-ownership bug between two parallel renewal implementations: the legacy runner-server path uses a `TaskAgentJobRequest`, while the Run Service path receives a `RenewJobResponse`, but part of the legacy retry logic was carried into the Run Service overload without changing the source of `LockedUntil`.

## Why the failure appears only after a successful renewal

Before the first successful renewal, the method uses the bounded "first renew" retry path and does not consult `request.LockedUntil`.

After `firstJobRequestRenewed` is completed, a later exception takes the lease-window branch. That is the first point at which the unassigned `request` is dereferenced.

The resulting sequence is therefore:

```text
successful Run Service renewal
        ↓
latest LockedUntil exists in renewResponse
        ↓
transient HTTP/TLS/network failure
        ↓
post-success retry branch
        ↓
request.LockedUntil dereference
        ↓
NullReferenceException
        ↓
renewal task faults; active worker is cancelled/abandoned
```

## Likely fix path

The Run Service overload should retain the lease deadline returned by the most recent successful `RenewJobResponse` and use that value consistently in the retry branch.

Conceptually:

```csharp
DateTime? lockedUntil = null;

while (!token.IsCancellationRequested)
{
    try
    {
        var renewResponse = await runServer.RenewJobAsync(planId, jobId, token);
        lockedUntil = renewResponse.LockedUntil;
        // existing success handling
    }
    catch (Exception ex)
    {
        // existing first-renew handling

        if (firstJobRequestRenewed.Task.IsCompleted && lockedUntil.HasValue)
        {
            var remainingTime = lockedUntil.Value
                + TimeSpan.FromMinutes(5)
                - DateTime.UtcNow;

            // existing backoff/retry logic
        }
    }
}
```

The exact patch should also replace the retry log's `request.LockedUntil.Value` reference with the retained Run Service lease value.

Using a dedicated local `DateTime?` is preferable to manufacturing a `TaskAgentJobRequest` merely to satisfy copied retry code: the Run Service response is the authoritative source of this state.

## Regression strategy

The current Run Service L0 coverage exercises successful renewals followed by terminal `TaskOrchestrationJobNotFoundException`, but it does not exercise a generic exception after a successful renewal.

Add a focused Run Service test with this sequence:

```text
renew #1 -> success, future LockedUntil
renew #2 -> transient generic exception
retry    -> success (or controlled cancellation after proving retry occurred)
```

The test should verify:

- the first renewal completes successfully;
- the transient exception does not produce `NullReferenceException`;
- the method schedules another retry while the retained lease window is positive;
- a later successful renewal updates the retained deadline;
- terminal job-not-found behavior is unchanged;
- cancellation still exits cleanly.

A useful second edge case is a failure before any successful renewal, confirming the existing five-attempt first-renew behavior remains unchanged.

## General debugging lesson

When two implementations share retry logic but obtain state from different response types, copied state references are a high-risk boundary. A successful-path variable can be correct while an exception path still points at an object that does not exist in that implementation.

For long-running control loops, inspect three states separately:

1. state received from the latest successful operation;
2. state required to decide whether retry is still safe;
3. state referenced by logging/error branches.

All three must refer to the same authoritative lifecycle state.

## Verification checklist

- Run Service success followed by a transient renewal failure does not fault with `NullReferenceException`.
- Retry continues within the last confirmed `LockedUntil + 5 minutes` window.
- A recovered renewal updates the deadline used by subsequent retries.
- First-renew bounded retries remain unchanged.
- `TaskOrchestrationJobNotFoundException` remains terminal.
- Explicit cancellation remains terminal and clean.
- Legacy `IRunnerServer` renewal behavior is unaffected.
