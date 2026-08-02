# Case Study 028 — Wrangler Pages account cache overrides explicit account selection

## Incident

`wrangler pages project list` can target a stale account from the internal `pages.json` cache even when the operator explicitly sets `CLOUDFLARE_ACCOUNT_ID` to a different account.

This is most visible with OAuth authentication in a multi-account setup:

```text
pages.json account_id = account-A
CLOUDFLARE_ACCOUNT_ID = account-B
actual request         = /accounts/account-A/pages/projects
expected request       = /accounts/account-B/pages/projects
```

The command can then fail with Cloudflare authentication error `10000`, despite the active OAuth profile having access to the explicitly selected account.

Upstream issue: <https://github.com/cloudflare/workers-sdk/issues/14970>

## Impact

The defect is more serious than a confusing cache miss:

- an explicit account selection is silently ignored;
- read, create, or delete operations may be directed at an unintended account;
- OAuth users can receive a misleading authentication failure;
- clearing the cache appears to repair authentication without revealing the actual precedence bug.

## Source-level evidence

The Pages project commands read the internal cache and pass it directly to `requireAuth()`:

```ts
const config = getConfigCache<PagesConfigCache>(
  PAGES_CONFIG_CACHE_FILENAME
);
const accountId = await requireAuth(config);
```

This pattern appears in the list, create, and delete handlers in:

```text
packages/wrangler/src/pages/projects.ts
```

The shared account resolver documents configuration as higher priority than the environment variable. Because the cache object is passed in the configuration position, cached runtime state is accidentally promoted to user-authored configuration.

Wrangler already has the correct precedence in `pages deploy`:

```ts
const configCache = getConfigCache<PagesConfigCache>(
  PAGES_CONFIG_CACHE_FILENAME
);
const envAccountId = getCloudflareAccountIdFromEnv();
const accountId = await requireAuth({
  ...configCache,
  ...(envAccountId ? { account_id: envAccountId } : {}),
});
```

The environment value is deliberately overlaid after the cache.

## Why the existing test does not protect the OAuth path

`project-list.test.ts` contains a test named:

```text
should override cached accountId with CLOUDFLARE_ACCOUNT_ID environmental variable if provided
```

However, the test also installs a mocked API token. Environment-token authentication follows a different account-resolution path from stored OAuth authentication, which is the failing scenario in the issue.

The test also calls:

```ts
vi.mock("getConfigCache", ...)
```

That module identifier does not match the imported `../config-cache` module. The test therefore does not reliably seed the real Pages cache used by the command.

A meaningful regression must exercise stored OAuth credentials and the actual cache file or the real `config-cache` module seam.

## Root cause

The defect is a provenance error:

```text
runtime cache state
      ↓ passed as
user configuration
      ↓ receives higher precedence than
explicit CLOUDFLARE_ACCOUNT_ID
```

`requireAuth()` is behaving according to its documented priority. The caller is supplying the wrong semantic type of data.

## Focused fix

Add a small Pages authentication helper that treats cache state as fallback data:

```ts
function getPagesAuthConfig(): PagesConfigCache {
  const configCache = getConfigCache<PagesConfigCache>(
    PAGES_CONFIG_CACHE_FILENAME
  );
  const envAccountId = getCloudflareAccountIdFromEnv();
  return {
    ...configCache,
    ...(envAccountId ? { account_id: envAccountId } : {}),
  };
}
```

Then use it consistently in the Pages project list, create, and delete handlers before calling `requireAuth()`.

A narrower three-site inline change would work, but a helper reduces the risk that the commands drift apart again.

## Regression matrix

1. Cache contains account A; `CLOUDFLARE_ACCOUNT_ID` is account B; stored OAuth profile can access B.
   - Request must target account B.
2. Cache contains account A; no environment account is set.
   - Request may use account A as fallback.
3. Environment account is invalid.
   - Existing account-ID validation must fail before an API request.
4. List succeeds using account B.
   - Saved Pages cache should be refreshed to account B.
5. Create and delete commands receive the same cache/environment combination.
   - Both must target account B.
6. API-token authentication.
   - Existing behavior must remain unchanged.

## Verification

Suggested focused commands from the Workers SDK repository:

```bash
pnpm --filter wrangler test -- project-list.test.ts
pnpm --filter wrangler test -- project-create.test.ts
pnpm --filter wrangler test -- project-delete.test.ts
pnpm check
```

The exact package test command should be confirmed against the repository scripts before submission.

## Small PR scope

```text
packages/wrangler/src/pages/projects.ts
- import getCloudflareAccountIdFromEnv
- merge the explicit environment account over cached Pages state
- apply the same helper to list, create, and delete

packages/wrangler/src/__tests__/pages/project-list.test.ts
- replace the ineffective cache mock with actual cache state
- exercise the stored-OAuth path
- assert the requested account ID

related create/delete tests
- add parity coverage if the shared helper is not tested directly
```

## Diagnostic comment draft

The failure is caused by treating `pages.json` as configuration rather than fallback runtime state. `pages project list`, create, and delete pass the cache object directly to `requireAuth()`, whose documented priority puts `config.account_id` ahead of `CLOUDFLARE_ACCOUNT_ID`. That makes the cached account win by design.

`pages deploy` already shows the intended correction: read the cache, then overlay `getCloudflareAccountIdFromEnv()` before calling `requireAuth()`.

The existing project-list test does not cover the reported OAuth path because it installs a mocked API token, and its `vi.mock("getConfigCache", ...)` identifier does not match the actual `../config-cache` import. I would add a regression that seeds the real Pages cache with account A, uses stored OAuth credentials, sets the environment to account B, and asserts the request path contains account B. The same helper should be used by list, create, and delete so the three commands cannot diverge.
