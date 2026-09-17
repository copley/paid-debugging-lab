# Case Study 059 — Actions runner base-prefix string coercion drift

## Contribution status

- Upstream issue: https://github.com/actions/runner/issues/4724
- Checked issue comments: 2026-09-17
- Checked open PRs: 2026-09-17
- Assignment status: no competing fix found in the checked open PRs
- Intent comment: not posted
- Reproduction: upstream report includes an executed workflow; this portfolio pass performed source inspection rather than claiming an independent runtime reproduction
- Upstream PR: none
- Portfolio classification: source-inspected investigation, not an upstream contribution

## User-visible failure

GitHub Actions expression comparison coerces quoted strings to numbers through the runner's `ExpressionUtility.ParseNumber` path. Lowercase hexadecimal and octal prefixes are accepted, while uppercase `0X` / `0O` and binary `0b` inputs do not follow the JavaScript `Number()` behavior that the helper's own source comment names as its target.

The reported controls are useful because they distinguish string coercion from numeric-literal parsing: for example, `'0x1F' == 31` succeeds while `'0X1F' == 31` does not.

## Evidence

The current runner source contains two expression SDK copies of `ExpressionUtility.ParseNumber`. In the inspected implementation:

- hexadecimal recognition explicitly requires `str[1] == 'x'`;
- octal recognition explicitly requires `str[1] == 'o'`;
- there is no binary-prefix branch;
- the method comment says its conversion rules attempt to follow JavaScript `Number()` semantics.

Quoted strings reach this conversion through `EvaluationResult` numeric coercion, so this is a conversion-helper boundary rather than a YAML parser failure.

As of 2026-09-17, the issue had no discussion comments and the checked open PR search found no fix referencing #4724.

## Root cause

The runner implements a hand-written subset of JavaScript's string-to-number grammar. That subset is narrower than the stated contract: the base-prefix recognition is case-sensitive and binary prefixes are absent.

The repository also carries the conversion logic in both expression SDK trees, so a safe change needs to keep those implementations synchronized.

## Bounded implementation path

Keep the first patch grammar-only:

1. accept both `0x` and `0X`;
2. accept both `0o` and `0O`;
3. add `0b` and `0B` binary conversion;
4. apply the same behavior to both expression SDK implementations;
5. add expression-level tests using **quoted string operands**, so the patch does not accidentally redefine numeric-literal tokenization.

Do not silently broaden integer range or precision in the same patch. The current base-prefixed paths use 32-bit integer conversions, which may represent a separate JavaScript-compatibility gap, but that is a distinct behavioral decision and should have its own evidence and tests.

## Verification plan

A focused regression matrix should exercise string coercion through normal expression evaluation:

| Quoted input | Numeric comparison | Expected |
| --- | ---: | --- |
| `'0x1F'` | `31` | true |
| `'0X1F'` | `31` | true |
| `'0o17'` | `15` | true |
| `'0O17'` | `15` | true |
| `'0b101'` | `5` | true |
| `'0B101'` | `5` | true |
| invalid base digit control | matching decimal | false / NaN semantics |

Adjacent tests should retain existing decimal, exponent, `Infinity`, whitespace and invalid-string behavior. If the repository has separate test assemblies for the two expression SDKs, both need the same conformance cases.

## Engineering lesson

When a compatibility layer claims another runtime's coercion semantics, handwritten grammar branches become a drift risk. The safest regression tests should exercise the public semantic boundary—not only helper functions—and should distinguish conversion of strings from parsing of source-language literals.
