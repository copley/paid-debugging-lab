# Case Study 039: GitHub Actions workflow parser JSON import attributes

## Problem

`@actions/workflow-parser` 0.3.61 is an ES module package, but importing it under Node 24 fails with `ERR_IMPORT_ATTRIBUTE_MISSING` for the generated workflow/action JSON schema files.

Upstream issue: `actions/languageservices#387`.

## Root cause

The package declares `"type": "module"` and supports Node `>=20`. Its TypeScript configuration enables `resolveJsonModule`, but two runtime source files still use JSON imports without an ESM JSON import attribute:

```ts
import WorkflowSchema from "../workflow-v1.0.min.json";
import ActionSchema from "../action-v1.0.min.json";
```

`resolveJsonModule` solves TypeScript's compile-time resolution; it does not add the JSON module type required by Node's ESM loader. The built package is therefore rejected during module linking before parser code runs.

## Small PR candidate

Update both imports to preserve the JSON module type in emitted ESM:

```ts
import WorkflowSchema from "../workflow-v1.0.min.json" with { type: "json" };
import ActionSchema from "../action-v1.0.min.json" with { type: "json" };
```

Affected files:

- `workflow-parser/src/workflows/workflow-schema.ts`
- `workflow-parser/src/actions/action-schema.ts`

## Regression coverage

The key regression should exercise the built artifact with native Node rather than only source through Jest:

1. Build `workflow-parser`.
2. Import `workflow-parser/dist/index.js` with Node.
3. Exercise workflow schema initialization.
4. Exercise action schema initialization.
5. Assert no `ERR_IMPORT_ATTRIBUTE_MISSING` occurs.

This matters because the defect sits at the published ESM/runtime boundary, where source-level tests can remain green.

## Debugging lesson

TypeScript module resolution and Node runtime module loading are separate contracts. When a package ships ESM plus JSON or other non-JavaScript modules, CI should smoke-test the built package with the same native loader consumers will use.

## Suggested upstream diagnostic comment

I traced this to the emitted ESM boundary rather than the parser itself. `@actions/workflow-parser` is published with `"type": "module"`, while both `workflow-schema.ts` and `action-schema.ts` import their generated JSON files without a JSON import attribute. `resolveJsonModule` makes those imports valid to TypeScript, but it does not satisfy Node's runtime JSON-module contract, so the built package fails during module linking.

The narrow fix is to add `with { type: "json" }` to both schema imports and verify the compiler preserves it in `dist`. I would add a native-Node post-build smoke test against the built package, and exercise both workflow and action schema initialization so fixing one import cannot leave the second as a latent failure.