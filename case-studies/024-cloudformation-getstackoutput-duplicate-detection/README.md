# Case Study 024 — Distinct `Fn::GetStackOutput` values reported as duplicates

## Status

PR-ready source diagnosis. No matching open upstream pull request was found on 29 July 2026.

## Upstream issue

- `aws-cloudformation/cloudformation-validate#247`
- Downstream symptom: `aws/aws-cdk#38424`

## Problem

CloudFormation Validate rule `W9007` reports that arrays such as load-balancer `Subnets` contain duplicate values when every element is a distinct `Fn::GetStackOutput` expression.

A representative array contains three different output names:

```json
[
  { "Fn::GetStackOutput": { "StackName": "VpcStack", "Region": "ap-northeast-1", "OutputName": "PublicSubnet1" } },
  { "Fn::GetStackOutput": { "StackName": "VpcStack", "Region": "ap-northeast-1", "OutputName": "PublicSubnet2" } },
  { "Fn::GetStackOutput": { "StackName": "VpcStack", "Region": "ap-northeast-1", "OutputName": "PublicSubnet3" } }
]
```

The values are unknown until deployment, but they are not duplicate expressions.

## Root cause

The duplicate rule converts the resolved array to a set and compares cardinality:

```rego
count(prop_val) != count({v | some v in prop_val})
```

That is valid only when distinct intrinsic expressions remain distinguishable after template-model resolution.

`Fn::ImportValue` preserves concrete identity in its dynamic reason:

```rust
IntrinsicFn::ImportValue(arg) => {
    let reason = match self.resolve_node(*arg) {
        ResolvedValue::Concrete { value } => match value.as_str() {
            Some(export) => format!("cross-stack import: {export}"),
            None => "cross-stack import".into(),
        },
        _ => "cross-stack import".into(),
    };
    ResolvedValue::TypedDynamic {
        reason,
        param_type: PARAM_TYPE_STRING.into(),
    }
}
```

`Fn::GetStackOutput` resolves its arguments only for traversal side effects, discards every result, and returns the same object for every occurrence:

```rust
IntrinsicFn::GetStackOutput(args) => {
    let saved = self.current_path.clone();
    for (key, arg) in args {
        self.current_path = format!("{}.{}", saved, key);
        let _ = self.resolve_node(*arg);
    }
    self.current_path = saved;
    ResolvedValue::Dynamic {
        reason: "cross-stack output".into(),
    }
}
```

The set operation therefore sees several identical resolved values and emits `W9007`.

## Small PR candidate

### Files

```text
src/template-model/src/resolver.rs
src/cfn-validate/tests/github_issues.rs
src/resources/templates/gh-issues/issue-247.json
```

### Likely fix

1. Resolve `StackName`, `Region`, and `OutputName` while retaining the existing path traversal.
2. When those arguments are concrete, build a deterministic identity string from the named fields.
3. Return `ResolvedValue::TypedDynamic` with string type, matching `Fn::ImportValue` semantics.
4. Keep a conservative fallback when the arguments cannot be identified safely.
5. Do not use the array index/current path as identity, because that would hide exact duplicate expressions.

A minimal implementation can fix the common concrete-argument case first. A more complete implementation would derive identity from the intrinsic expression itself for partially unresolved arguments.

## Regression matrix

- distinct `OutputName` values: no `W9007`
- distinct `StackName` values: no `W9007`
- exact duplicate `Fn::GetStackOutput` expressions: `W9007` remains
- distinct `Fn::ImportValue` values: remains clean
- duplicate literals: `W9007` remains
- one stack output plus one literal: remains clean
- partially unresolved arguments: deterministic, documented behavior

## Verification

```bash
cargo test -p cfn-validate github_issues
cargo test -p template-model
cargo fmt --check
cargo clippy --workspace --all-targets -- -D warnings
```

## Diagnostic comment draft

> I traced this to loss of intrinsic identity before the `W9007` rule runs. The rule detects duplicates by converting the resolved array to a set. `Fn::ImportValue` includes the concrete export name in its dynamic reason, but `Fn::GetStackOutput` discards the resolved `StackName`, `Region`, and `OutputName` values and always returns `Dynamic { reason: "cross-stack output" }`. Distinct stack-output expressions therefore become equal before the set comparison.
>
> A focused fix is to build a deterministic reason from the concrete named arguments and return a string-typed dynamic value, while retaining a conservative fallback for unresolved arguments. The regression should prove that distinct output names stay distinct and that two exactly identical `Fn::GetStackOutput` expressions are still reported as duplicates.

## Prevention note

Any validation engine that canonicalizes unknown values must preserve expression identity. Collapsing all dynamic values of one intrinsic type into a single sentinel creates false duplicate, equality, and uniqueness results downstream.