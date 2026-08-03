# Case Study 029 — C3 Generates a Compatibility Date Newer Than Its Runtime

## Upstream issue

- `cloudflare/workers-sdk#14942`
- https://github.com/cloudflare/workers-sdk/issues/14942

## Symptom

A newly scaffolded Cloudflare project can fail on its first local `dev` run:

```text
This Worker requires compatibility date "2026-07-30", but the newest
date supported by this server binary is "2026-07-29".
```

The project was generated on July 30, 2026. C3 wrote that day's date into `wrangler.jsonc`, but the bundled `workerd` binary supported only July 29, 2026.

## Source-level diagnosis

C3's configuration writer treats `getWorkerdCompatibilityDate()` as the runtime-safe fallback used for both `<COMPATIBILITY_DATE>` substitution and missing/invalid `compatibility_date` values:

```text
packages/create-cloudflare/src/wrangler/config.ts
```

However, the helper does not inspect the installed runtime. It ignores its `_projectPath` argument and returns the current calendar date:

```ts
export function getWorkerdCompatibilityDate(_projectPath: string) {
  const date = getTodaysCompatDate();
  return date;
}
```

The corresponding test currently codifies that behavior as `returns today's date`:

```text
packages/create-cloudflare/src/helpers/__tests__/compatDate.test.ts
```

The semantic mismatch is the bug:

```text
helper name and caller assumption: newest date supported by workerd
actual implementation:           today's UTC calendar date
```

This becomes visible whenever the calendar advances before the pinned runtime package is published with support for the new date.

## Why the failure is intermittent

The defect is release-skew dependent rather than project dependent:

1. The date rolls over.
2. C3 immediately begins writing the new date.
3. Its installed Miniflare/workerd dependency may still contain the previous runtime build.
4. Fresh projects fail until the package graph catches up.

A normal unit test run usually misses this because it checks only that the output looks like a date, not that the generated project can boot with the dependency versions C3 installed.

## Candidate fix

Make `getWorkerdCompatibilityDate(projectPath)` live up to its name:

1. Resolve the workerd runtime package installed for the generated project.
2. Read the runtime's declared maximum compatibility date from supported package metadata or a shared runtime helper.
3. Generate a date no later than both:
   - today's date; and
   - the runtime's maximum supported date.
4. Preserve an existing valid user-supplied compatibility date rather than silently rewriting it.
5. Use a deterministic fallback when runtime metadata cannot be resolved; do not silently claim that today's date is runtime-supported.

A shared helper owned near Miniflare/workerd would be preferable to duplicating knowledge of runtime package layout inside C3.

## Regression matrix

```text
Today       Runtime max  Existing config        Expected generated value
2026-07-30  2026-07-29   absent                 2026-07-29
2026-07-29  2026-07-29   absent                 2026-07-29
2026-07-28  2026-07-29   absent                 2026-07-28
2026-07-30  2026-07-29   valid explicit date    preserve explicit date
2026-07-30  unavailable  absent                 deterministic safe failure/fallback
```

## Tests to add

- Replace the `returns today's date` assertion with runtime-cap-aware cases.
- Stub today's date and the installed runtime's maximum date independently.
- Cover JSON, JSONC, and TOML configuration generation.
- Preserve a valid explicit compatibility date.
- Add a scaffold-and-boot smoke test using the exact dependency graph produced by C3.
- Include a date-rollover fixture where today is one day newer than workerd's maximum.

## Small PR scope

```text
packages/create-cloudflare/src/helpers/compatDate.ts
packages/create-cloudflare/src/helpers/__tests__/compatDate.test.ts
packages/create-cloudflare/src/wrangler/__tests__/config.test.ts
```

A package or shared-helper change may also be required depending on where workerd exposes its maximum compatibility date.

## Diagnostic comment draft

> I traced this to a semantic mismatch in C3's compatibility-date helper. `updateWranglerConfig()` uses `getWorkerdCompatibilityDate(projectPath)` as the runtime-safe fallback, but the helper ignores `projectPath` and simply returns `getTodaysCompatDate()`. Its unit test explicitly asserts “returns today's date.”
>
> That means C3 is not reading the maximum date supported by the workerd binary it installs. At a UTC date rollover, the generated config can advance immediately while the pinned runtime remains one day behind.
>
> I would change the helper to resolve the installed runtime's declared maximum compatibility date and return `min(today, runtimeMax)`, while preserving an existing valid user-specified date. The regression should freeze today at `2026-07-30`, runtime max at `2026-07-29`, scaffold a project, and verify that the generated config uses `2026-07-29` and boots successfully.

## Verification status

The issue and relevant source paths were inspected on August 3, 2026. No upstream comment or patch was posted, and the proposed change has not yet been executed against the upstream test suite.
