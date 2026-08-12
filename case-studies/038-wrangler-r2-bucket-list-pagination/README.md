# Case Study 038: Wrangler R2 bucket listing silently truncates at the first API page

## Problem

`wrangler r2 bucket list` can present a partial account inventory as though it were complete. On an account with 45 buckets, the command returns the first 20 only. There is no warning, cursor, or visible indication that more buckets exist.

Upstream issue: `cloudflare/workers-sdk#15103`.

This is a quiet-wrong failure: a user can reasonably conclude that an omitted bucket does not exist even though the same credentials can access it.

## Reproduction boundary

The upstream report demonstrates the same account and credentials returning different counts from the API:

```text
GET /accounts/$ACCOUNT/r2/buckets?per_page=20   -> 20 buckets
GET /accounts/$ACCOUNT/r2/buckets?per_page=100  -> 45 buckets
```

Wrangler's current command path is:

```text
r2BucketListCommand
        |
        v
listR2Buckets()
        |
        v
fetchResult('/accounts/{id}/r2/buckets')
        |
        v
return results.buckets
```

No pagination query parameter is supplied and no pagination metadata is retained.

## Root cause

`packages/wrangler/src/r2/helpers/bucket.ts` currently performs exactly one request:

```ts
const results = await fetchResult<{
  buckets: R2BucketInfo[];
}>(complianceConfig, `/accounts/${accountId}/r2/buckets`, { headers });
return results.buckets;
```

`fetchResult()` intentionally unwraps the API envelope and returns only `json.result`. For this endpoint that means the helper loses the top-level `result_info` containing the continuation cursor.

Wrangler already has cursor-aware infrastructure in `fetchListResultBase()`: it repeatedly calls the API and follows `result_info.cursor`. However, that helper assumes `json.result` itself is an array. R2's list-buckets result is an object containing a `buckets` array, so it cannot be substituted directly without adapting the response shape.

Cloudflare's current R2 API contract supports cursor pagination for List Buckets and allows `per_page` values up to 1000. Raising `per_page` alone reduces the frequency of the bug but does not make the command correct for accounts above that limit; the command needs to follow the cursor until exhaustion.

## Small PR candidate

Keep the change local to `listR2Buckets()` and reuse Wrangler's existing lower-level fetch/error machinery:

```text
packages/wrangler/src/r2/helpers/bucket.ts

1. Build URLSearchParams with per_page=1000.
2. Call fetchInternal<FetchResult<{ buckets: R2BucketInfo[] }>>.
3. Append json.result.buckets to one result array.
4. If result_info contains a cursor, set cursor and repeat.
5. Otherwise return the accumulated buckets.
6. Route unsuccessful envelopes through the existing throwFetchError path.
7. Preserve cf-r2-jurisdiction on every request.
```

The important point is that the loop must use the API envelope rather than `fetchResult()`, because the continuation token is outside `result`.

A reusable generic helper for object-wrapped cursor responses could be introduced later, but it is not required for the first bug fix.

## Regression coverage

`packages/wrangler/src/__tests__/r2/bucket.test.ts` already has an MSW test for the list command. Extend that surface with a two-page response:

1. First request asserts `per_page=1000` and no cursor, returns buckets A/B plus `result_info.cursor = "next"`.
2. Second request asserts `cursor=next`, returns buckets C/D with no continuation cursor.
3. Assert all four buckets are printed exactly once.
4. Repeat with `--jurisdiction` and assert the jurisdiction header is present on every page.
5. Add a single-page case to prove the loop stops immediately when no cursor is returned.
6. Add an API-error-on-page-two case and assert the command fails rather than presenting a partial list as success.

The last test is important: pagination must not convert a partial inventory into a successful-looking result when a later page fails.

## Why this is high-signal

The command-layer renderer is behaving correctly; it prints every bucket it receives. The defect sits one layer lower, where an envelope-unwrapping helper discards pagination metadata before the R2 helper can act on it.

The broader debugging lesson is to audit response-envelope semantics when a CLI silently returns a plausible subset. A one-request helper can be perfectly correct for single-resource APIs while being structurally wrong for a cursor-paginated collection.

## Suggested upstream diagnostic comment

> I traced this to `listR2Buckets()` rather than the CLI renderer. The helper calls `fetchResult()` once with no query parameters and returns `results.buckets`; `fetchResult()` unwraps the Cloudflare API envelope, so the command never sees the top-level `result_info.cursor` needed to continue the listing.
>
> Wrangler already has cursor-pagination machinery in `fetchListResultBase()`, but that helper expects `json.result` itself to be an array. R2 List Buckets instead returns an object containing `buckets`, so the narrow fix is to loop at `listR2Buckets()` (or add an object-wrapped cursor helper), accumulate `result.buckets`, and follow `result_info.cursor` until it is absent. I would set `per_page=1000` to reduce round trips, but still follow the cursor because 1000 is only the per-request maximum.
>
> The existing `bucket.test.ts` list test is a good regression surface: make MSW return two pages, assert the second request carries the cursor, and verify all buckets are printed once. I would also make page two fail in one test so a partial inventory can never be reported as successful.