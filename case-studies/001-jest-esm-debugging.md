# Case Study 001: Jest ESM Debugging

## Symptom

A TypeScript project passes its package test script but fails when Jest is invoked directly.

## Root cause

The direct Jest command bypasses runtime flags used by the project script for the ESM test environment.

## Fix direction

Use the package test script for targeted debugging, or preserve the same Node options when invoking Jest directly.

## Prevention

Document debug commands next to package scripts so contributors do not bypass required runtime configuration.
