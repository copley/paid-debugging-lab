# Case Study 014 — Wrangler secret binding overwritten by deploy

## Status

Source-inspected diagnosis for [`cloudflare/wrangler-action#392`](https://github.com/cloudflare/wrangler-action/issues/392). No public comment or upstream PR was posted during this investigation.

## Reported symptom

A workflow supplies `BASE_URL` and `CRON_SECRET` through the action's `secrets` input. The action reports that both secrets were uploaded successfully, but after the workflow completes the Cloudflare dashboard shows plaintext bindings and the Worker receives empty values.

The reporter found that removing same-named entries from `wrangler.jsonc` makes the secret upload work.

## Failure boundary

The action performs two separate writes to the Worker configuration:

1. `uploadSecrets()` runs `wrangler secret bulk`.
2. `wranglerCommands()` subsequently runs `wrangler deploy` or `wrangler publish`.

Current order in `src/wranglerAction.ts`:

```ts
await uploadSecrets(resolvedConfig, packageManager);
await wranglerCommands(resolvedConfig, packageManager);
```

The secret command can succeed at the moment it runs. The later deployment then reapplies the bindings declared in `wrangler.jsonc`. If a name is present both in the action's `secrets` list and in the Wrangler `vars` configuration, the final deployment can replace the secret binding with the plaintext configuration binding.

This explains why the upload log is successful while the final dashboard state is wrong: the successful operation is overwritten later in the same action run.

## Root-cause hypothesis

The defect is command ordering combined with an undetected binding-name collision:

```text
secret bulk succeeds
        ↓
wrangler deploy reads wrangler.jsonc
        ↓
same key is emitted as a plaintext var
        ↓
final deployed binding is no longer the secret
```

The action validates that each requested secret has a non-empty environment value, but it does not validate whether the same binding name is declared as a plaintext variable in the Wrangler configuration.

## Small PR candidate

The smallest behavior-level patch is to make secret upload the final binding operation:

```diff
- await uploadSecrets(resolvedConfig, packageManager);
  await wranglerCommands(resolvedConfig, packageManager);
+ await uploadSecrets(resolvedConfig, packageManager);
```

That should prevent a normal deploy from overwriting a secret uploaded earlier in the run.

This change needs verification against custom `commands` inputs because not every command is necessarily a deployment command. A safer implementation may classify commands and upload secrets after the final deploy/publish/versions-deploy operation rather than unconditionally after every possible custom command sequence.

## Preferred defensive improvement

Add a preflight collision check where practical:

- obtain the effective Wrangler configuration for the selected environment;
- compare plaintext `vars` keys with the action's `secrets` input;
- fail with a precise message when a key appears in both sets.

Example diagnostic:

```text
Binding "CRON_SECRET" is configured both as a Wrangler plaintext var and as an action secret. The later deploy would overwrite the secret binding. Remove it from vars or rename one binding.
```

If consuming Wrangler's resolved configuration is not stable enough for this action, documenting the collision rule and changing the command order is still better than reporting a successful final state that does not persist.

## Regression tests

### Unit test: command order

Mock the action executor and assert that a default run with secrets calls:

```text
wrangler deploy
wrangler secret bulk
```

in that order.

### Integration test: duplicate binding name

Use a fixture with:

```jsonc
{
  "vars": {
    "CRON_SECRET": "plaintext-placeholder"
  }
}
```

and an action secret named `CRON_SECRET`. Verify either:

- the action rejects the collision before deployment; or
- the final remote binding type is secret after the workflow completes.

### Compatibility tests

Cover:

- default deploy command;
- explicit `deploy` command;
- environment-specific configuration;
- multiple secrets;
- custom command sequences with no deployment command;
- legacy Wrangler secret commands.

## Draft upstream diagnostic comment

> I traced this to two successful but conflicting writes in the action's current execution order.
>
> `main()` calls `uploadSecrets()` first and `wranglerCommands()` second. The first step runs `wrangler secret bulk`, so its success message is accurate at that point. The subsequent `wrangler deploy` then reapplies bindings from `wrangler.jsonc`. When the same name is also present under plaintext `vars`, that later deployment can replace the secret binding, which matches the report that removing the config entries fixes the final dashboard state.
>
> A focused first fix would be to make secret upload the final binding operation for deploy/publish workflows and add a test asserting command order. A stronger defensive fix would detect overlap between resolved Wrangler `vars` keys and the action's `secrets` list and fail with a clear collision error.
>
> I would test the default deploy path, explicit environment configuration, and custom command sequences before changing the order globally, because `commands` can contain operations other than deploy.

## Commercial debugging lesson

A successful log line proves only that one command succeeded. It does not prove that a later command preserved the state it wrote. For CI/CD diagnosis, inspect the full mutation sequence and identify which operation owns the final state.