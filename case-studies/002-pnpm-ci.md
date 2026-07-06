# Case Study 002: pnpm CI

## Symptom

A project runs locally after switching to pnpm, but CI fails.

## Root cause pattern

The workflow no longer matches the package scripts and lockfile strategy.

## Fix direction

Use the package manager setup action, enable pnpm cache, install with the lockfile, and run CI-safe scripts.

## Prevention

When changing package managers, update local scripts and CI workflows together.
