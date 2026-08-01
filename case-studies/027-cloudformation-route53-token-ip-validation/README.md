# Case Study 027 — Route53 token mistaken for a literal IP address

## Incident

AWS CDK synthesis reports `CloudFormation-Validate::E3023` for a valid Route53 `A` record whose value is produced by `Ref` or `Fn::GetAtt`:

```text
ResourceRecords.0: 'EC2EIP77649D11' is not a valid IPv4 address for record type 'A'
```

The generated template is valid: CloudFormation resolves the token to an IP address during deployment. The validator is applying a literal-IP regex to an unresolved value.

Upstream issue: `aws/aws-cdk#38460`.

Root-cause repository: `aws-cloudformation/cloudformation-validate`.

## High-signal clues

- A hard-coded valid IPv4 address is accepted.
- A hard-coded invalid IPv4 address correctly triggers `E3023`.
- `Fn::GetAtt` is rendered as a logical-ID-like string in the diagnostic.
- `Ref` can be rendered as an empty or placeholder string.
- The warning is emitted by CloudFormation Validate, not by the Route53 CDK construct.
- The validation project runs equivalent rules through both Rego and CEL engines.

## Root-cause boundary

The Rego rule resolves `Properties.ResourceRecords`, checks only that each result is a string, and then applies the IPv4 regex:

```rego
records := resolve(name, "Properties.ResourceRecords")
is_array(records)
some i, rec in records
is_string(rec)
not regex.match(IPV4_PATTERN, rec)
```

`is_string(rec)` does not prove that the user authored a literal string. A reference can be converted to a string-shaped placeholder during semantic resolution, so the rule loses the distinction between:

```text
literal invalid address: "999.1.1.1"
dynamic deployment value: { "Fn::GetAtt": ["EIP", "PublicIp"] }
```

Once both values reach the regex as strings, the validator reports the dynamic value as malformed.

The same distinction must be preserved in the CEL implementation. Its semantic model already represents concrete, reference, dynamic and typed-dynamic values separately; the IP-format check should run only on concrete string values.

## Safe fix direction

Validate only values proven to be authored concrete literals.

A focused correction should:

1. Preserve value provenance when iterating `ResourceRecords`.
2. Apply IPv4 or IPv6 syntax validation only to concrete strings.
3. Skip `Ref`, `Fn::GetAtt`, dynamic references and other unresolved intrinsic expressions.
4. Keep reporting genuinely malformed static strings.
5. Apply the same rule to `AWS::Route53::RecordSetGroup` entries.
6. Keep the Rego and CEL engines behaviorally equivalent.

The fix should not simply ignore every non-matching string. That would hide real configuration errors. The discriminator must be whether the value is concrete, not whether it resembles a token.

## Regression tests

The narrow regression matrix is:

- valid literal IPv4: no `E3023`;
- invalid literal IPv4: `E3023` remains;
- `Fn::GetAtt` returning an Elastic IP: no `E3023`;
- `Ref` to a parameter: no `E3023`;
- valid and invalid literal IPv6 equivalents;
- intrinsic values inside `AWS::Route53::RecordSetGroup`: no false positive;
- malformed static values inside `RecordSetGroup`: warning remains;
- both Rego and CEL engines return the same diagnostic set.

The existing dual-engine test helper in `src/cfn-validate/tests/regex_fixes.rs` is a suitable regression location.

## Likely PR scope

```text
src/rego-engine/handwritten/rego/resources/route53/recordset.rego
- distinguish concrete authored strings from resolved references
- update A and AAAA checks for RecordSet and RecordSetGroup

src/cel-engine/src/rules/resources_extra.rs
- run IP parsing only for ResolvedValue::Concrete string values
- skip Reference, Dynamic and TypedDynamic values

src/cfn-validate/tests/regex_fixes.rs
- add Ref/GetAtt regression templates
- retain invalid-literal control cases
- assert Rego/CEL agreement
```

Verification should run the focused CloudFormation validation tests, the Rego and CEL engine suites, formatting, linting and the repository's standard test target.

## Draft upstream diagnostic comment

> I traced this below the CDK construct layer into the Route53 `E3023` validation path.
>
> The Rego rule calls `resolve()` for `ResourceRecords`, checks only `is_string(rec)`, and then applies the IPv4/IPv6 literal regex. A `Fn::GetAtt` can therefore reach the rule as a logical-ID-like placeholder string, while a `Ref` can reach it as an empty or otherwise unresolved string. Both are then treated as authored literals.
>
> The fix should preserve value provenance and validate only concrete string values. `Ref`, `Fn::GetAtt`, dynamic and typed-dynamic values should be skipped, while malformed static strings must continue to trigger `E3023`. The CEL implementation needs the equivalent `ResolvedValue::Concrete` guard so the two engines remain aligned.
>
> I would add dual-engine regression cases for `Fn::GetAtt`, parameter `Ref`, invalid static IPv4/IPv6 values, and the corresponding `RecordSetGroup` paths.

## Status

Source-level diagnosis and PR plan only. No upstream comment or patch was posted because the proposed dual-engine change has not yet been executed against the CloudFormation Validate test suites.
