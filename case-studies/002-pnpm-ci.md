# Case Study 002: pnpm CI

## Symptom

A project runs locally after switching to pnpm, but CI fails.

## Reproduction path

Compare local commands with CI commands.

## Root cause pattern

The workflow no longer matches the package scripts and lockfile strategy.

## Fix direction

Use the package manager setup action, enable pnpm cache, install with the lockfile, and run CI-safe scripts.

## Verification

The CI workflow should pass on a clean runner.

## Prevention

When changing package managers, update local scripts and CI workflows together.
