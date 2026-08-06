# Case Study 032 — Vitest Pool Workers: relative CJS imports fail in workspace paths containing spaces

## Incident

`@cloudflare/vitest-pool-workers` can load the entry point of an externalized CommonJS dependency, then fail when that dependency performs a relative `require()` such as:

```js
module.exports = require("./lib/impl.js");
```

The failure appears only when the project real path contains a space. A path such as:

```text
C:/work/wsdk repro/deps/cjs-demo/index.js
```

can re-enter the module fallback service as:

```text
C:/work/wsdk%20repro/deps/cjs-demo/lib/impl.js
```

The fallback then treats `%20` as literal filename characters and reports that the module does not exist.

Upstream issue: [cloudflare/workers-sdk#15048](https://github.com/cloudflare/workers-sdk/issues/15048)

## Why the existing fix is incomplete

The earlier `file:`-URL correction recovers an entry path from `rawSpecifier`:

```ts
if (rawSpecifier?.startsWith("file:")) {
  specifier = ensurePosixLikePath(fileURLToPath(rawSpecifier));
}
```

That branch cannot help a relative CommonJS import because its `rawSpecifier` is only:

```text
./lib/impl.js
```

By that point, the importing module's registered name already contains `%20`. Workerd derives the child module name from that encoded referrer, so the encoding propagates into every relative import beneath it.

## More precise root cause

Current main already has a safe redirect sentinel for paths that the fallback itself percent-encodes:

```text
/__mf_vitest_encoded__
```

`decodeEncodedSpecifier()` decodes only values carrying this prefix. This is important because an actual directory may legally be named `build%20output`; blindly applying `decodeURIComponent()` would silently resolve the wrong file.

The remaining gap is in `encodeRedirectLocation()`:

- it regards all printable ASCII characters from `0x20` through `0x7E` as safe;
- ASCII space (`0x20`) therefore does not trigger encoding or the sentinel;
- a redirect target containing a real space can later be URI-encoded by the transport/workerd boundary as `%20`;
- because that value has no sentinel, the fallback correctly refuses to decode it;
- relative imports inherit the unmarked `%20` module name and fail on filesystem lookup.

The safe fix is therefore not a global decode. It is to make the redirect producer mark and encode paths containing spaces before they cross the boundary.

## Small PR candidate

### Likely source changes

File:

```text
packages/vitest-pool-workers/src/pool/module-fallback.ts
```

Adjust the redirect-safety predicate so an ASCII space requires encoding and sentinel tagging. The replacement expression must use the same character boundary.

Conceptually:

```ts
// Space is not safe in a URI Location path and must take the tagged path.
const nonHeaderSafeRegExp = /[^\x21-\x7E]/u;

const encoded = filePath
  .replace(/%/g, "%25")
  .replace(/[^\x21-\x7E]/gu, (char) => encodeURIComponent(char));
```

The exact name of the predicate may be worth changing because this is now both a header-serialization and URI-identity boundary.

### Why this shape is safer

- A real space becomes `%20` under the existing sentinel.
- `decodeEncodedSpecifier()` can recover it deterministically.
- A literal `%20` directory that did not originate from the encoder remains untouched.
- Relative imports keep the leading sentinel path segment, so descendants remain decodable.
- Bare package names and `node:` / `cloudflare:` specifiers are unaffected.

## Regression matrix

File:

```text
packages/vitest-pool-workers/test/module-fallback.test.ts
```

Add tests covering:

1. `encodeRedirectLocation("/a/work space/dep.cjs")` produces the sentinel and `%20`.
2. The encoded value round-trips to the original path.
3. An original literal path `/a/build%20output/dep.cjs` remains unchanged.
4. An externalized CommonJS entry in a temporary directory containing a space can `require("./lib/impl.js")`.
5. The first redirect and the descendant request both preserve the sentinel.
6. Windows drive-letter and POSIX paths behave consistently.
7. Existing non-ASCII, literal-percent, malformed-percent and `file:` URL regressions remain passing.

An integration fixture should verify the actual returned module body, not merely the redirect status.

## Verification commands

```bash
pnpm --filter @cloudflare/vitest-pool-workers test -- module-fallback
pnpm --filter @cloudflare/vitest-pool-workers check
pnpm --filter @cloudflare/vitest-pool-workers build
```

The repository's exact focused-test invocation should be confirmed from its package scripts before submitting the PR.

## Diagnostic comment draft

> I think the narrow fix boundary is the redirect encoder, not a blind decode of every incoming `%20` path.
>
> Current main already uses `ENCODED_PATH_PREFIX` so only paths encoded by the fallback are decoded later; that intentionally preserves real directories whose names contain literal percent sequences such as `build%20output`. However, `encodeRedirectLocation()` currently treats ASCII space (`0x20`) as safe and returns the path without the sentinel. Once that redirect/module name crosses the workerd transport boundary, the space can reappear as unmarked `%20`. Relative CJS resolution then propagates that encoded referrer, and `decodeEncodedSpecifier()` correctly leaves it untouched.
>
> I would make space take the existing sentinel-encoding path by changing both the detection and replacement character ranges from `0x20–0x7E` to `0x21–0x7E`. A regression should create an externalized multi-file CJS dependency under a temporary path containing a space, follow the redirect, then require a sibling module. The existing literal-`%20` test should remain unchanged to prove this does not introduce ambiguous decoding.

## Status

- Root-cause hypothesis is source-backed.
- No public upstream comment was posted.
- No upstream patch or test run is claimed here.
- The proposed PR should be validated against the runnable reproduction and the repository's full module-fallback test suite before submission.
