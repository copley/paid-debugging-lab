# Case Study 005 — Refreshing GitHub OIDC tokens across AWS STS retries

## Problem

`aws-actions/configure-aws-credentials` can fetch a GitHub OIDC token successfully, then spend long enough retrying `AssumeRoleWithWebIdentity` that the token expires before a later retry reaches AWS STS.

The reported failure was triggered by repeated network timeouts. Each STS attempt took roughly six minutes and forty-five seconds. Once the original GitHub JWT expired, every remaining retry reused the same stale token and failed immediately with `Token expired`.

Upstream issue inspected: `aws-actions/configure-aws-credentials#1848`.

## Signal from the issue

The log separates the failure into two phases:

1. early attempts fail with `connect ETIMEDOUT`;
2. later attempts fail with `Token expired`;
3. retries continue, but every attempt uses the same expired JWT.

That pattern indicates that retry timing is not the only problem. The credential exchange is retryable, but the short-lived input credential is captured outside the retry boundary.

## Source inspection

In `src/index.ts`, the action currently obtains the GitHub OIDC token before the role-assumption retry loop:

```ts
webIdentityToken = await withRetry(async () => {
  return core.getIDToken(audience);
}, "getIDToken");
```

Later, `AssumeRole` is retried while the same `webIdentityToken` value is passed into every attempt:

```ts
roleCredentials = await withRetry(async () => {
  return assumeRole({
    // ...
    webIdentityToken,
  });
}, "AssumeRole");
```

`src/assumeRole.ts` then places that captured string directly into `AssumeRoleWithWebIdentityCommand`.

## Root cause

The retry boundary surrounds the AWS STS call but not the acquisition of the short-lived GitHub OIDC credential.

The effective flow is:

```text
get GitHub JWT once
        |
        v
retry STS with the same JWT
        |
        +-- timeout
        +-- timeout
        +-- JWT expires
        +-- retry stale JWT repeatedly
```

This is a stale-credential retry bug. A retryable operation depends on an expiring input, so that input must be refreshed inside the operation's retry boundary.

## Focused fix path

A small patch can keep the existing retry helper and move GitHub OIDC token acquisition into the `AssumeRole` attempt closure:

```ts
const useOidc = useGitHubOIDCProvider();

roleCredentials = await withRetry(async () => {
  let attemptToken = webIdentityToken;

  if (useOidc) {
    try {
      attemptToken = await core.getIDToken(audience);
    } catch (error) {
      throw new Error(`getIDToken call failed: ${errorMessage(error)}`);
    }
  }

  return assumeRole({
    credentialsClient,
    sourceAccountId,
    roleToAssume,
    roleExternalId,
    roleDuration,
    roleSessionName,
    roleSkipSessionTagging,
    transitiveTagKeys,
    webIdentityTokenFile,
    webIdentityToken: attemptToken,
    inlineSessionPolicy,
    managedSessionPolicies,
    customTags,
  });
}, "AssumeRole");
```

The earlier one-time `core.getIDToken()` block would then be removed for the GitHub OIDC path. File-based web identity tokens and IAM credentials should retain their current behavior.

This approach deliberately obtains a token immediately before each STS attempt. It avoids decoding JWT expiry locally and remains correct even when a previous attempt spent several minutes blocked in the network stack.

## Tests to add

The existing Vitest suite already mocks `core.getIDToken`, the STS client, and retry sleeps. A focused regression test should:

1. configure GitHub OIDC authentication;
2. return `token-1` and then `token-2` from `core.getIDToken`;
3. make the first `AssumeRoleWithWebIdentityCommand` fail with a transient timeout;
4. make the second command succeed;
5. assert that `core.getIDToken` was called twice;
6. assert that the first STS request used `token-1` and the second used `token-2`;
7. assert that credentials were exported successfully.

A second guard test should confirm that the web-identity-token-file path does not call `core.getIDToken`.

## Draft public diagnostic comment

```markdown
I inspected the retry boundary in `src/index.ts`, and the log is consistent with a stale short-lived credential being captured outside the retried operation.

The GitHub JWT is obtained once through `core.getIDToken(audience)`. The later `withRetry(..., "AssumeRole")` closure reuses that same `webIdentityToken` for every `AssumeRoleWithWebIdentity` attempt. In the reported run, the early STS attempts spend about 6m45s timing out; after the JWT expires, all remaining retries keep submitting the same expired token.

A focused fix would be to acquire the GitHub OIDC token inside each `AssumeRole` retry attempt, immediately before calling `assumeRole()`. The file-based web identity and IAM credential paths can remain unchanged.

The regression test can mock two token values, fail the first STS command with `ETIMEDOUT`, succeed the second, and assert that the two command inputs contain different `WebIdentityToken` values. That verifies the fix without waiting for a real token to expire.
```

## Why this is a strong portfolio case

This issue demonstrates several commercially useful debugging skills:

- reading timing information from CI logs;
- distinguishing the original transient failure from the later terminal failure;
- locating the retry boundary in TypeScript source;
- recognizing stale short-lived credentials as retry state;
- designing a deterministic test for an expiry bug without sleeping;
- keeping the proposed patch scoped to one authentication path.
