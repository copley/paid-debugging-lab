# Case Study 021: Playwright tsconfig bare `extends` resolution

## Upstream issue

- Repository: `microsoft/playwright`
- Issue: `#41989`
- Area: TypeScript configuration loading and Playwright test discovery

## Symptom

Playwright 1.62 fails test discovery when a source package has a `tsconfig.json` that extends a hoisted configuration package, for example:

```json
{
  "extends": "foo/tsconfig.json"
}
```

The configuration package exists in a parent `node_modules` directory and is resolvable by TypeScript, but Playwright reports:

```text
Failed to resolve "extends" path "foo/tsconfig.json"
```

The same project passes on Playwright 1.61 because the unresolved inherited configuration was previously ignored.

## Reproduction path

A representative layout is:

```text
project/
├── node_modules/
│   └── foo/
│       └── tsconfig.json
├── dep-src/
│   ├── index.js
│   └── tsconfig.json       # extends foo/tsconfig.json
└── tests/
    └── example.spec.ts     # imports dep-src
```

Run:

```bash
npm install
npx playwright test
```

TypeScript resolves the bare `extends` specifier by walking upward through parent `node_modules` directories. Playwright currently does not.

## Root cause

`packages/playwright/src/transform/tsconfig-loader.ts` resolves inherited configuration files using only two locations:

1. relative to the directory containing the current `tsconfig.json`;
2. the immediate `<current-directory>/node_modules` directory.

The loader does not continue walking through parent directories. In a workspace, linked package, or hoisted dependency layout, the configuration package can legitimately live in a higher-level `node_modules` directory.

A recent change made unresolved `extends` and `references` paths fail loudly. That change improved diagnostics for genuine typos, but exposed the pre-existing incomplete package-resolution behavior as a fatal regression.

## Fix direction

Keep strict failure for truly missing configurations, but expand the package-style fallback to walk upward through `node_modules` directories.

A focused helper could look like:

```ts
function resolveFromParentNodeModules(
  startDirectory: string,
  referencedConfigFile: string,
): string | undefined {
  let directory = startDirectory;

  while (true) {
    const candidate = path.join(
      directory,
      'node_modules',
      referencedConfigFile,
    );

    if (fs.existsSync(candidate))
      return candidate;

    const parent = path.dirname(directory);
    if (parent === directory)
      return undefined;

    directory = parent;
  }
}
```

`resolveConfigFile()` would retain relative-path resolution first, then use this helper for package-style `extends` paths before throwing the existing error.

The implementation should avoid weakening the new fail-loudly behavior. A genuinely unresolved path must still produce the precise error introduced for `#41543`.

## Regression test

Add an inline test to `tests/playwright-test/resolver.spec.ts` containing:

```text
node_modules/foo/tsconfig.json
dep-src/tsconfig.json
dep-src/index.js
a.spec.ts
```

The nested `dep-src/tsconfig.json` should extend `foo/tsconfig.json`, while the package exists only in the project-root `node_modules` directory.

Assertions:

```text
- Playwright collects and runs the test successfully.
- Parent node_modules resolution is exercised.
- A genuinely absent package config still fails loudly.
- Relative extends and project references retain their existing behavior.
```

## Verification commands

```bash
npm run build
npx playwright test tests/playwright-test/resolver.spec.ts
npm run lint
```

The exact repository commands should be confirmed against the current contribution documentation before submission.

## Prevention note

Configuration resolution should be tested against workspace and hoisted dependency layouts, not only direct child `node_modules` directories. When converting a previously ignored condition into a fatal error, regression tests should cover every path that is valid in the upstream tool being emulated.

## Draft diagnostic comment

> I confirmed the regression at the configuration-resolution boundary.
>
> `resolveConfigFile()` first checks a path relative to the current `tsconfig.json`, then checks only that directory's immediate `node_modules`. It never walks through parent `node_modules` directories. That differs from the package-style resolution used by TypeScript and explains why a hoisted `foo/tsconfig.json` is resolvable by `tsc` but rejected by Playwright.
>
> The recent fail-loudly change did not create the incomplete resolution, but it converted it from a silently ignored condition into a fatal discovery error.
>
> A narrow fix is to retain the existing relative lookup, then walk upward through parent `node_modules` directories for package-style `extends` values before throwing. The unresolved-path error should remain unchanged when no candidate exists.
>
> I would add an inline resolver test with `dep-src/tsconfig.json` extending `foo/tsconfig.json`, where `foo` exists only in the project-root `node_modules`. A second assertion should preserve the current loud failure for a genuinely absent config.

## Status

Source-level diagnosis and patch design completed. The proposed change has not yet been run against Playwright's upstream test suite.
