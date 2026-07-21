# Case Study 016: Wrangler failed commands lose captured outputs

## Upstream issue

- Repository: `cloudflare/wrangler-action`
- Issue: `#382` — failed Wrangler commands leave `command-output` and `command-stderr` empty

## Symptom

A workflow runs a Wrangler command with `continue-on-error: true` and then inspects the action outputs. Successful commands expose `command-output`, but a non-zero Wrangler command leaves both outputs unavailable even though the error text appeared in the action log.

## Reproduction boundary

`wranglerCommands()` attaches stdout and stderr listeners before executing the command. Those listeners correctly accumulate process output into `stdOut` and `stdErr`.

The output publication happens only after the awaited command succeeds:

```ts
await exec(`${packageManager.exec} wrangler ${command}`, args, options);
setOutput("command-output", stdOut);
setOutput("command-stderr", stdErr);
```

`@actions/exec.exec()` rejects on a non-zero exit code. Control therefore leaves the loop before either `setOutput()` call runs. The action-level catch marks the step failed, but the already captured buffers are discarded.

## Root cause

Output capture and command success are incorrectly coupled.

The action has enough stderr/stdout data to expose useful diagnostics, but it publishes that data only on the success path.

## Small PR direction

Publish the captured buffers regardless of exit status, then preserve the original command failure.

One focused structure is:

```ts
let commandError: unknown;

try {
  await exec(`${packageManager.exec} wrangler ${command}`, args, options);
} catch (error) {
  commandError = error;
} finally {
  setOutput("command-output", stdOut);
  setOutput("command-stderr", stdErr);
}

if (commandError)
  throw commandError;

await handleCommandOutputParsing(config, command, stdOut);
```

This keeps normal failure semantics intact while allowing downstream steps using `continue-on-error` to inspect the command output.

A helper that executes one Wrangler command and returns `{ stdout, stderr, exitError }` would make the behavior easier to test and avoid expanding the loop body.

## Important edge cases

- Do not call deployment/output parsers after a failed command unless they explicitly support partial output.
- Preserve the original exit failure rather than replacing it with a generic error.
- Publish outputs before rethrowing so GitHub records them for downstream steps.
- For multiple commands, document whether outputs represent the last command or accumulated command output. The current implementation overwrites outputs after each successful command, so retaining last-command semantics is the least disruptive choice.

## Regression tests

Add tests around `wranglerCommands()`:

1. Successful command publishes both stdout and stderr.
2. Failed command publishes both buffers and still rejects.
3. Failed command does not invoke `handleCommandOutputParsing()`.
4. Empty stderr is published as an empty string rather than omitting the output.
5. Multiple commands retain the existing last-command output behavior.

The test can mock `exec()` so it calls the provided listeners and then throws a non-zero-exit error.

## Draft diagnostic comment

I inspected the command execution path and the missing outputs are caused by control flow rather than by Wrangler writing to an unexpected stream.

`wranglerCommands()` registers stdout/stderr listeners, so the action captures the text that appears in the log. However, it calls `setOutput("command-output", ...)` and `setOutput("command-stderr", ...)` only after `await exec(...)` returns successfully. `@actions/exec.exec()` rejects on a non-zero exit code, so a failed Wrangler command exits the loop before either output is published.

A focused fix would publish both captured buffers in a `finally` block, then rethrow the original command error. Output parsing should remain success-only unless it is designed for partial failure output.

A regression test can mock `exec()` to emit stdout/stderr through the listeners and then reject, asserting that both outputs are set and the command still fails.

## Engineering lesson

Diagnostic artifacts must be committed before propagating a failure. If observability is placed only after a successful await, the exact failures that need evidence most will lose it.
