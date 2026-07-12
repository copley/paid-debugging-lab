# Case Study 007 — Docker build appears to restart during the GitHub Actions post step

## Problem

A workflow uses `concurrency.cancel-in-progress: true` with `docker/build-push-action@v6`. When a newer push cancels the previous run, the cancelled run enters the action's post step and appears to start building the image again. The post step can continue using the remote BuildKit endpoint and delay the replacement workflow.

Upstream issue inspected: `docker/build-push-action#1586`.

## Signal from the issue

The workflow uses:

- a remote Buildx driver;
- `cache-from: type=gha`;
- `cache-to: type=gha,mode=max`;
- a pushed image;
- workflow-level cancellation through a shared concurrency group.

The visible activity occurs under the `Post Build & push` step, which makes it look as though the Dockerfile build has restarted.

## Source inspection

The action metadata executes the same bundle for both phases:

```yaml
runs:
  using: node24
  main: dist/index.cjs
  post: dist/index.cjs
```

The TypeScript source separates those phases. The main phase invokes the actual build command through Buildx:

```ts
const buildCmd = await toolkit.buildx.getCommand(args);
await Exec.getExecOutput(buildCmd.command, buildCmd.args, {
  ignoreReturnCode: true,
  // ...
});
```

The post phase does not invoke that build command again. When build summaries are enabled, it performs a Buildx history export:

```ts
if (stateHelper.isSummarySupported) {
  await core.group(`Generating build summary`, async () => {
    const buildxHistory = new BuildxHistory();
    const exportRes = await buildxHistory.export({
      refs: stateHelper.buildRef ? [stateHelper.buildRef] : []
    });

    // optionally upload the .dockerbuild record
    await GitHubSummary.writeBuildSummary(...);
  });
}
```

For a remote BuildKit driver, `buildx history export` still communicates with the remote builder and exports the completed build record. That operation can emit BuildKit-style progress and transfer a large record, so it can resemble a second build even though the image build/push command is not being re-run.

## Root cause

The expensive post-cancellation work is build-record export and summary generation, not a second Dockerfile build.

The action enables build summaries by default. Once the main phase has recorded a supported build reference, the post phase exports the `.dockerbuild` history record and optionally uploads it as a workflow artifact. GitHub Actions runs post steps during cancellation cleanup, so the cancelled run can continue occupying the remote builder or runner while the replacement run is waiting on the same concurrency group or infrastructure.

The effective path is:

```text
new push cancels older workflow
        |
        v
GitHub runs registered post action
        |
        v
buildx history export <completed-build-ref>
        |
        +-- remote BuildKit communication
        +-- build-record transfer
        +-- optional artifact upload
        |
        v
replacement workflow waits for cleanup/infrastructure
```

## Immediate mitigation

Disable the build summary for workflows where rapid cancellation is more important than the downloadable `.dockerbuild` record:

```yaml
- name: Build and push
  uses: docker/build-push-action@v6
  env:
    DOCKER_BUILD_SUMMARY: false
  with:
    context: .
    push: true
    tags: ghcr.io/${{ github.repository }}:${{ github.sha }}
    cache-from: type=gha
    cache-to: type=gha,mode=max
```

This is the relevant switch because summary support gates the history export itself.

Setting only `DOCKER_BUILD_RECORD_UPLOAD=false` is not equivalent. That disables artifact upload, but the post phase still calls `buildx history export` so it can generate the job summary.

## Focused upstream fix path

A safe fix should preserve summaries for normal successful and failed builds while avoiding unbounded cleanup after cancellation.

Possible implementation direction:

1. add an explicit action input or environment control for post-step history export, separate from artifact upload;
2. make the history export bounded by a short timeout and downgrade cancellation-related export failures to a warning;
3. record enough state during the main phase to distinguish a normal completed build from a run terminated while cancellation was in progress;
4. retain temporary-directory cleanup even when history export is skipped;
5. document that remote-driver history export may continue contacting BuildKit during the post phase.

Using `post-if: success()` for the entire action would be too broad: it would suppress useful summaries for failed builds and would also skip unrelated cleanup. The cancellation decision should be applied specifically to history export rather than to the complete post handler.

## Tests to add

A focused test should exercise the post handler with mocked Buildx history export:

1. normal completed run: history export is called and summary is written;
2. summary disabled: history export is not called;
3. artifact upload disabled: history export still runs but upload does not;
4. cancellation/aborted-main state: history export is skipped or bounded while temporary-directory cleanup still runs;
5. export timeout or remote-builder error: the post step warns and exits without failing or hanging cleanup.

## Draft public diagnostic comment

```markdown
I inspected the action's post path, and the activity shown there is most likely not a second invocation of the Dockerfile build.

`action.yml` registers `dist/index.cjs` as both `main` and `post`, but the TypeScript post handler does not call the original `buildx build` command. When build summaries are enabled it calls `BuildxHistory.export()` for the saved build reference, then optionally uploads the generated `.dockerbuild` record and writes the job summary.

With a remote driver, that history export still talks to the remote BuildKit daemon and can emit BuildKit-style progress or transfer a large record. That explains why the post step looks like a build and why it can delay the replacement run after `cancel-in-progress`.

A useful confirmation is to rerun once with `DOCKER_BUILD_SUMMARY=false` on the build-push step. That disables the history-export branch entirely. `DOCKER_BUILD_RECORD_UPLOAD=false` alone is not sufficient because the action still exports the record to generate the summary.

For an upstream fix, I would keep normal failure summaries but make the history-export portion cancellation-aware or time-bounded, while leaving temp-directory cleanup unconditional.
```

## Why this is a strong portfolio case

This issue demonstrates commercially useful debugging skills:

- distinguishing an action's main and post execution paths;
- reading source instead of inferring behavior from a step label;
- identifying remote BuildKit history export as the expensive boundary;
- separating build-record export from artifact upload;
- providing a low-risk workflow mitigation;
- avoiding a broad fix that would remove failed-build diagnostics and cleanup.