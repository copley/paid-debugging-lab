# Case Study 004 — Wrangler Pages summary labels

## Problem

A Cloudflare Pages deployment can be correctly published as production while the GitHub Actions job summary still labels the immutable deployment URL as `Preview URL` and prints the branch alias row as `undefined`.

This creates confusing CI output: the deployment itself is correct, but the job summary implies a preview deployment and exposes an absent optional value.

Upstream issue inspected: `cloudflare/wrangler-action#440`.

## Signal from the issue

The issue included the important debugging clues:

- the deployment was already classified as production;
- the misleading output came from the generated job summary;
- `createGitHubDeploymentAndJobSummary` already receives the deployment environment;
- the alias URL is optional but rendered unconditionally.

That made this a good small PR candidate because the likely failing boundary was isolated to one service file and one existing unit test file.

## Root cause hypothesis

`createJobSummary()` always renders these two rows:

```markdown
| **Preview URL**:        | ${deploymentUrl} |
| **Branch Preview URL**: | ${aliasUrl} |
```

That means:

1. production and preview deployments receive the same `Preview URL` label;
2. an absent alias URL is interpolated into Markdown as `undefined`;
3. the function is not using `pagesArtifactFields.environment`, even though the caller has that value.

## Fix path

A focused patch should:

1. add an `environment` argument to `createJobSummary()`;
2. pass `pagesArtifactFields.environment` from `createGitHubDeploymentAndJobSummary()`;
3. choose the immutable URL label from the environment:
   - `Production Deployment URL` or `Deployment URL` for production;
   - `Preview URL` for preview;
4. append the `Branch Preview URL` row only when `aliasUrl` is present;
5. update existing Vitest coverage.

## Likely implementation sketch

```ts
export async function createJobSummary({
  commitHash,
  deploymentUrl,
  aliasUrl,
  environment,
}: {
  commitHash: string;
  deploymentUrl?: string;
  aliasUrl?: string;
  environment?: string;
}) {
  const deploymentUrlLabel = environment === "production"
    ? "Production Deployment URL"
    : "Preview URL";

  const aliasRow = aliasUrl
    ? `| **Branch Preview URL**: | ${aliasUrl} |\n`
    : "";

  await summary
    .addRaw(`
# Deploying with Cloudflare Pages

| Name                    | Result |
| ----------------------- | - |
| **Last commit:**        | ${commitHash} |
| **${deploymentUrlLabel}**: | ${deploymentUrl} |
${aliasRow}  `)
    .write();
}
```

The exact Markdown alignment can be adjusted to match repository style.

## Tests to add

Minimum useful coverage:

1. preview deployment with alias URL keeps `Preview URL` and renders `Branch Preview URL`;
2. production deployment with no alias URL uses a production/deployment label;
3. production deployment with no alias URL does not contain `undefined`.

## Draft public diagnostic comment

```markdown
I inspected the summary-generation path and this looks isolated to `src/service/github.ts`.

`createGitHubDeploymentAndJobSummary()` already receives `pagesArtifactFields.environment`, but `createJobSummary()` currently only receives `commitHash`, `deploymentUrl`, and `aliasUrl`. Inside `createJobSummary()`, the Markdown always renders the immutable deployment URL as `Preview URL` and always renders `Branch Preview URL`, so an absent alias becomes `undefined`.

A focused fix would be to pass `pagesArtifactFields.environment` into `createJobSummary()`, choose the URL label based on whether the environment is production, and only append the alias row when `aliasUrl` is truthy.

I would cover this with two or three Vitest cases in `src/service/github.spec.ts`:

1. preview deployment with alias: keeps `Preview URL` and branch alias row;
2. production deployment without alias: uses a production/deployment label;
3. production deployment without alias: summary does not contain `undefined`.

That keeps the change scoped to the summary output and avoids touching the GitHub Deployment creation behavior.
```

## Why this is a strong portfolio case

This issue demonstrates the debugging style this repository is meant to show:

- inspect the actual failing boundary;
- identify where the available state is dropped;
- separate display bugs from deployment behavior;
- propose a minimal patch with regression tests;
- do not post vague comments or ask for money upfront.
