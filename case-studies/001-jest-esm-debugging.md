# Case Study 001: Jest ESM Debugging

## Symptom

A TypeScript project passes its normal test script but fails when a contributor runs the test runner manually during debugging.

## Reproduction path

Run the same test through both the package script and the manual command, then compare the environment used by each process.

## Root cause

The manual command can bypass runtime options configured in the package script. In ESM projects, that can produce confusing test-runner failures that look like source-code problems.

## Fix direction

Use the project test script for targeted debugging, or make the manual command match the package script environment.

## Verification

The targeted test and full test suite should both pass with the same module-system assumptions.

## Prevention

Document local debugging commands near the package scripts.

## Engineering lesson

When CI and local debugging behave differently, compare commands, runtime flags, package-manager behavior, and module-system settings before changing application code.
