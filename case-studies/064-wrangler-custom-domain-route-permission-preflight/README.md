# Case Study 064 — Wrangler custom-domain redeploy triggers an unrelated Zone Routes permission check

**Status:** Source-inspected investigation. No upstream comment or pull request has been posted from this work.

**Upstream issue:** [cloudflare/workers-sdk#15863](https://github.com/cloudflare/workers-sdk/issues/15863)

## Failure contract

An unchanged Wrangler deployment can fail after the Worker has already uploaded when the configuration contains only a custom domain, has `workers_dev: false`, and the API token does not have `Zone > Workers Routes Read`.

The reported sequence is state-dependent: the deployment that first disables `workers.dev` succeeds, while later identical deployments can call `/zones/:zone/workers/routes` and fail with a permission error. That permission belongs to ordinary Worker Routes, not to the custom-domain publication path.

Cloudflare's automated triage classified the report as actionable, but its automated reproduction attempt timed out before recording a result. This case study therefore records a source-inspected diagnosis rather than claiming an independently reproduced upstream failure.

## Source evidence

`triggersDeploy()` already separates configured routes into two resource classes:

```ts
const routesOnly: Array<Route> = []
const customDomainsOnly: Array<RouteObject> = []

for (const route of routes) {
  if (typeof route !== "string" && route.custom_domain) {
    customDomainsOnly.push(route)
  } else {
    routesOnly.push(route)
  }
}
```

The later publication paths respect that classification:

- `publishRoutes(..., routesOnly, ...)` handles ordinary Worker Routes.
- `publishCustomDomains(..., customDomainsOnly)` handles custom domains.

The conflicting-route preflight does not preserve the same boundary. Its guard checks the original combined `routes` collection:

```ts
if (!wantWorkersDev && workersDevInSync && routes.length !== 0) {
```

and the preflight then iterates:

```ts
for (const route of routes) {
```

Inside that loop Wrangler can resolve the zone and request:

```text
/zones/:zone/workers/routes
```

A `custom_domain: true` entry can therefore reach the ordinary Zone Workers Routes lookup even though it will later be published only through `publishCustomDomains()`.

The `workersDevInSync` condition explains the reported first-deploy/second-deploy distinction: a deployment that changes the workers.dev state skips this preflight, while a subsequent deployment with the state already synchronized enters it.

## Root cause

The route-classification boundary is established correctly but is not propagated into the conflict-preflight path.

The missing invariant is:

> Only resources that will be published as Worker Routes should participate in the Worker Routes conflict preflight or require permissions for the Zone Workers Routes API.

This is an authorization-scope bug caused by operating on the pre-classification collection after the code has already derived the correct resource-specific collections.

## Bounded fix path

Keep the existing `workersDevInSync` policy unchanged and narrow only the resource boundary:

1. Change the preflight guard from `routes.length !== 0` to `routesOnly.length !== 0`.
2. Change `for (const route of routes)` to `for (const route of routesOnly)`.

This retains the existing conflict check for genuine Worker Routes while preventing custom-domain-only deployments from acquiring an unrelated Zone Workers Routes read-permission requirement.

I would not combine this patch with a broad authorization fallback such as downgrading all failures from `/workers/routes` to warnings. That would weaken a useful conflict check for real Worker Routes and make the change harder to reason about.

## Regression matrix

1. Custom-domain-only configuration, `workers_dev: false`, workers.dev already in sync -> no request to `/zones/:zone/workers/routes`.
2. Ordinary Worker Route with workers.dev already in sync -> the existing conflict lookup still runs.
3. Mixed ordinary route plus custom domain -> the preflight checks only the ordinary route.
4. Initial deployment that changes workers.dev state -> existing state-transition behavior remains unchanged.
5. Conflicting ordinary Worker Route -> existing error behavior remains unchanged.

The strongest regression assertion is not merely that deployment succeeds. The test should explicitly verify that the Zone Workers Routes endpoint is never requested for the custom-domain-only case.

## Verification plan

- Add focused deploy-helper coverage around the conflicting-route preflight.
- Use the existing API/mock infrastructure to fail the test if `/zones/:zone/workers/routes` is called in the custom-domain-only scenario.
- Run the deploy-helper test suite and repository-required type/lint checks.
- Retain a control with a real Worker Route to prove the permission-requiring path was not accidentally removed.
- If credentials are available, repeat the issue's second-deploy scenario with a least-privilege token that can manage the Worker/custom domain but lacks Zone Workers Routes Read.

## Commercial debugging analogue

This is a common integration failure class: a system correctly classifies two resource types for the final write operation, then accidentally recombines them for validation or discovery and expands the permissions required by the overall workflow.

The reusable lesson is to enforce authorization and validation at the same resource boundary as the operation they protect. A preflight for resource A should not make users grant permissions for A when they are only operating on resource B.
