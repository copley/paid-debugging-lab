# Case Study 053 — Miniflare declaration rollup leaves a dangling `./shared` import

## Problem

A published Miniflare alpha package can expose `dist/src/index.d.ts` as its TypeScript entrypoint while that declaration file still imports `./shared`. The package does not ship a corresponding `dist/src/shared.d.ts`, so a normal consumer compiling with library checking enabled receives `TS2307: Cannot find module './shared' or its corresponding type declarations`.

The important failure is not simply “a file is missing.” The package build is designed to publish a **rolled-up declaration entrypoint**, so any relative declaration dependency left behind by the rollup violates the package boundary.

## Evidence

Upstream issue: `cloudflare/workers-sdk#15377`.

Cloudflare automated triage reproduced the published-package failure and confirmed that the declaration entrypoint references `./shared` while the expected declaration is absent from the package.

Current Miniflare source contains `src/shared/index.ts`, which re-exports several shared types. The source tree is therefore valid during monorepo compilation.

The package build has a different contract:

- `package.json` publishes `dist/src` and points `types` at `dist/src/index.d.ts`.
- `types:build` invokes `scripts/types.mjs ... --bundle`.
- that script first emits declarations to `dist-types/` and then uses API Extractor to roll them into a single `dist/src/index.d.ts`.
- the API Extractor configuration names only the rolled-up `dist/src/index.d.ts` as the declaration artifact.

A relative import that still points at `./shared` after this phase is therefore unsafe unless the build also emits and publishes the entire referenced declaration subtree.

## Root-cause boundary

This is a **declaration packaging / API Extractor boundary bug**, not primarily a TypeScript module-resolution bug in the consumer.

Inside the repository, `./shared` resolves because the source/declaration tree exists. Once the declarations are rolled up and the npm tarball is produced, the package must satisfy one of two invariants:

1. the public declaration entrypoint is self-contained apart from legitimate package dependencies; or
2. every relative declaration import reachable from that entrypoint is included in the tarball at the exact emitted path.

The published package violated both conditions for `./shared`.

## Fix direction

Prefer fixing the declaration rollup so the public `dist/src/index.d.ts` does not retain an unnecessary private relative import.

A safe implementation sequence is:

1. Build Miniflare declarations from a clean checkout.
2. Inspect `dist-types/src/index.d.ts` and the API Extractor output to identify why `Plugin`/shared types remain external to the rollup.
3. Adjust the public type boundary or API Extractor configuration so those types are rolled into `dist/src/index.d.ts`.
4. If a multi-file declaration output is intentionally required, copy the complete reachable declaration subtree rather than adding only a single `shared.d.ts` file; `src/shared` itself is a directory of re-exports and a one-file patch can simply move the dangling-import failure one level deeper.
5. Ensure workspace-only declaration dependencies are not leaked into the published package unless they are declared installable dependencies.

## Regression test

Do not test only the monorepo source tree. Test the artifact users actually install.

A useful package smoke test should:

1. run the Miniflare type build;
2. create the npm tarball;
3. install or unpack that tarball into a temporary consumer project;
4. compile a minimal `import { Miniflare } from 'miniflare'` program with `skipLibCheck: false`;
5. assert there are no unresolved relative declaration imports and no references to unpublished workspace-only packages.

This test catches packaging failures that ordinary repository-level TypeScript checks cannot see.

## Verification

The fix is complete when the packed artifact, not merely the workspace package, passes a clean TypeScript consumer compile and every relative import reachable from the declared `types` entrypoint resolves inside the tarball.

## Prevention

For libraries that roll up declarations, treat the packed npm artifact as a first-class CI product. A monorepo typecheck proves the source graph is valid; it does not prove the published declaration graph is closed.