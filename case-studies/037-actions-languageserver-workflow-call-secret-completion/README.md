# Case Study 037: GitHub Actions language server drops remote secret completion for `workflow_call`-only workflows

## Problem

A reusable workflow whose only trigger is `workflow_call` gets incomplete `secrets.*` completion in the GitHub Actions language server. Explicitly declared `workflow_call.secrets` and `GITHUB_TOKEN` appear, but repository and organization secret names are not fetched. Adding an unrelated second trigger such as `push` makes the remote names appear.

Upstream issue: `actions/languageservices#386`.

## Reproduction boundary

```yaml
name: probe
on:
  workflow_call:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - run: echo
        env:
          A: ${{ secrets. }}
          B: ${{ vars. }}
```

Observed behavior:

- `secrets.` offers `GITHUB_TOKEN` and explicitly declared reusable-workflow secrets only.
- `vars.` still fetches repository/organization variables.
- adding `push:` alongside `workflow_call:` makes remote secret names appear.

## Root cause

There are two secret-context layers.

`languageservice/src/context-providers/secrets.ts` builds the local context. For `workflow_call` it correctly marks the dictionary incomplete and, in completion mode, adds secrets explicitly declared in the reusable workflow contract.

The language-server layer then enriches that context from GitHub:

```ts
const eventsConfig = workflowContext?.template?.events;

if (eventsConfig?.workflow_call) {
  secretsContext.complete = false;

  if (mode === Mode.Validation || Object.keys(eventsConfig).length == 1) {
    return secretsContext;
  }
}

const secrets = await getRemoteSecrets(...);
```

The `Object.keys(eventsConfig).length == 1` condition is the defect. It makes the *number of workflow triggers* decide whether completion may query remote secret names.

That produces an internally inconsistent matrix:

```text
workflow_call only       -> no remote secret completion
workflow_call + push     -> remote secret completion
push only                -> remote secret completion
vars in workflow_call    -> remote variable completion
```

The context already has `complete = false`, which is the correct signal that additional secrets may arrive from a caller and cannot be exhaustively known. Returning early is therefore unnecessary for completion and removes useful names without making validation more correct.

## Small PR candidate

The narrow fix is to preserve the validation-mode guard but remove the trigger-count guard:

```ts
if (eventsConfig?.workflow_call) {
  secretsContext.complete = false;
  if (mode === Mode.Validation) {
    return secretsContext;
  }
}
```

This does not make the dictionary claim completeness. It only allows the existing remote-enrichment path to run in completion mode, exactly as it already does for a reusable workflow that happens to have a second trigger.

## Regression coverage

Add language-server tests covering:

1. `workflow_call` as the only trigger in completion mode.
2. Remote repository and organization secret names are included.
3. Explicit `workflow_call.secrets` are still included.
4. `GITHUB_TOKEN` remains present.
5. `DescriptionDictionary.complete` remains `false`.
6. Validation mode still avoids treating the remote list as exhaustive.
7. A mixed `workflow_call` + `push` workflow keeps existing behavior.
8. No-repository-permission mode still returns an incomplete context without attempting remote lookup.

## Why this is high-signal

The bug is not an API failure and not a parser failure. The same remote-data machinery works in the same document for `vars`, and it also works for `secrets` as soon as another trigger is added. That isolates the failure to a policy branch between local context construction and remote enrichment.

The broader debugging lesson is to treat completion and validation as different contracts: completion may safely provide a useful partial superset while marking it incomplete; validation must not infer that the same list is exhaustive.

## Suggested upstream diagnostic comment

> I traced this to the language-server enrichment guard rather than the reusable-workflow secret parser. The local provider already adds explicitly declared `workflow_call.secrets` and marks the dictionary `complete = false`. The server provider then returns early when `workflow_call` is the only trigger, before `getRemoteSecrets()` runs.
>
> That makes trigger count affect completion even though the context is already explicitly non-exhaustive. It also explains why adding `push` immediately restores remote names and why `vars` is unaffected.
>
> The narrow change is to keep the early return for `Mode.Validation`, but allow completion mode to continue through the existing remote-secret lookup even when `workflow_call` is the only trigger. A regression should assert that repo/org names and explicit reusable-workflow secrets are all offered while `complete` remains false.