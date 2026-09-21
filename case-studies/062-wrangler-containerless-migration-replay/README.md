# Case Study 062 — Wrangler replays irrelevant Durable Object migration history during deploy

## Contribution status

- Upstream issue: https://github.com/cloudflare/workers-sdk/issues/15741
- Checked issue comments: 2026-09-21
- Checked open PRs: 2026-09-21
- Assignment status: unassigned at review
- Intent comment: none from this investigation
- Reproduction: independently reproduced by Cloudflare's automated triage; not independently executed in this investigation
- Upstream PR: none referencing #15741 at time of review
- Portfolio classification: source-inspected investigation, not an upstream contribution

## User-visible failure

Wrangler 4.131.0 introduced a deploy-time regression for Workers that have Durable Object migration history but no Durable Object-managed Containers.

A migration file can legitimately contain a historical deletion for a class that existed remotely even when an earlier creation for that class is no longer represented in the local file. The Workers API only needs migrations after the currently applied tag, but current Wrangler deploy preparation reconstructs the entire local history from an empty state and can fail before upload with:

```text
Cannot apply deleted_classes migration to non-existent class LegacyRoom
```

The same configuration deploys with Wrangler 4.128.0. Cloudflare's automated triage reproduced the failure on 4.135.0 and the successful 4.128.0 control.

## Evidence

Both `deployWorker` and `versions upload` compute the Durable Object-managed container list and then call `prepareDurableObjectContainerApplications(...)` even when that list is empty.

`prepareDurableObjectContainerApplications()` immediately calls `validateDurableObjectContainerApplications(config, durableObjectContainerConfig)` before doing any work that depends on an actual container application.

The validator calls `getDurableObjectClassNameToUseSQLiteMap(config.migrations, config.exports)` before iterating the supplied container list. That helper replays every migration into an initially empty map and throws when a `deleted_classes` entry cannot delete a locally reconstructed class.

The failure is therefore not caused by an actual Durable Object-managed Container configuration. Container preparation is invoking a strict historical-replay validator even when there is no container application to prepare.

## Root cause

The new container-deployment preparation path violates a relevance boundary:

```text
no DO-managed containers
        |
        v
prepareDurableObjectContainerApplications
        |
        v
validate full migration history
        |
        v
reconstruct from empty local state
        |
        v
historical delete appears impossible -> throw
```

Validation that exists solely to establish storage/ownership properties for Durable Object-managed Containers is being run even when the set of such containers is empty.

This turns an implementation detail of the newly added container workflow into a deploy blocker for unrelated Workers.

## Bounded fix path

Short-circuit `prepareDurableObjectContainerApplications()` when `durableObjectContainerConfig.length === 0` and return an empty prepared-image map before invoking container-specific validation.

Conceptually:

```ts
if (durableObjectContainerConfig.length === 0) {
  return {}
}

validateDurableObjectContainerApplications(
  config,
  durableObjectContainerConfig,
)
```

This is preferable to weakening `getDurableObjectClassNameToUseSQLiteMap()` globally. That helper is used in contexts where strict reconstruction may still be intentional. It is also preferable to duplicating guards independently in `deployWorker` and `versions upload`; the preparation helper owns the invariant that there is nothing to prepare for an empty container set.

The patch should preserve all existing validation when one or more Durable Object-managed Containers are configured.

## Regression matrix

Add focused coverage around `prepareDurableObjectContainerApplications()`:

1. empty container list + migration deleting an unknown historical class returns `{}` and does not throw;
2. empty container list + renamed historical class likewise does not trigger container validation;
3. non-empty container list still executes the current migration/storage validation;
4. valid non-empty container configuration still produces the existing prepared-image result;
5. command-level `wrangler deploy --dry-run` control reproduces the issue fixture without failure;
6. `wrangler versions upload --dry-run` uses the same no-container invariant.

## Verification plan

Run the focused deploy-helper tests, then the Wrangler deploy and versions-upload test suites that cover Durable Object-managed Containers. Use the issue's two-file fixture as an external control and verify the current release-regression case no longer fails while an actually invalid managed-container configuration still does.

## Engineering lesson

Validation should be scoped to the feature whose invariant it protects. A newly inserted validation phase can become a compatibility regression when it evaluates historical state that is irrelevant to the current operation. Before replaying or normalizing a large configuration history, first establish that the operation has a consumer for that derived state.
