# Case Study 040: Wrangler temporary-account auth is not idempotent

## Problem

`wrangler d1 migrations apply --remote --temporary` can fail with an "already authenticated" error even when the only credentials in use were created by Wrangler's own temporary-preview-account flow.

Upstream issue: `cloudflare/workers-sdk#15171`.

## Root cause

The D1 migrations command already opts into temporary authentication with `behaviour.supportTemporary: true`, and Wrangler's command dispatcher enables temporary auth before invoking the handler.

The failure is inside the shared `requireAuth()` temporary-account branch. On the first call, Wrangler sees no pre-existing credentials, activates a temporary account, latches it for the invocation, and returns its account ID. A later `requireAuth()` call in the same command enters the temporary branch again.

At that point `getAPIToken()` now returns the temporary token that the first call just activated. The generic "already authenticated" guard therefore mistakes credentials created by the current temporary flow for pre-existing environment/OAuth credentials and rejects the second call.

The upstream automated reproduction confirmed this exact multi-call sequence.

## Small PR candidate

Make `requireAuth()` idempotent for an already-active temporary account while preserving its existing safety checks:

```ts
if (oauthFlow.isTemporaryAllowed()) {
  if (getCloudflareComplianceRegion(config) !== "public") {
    // existing rejection
  }

  const activeTemporaryAccount = oauthFlow.getActiveTemporaryAccount();
  if (activeTemporaryAccount) {
    return activeTemporaryAccount.account.id;
  }

  if (getAPIToken()) {
    // existing rejection for credentials that pre-date this temporary session
  }

  const { account, cached } = await oauthFlow.activateTemporaryAccount();
  // existing logging
  return account.account.id;
}
```

The early return should come after the compliance-region check so repeated calls retain the same policy boundary, but before `getAPIToken()` so the active temporary credential is not reclassified as external authentication.

## Regression coverage

The core regression should exercise the shared auth layer directly:

1. Enable temporary authentication with no environment or stored OAuth credentials.
2. Call `requireAuth()` and capture the temporary account ID.
3. Call `requireAuth()` again in the same invocation.
4. Assert the same account ID is returned and no second activation occurs.
5. Keep tests proving that pre-existing API-token/OAuth credentials still reject `--temporary`.
6. Keep the non-public compliance-region rejection.
7. Add a Wrangler D1 regression proving the migrations path can make repeated auth calls without failing.

## Debugging lesson

Authentication helpers that create invocation-scoped credentials must distinguish **credential provenance** from mere credential presence. Once a helper activates temporary credentials itself, repeated calls should reuse that state rather than feed it back into a guard intended to detect credentials that existed before the temporary flow began.

## Suggested upstream diagnostic comment

I traced this to `requireAuth()` being non-idempotent on the temporary-account path. `d1 migrations apply` already declares `supportTemporary: true`, so command registration is enabling the feature correctly. The reproduced failure comes from multiple `requireAuth()` calls within one invocation: the first call activates and latches the temporary account, while the second call sees that same temporary token through `getAPIToken()` and the "already authenticated" guard treats it as pre-existing auth.

I would first return `oauthFlow.getActiveTemporaryAccount()` when a temporary account is already latched, then run the existing `getAPIToken()` rejection only before first activation. A focused regression should call `requireAuth()` twice and assert the same temporary account ID is returned without a second activation, while existing env/OAuth and compliance-region rejection tests stay unchanged.