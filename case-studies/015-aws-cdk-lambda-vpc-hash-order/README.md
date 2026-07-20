# Case Study 015 — AWS CDK Lambda version hash changes when VPC ID order changes

## Issue

[`aws/aws-cdk#38348`](https://github.com/aws/aws-cdk/issues/38348) reports that `Function.currentVersion` can synthesize a different Lambda Version logical ID even when the effective VPC configuration is unchanged.

The failure appears when `Vpc.fromLookup()` returns the same subnet set in a different order between fresh synth runs. The generated `VpcConfig.SubnetIds` array then serializes differently, changing the version hash and causing CloudFormation to replace the `AWS::Lambda::Version` resource. Lambda may reject the replacement with `AlreadyExistsException` because the code and effective configuration are otherwise unchanged.

## Failure boundary

The relevant path is:

```text
Function.currentVersion
  -> calculateFunctionHash()
  -> filterUsefulKeys()
  -> sortFunctionProperties()
  -> JSON.stringify()
  -> md5hash()
```

`VpcConfig` is correctly classified as version-locked, but `sortFunctionProperties()` only stabilizes top-level property ordering. It does not normalize the order-insensitive arrays inside `VpcConfig`.

Current behavior:

```ts
const updatedProps = sortFunctionProperties(filterUsefulKeys(properties, fn));
stringifiedConfig = JSON.stringify(updatedProps);
```

The top-level `VpcConfig` key is stable, but these two arrays can still produce different JSON for the same effective set:

```text
VpcConfig.SubnetIds
VpcConfig.SecurityGroupIds
```

## Root-cause hypothesis

Lambda treats the selected subnet IDs and security-group IDs as sets for configuration purposes. Array order does not change the function's behavior, but CDK currently hashes the serialized order.

Therefore:

```text
[subnet-a, subnet-b] != [subnet-b, subnet-a]
```

at the JSON/hash layer even though both configurations are operationally equivalent.

## Small PR candidate

Keep the normalization local to Lambda version hashing rather than changing the synthesized CloudFormation template globally.

A focused implementation would:

1. shallow-clone `VpcConfig` before hashing;
2. sort `SubnetIds` when every element is a resolved string;
3. sort `SecurityGroupIds` under the same condition;
4. leave unresolved token/intrinsic arrays unchanged;
5. preserve all other nested property ordering.

Illustrative shape:

```ts
function normalizeVpcConfigForHash(vpcConfig: any): any {
  if (!vpcConfig)
    return vpcConfig;

  return {
    ...vpcConfig,
    SubnetIds: sortResolvedStringArray(vpcConfig.SubnetIds),
    SecurityGroupIds: sortResolvedStringArray(vpcConfig.SecurityGroupIds),
  };
}

function sortResolvedStringArray(value: any): any {
  if (!Array.isArray(value) || !value.every(item => typeof item === 'string'))
    return value;
  return [...value].sort();
}
```

`sortFunctionProperties()` can then replace only the hash input's `VpcConfig` with the normalized clone.

The resolved-string guard is important. Blindly sorting CloudFormation intrinsic objects with JavaScript's default comparator can collapse them to identical string representations such as `[object Object]` and introduce a different nondeterminism.

## Regression tests

Add tests to `packages/aws-cdk-lib/aws-lambda/test/function-hash.test.ts`:

1. two functions with the same subnet IDs in opposite orders produce equal hashes;
2. two functions with the same security-group IDs in opposite orders produce equal hashes;
3. changing an actual subnet or security-group ID still changes the hash;
4. intrinsic/unresolved array entries retain their existing order and do not crash normalization;
5. unrelated nested ordering, especially environment-variable behavior protected for compatibility, remains unchanged.

The tests can avoid live VPC lookup by adding a `VpcConfig` property override to each function's `CfnFunction` child.

## Compatibility note

This fix may cause a one-time hash change for applications whose current literal VPC arrays are not already sorted. That risk should be called out in the PR. If CDK maintainers require strict hash backward compatibility, the normalization may need a feature flag; otherwise the one-time change may be accepted as the cost of removing ongoing nondeterminism.

## Draft upstream diagnostic comment

> I inspected the hash path and the nondeterminism is below the top-level property sorter. `calculateFunctionHash()` serializes `sortFunctionProperties(...)`, but that function only stabilizes the order of top-level Lambda properties. `VpcConfig` remains an opaque nested object, so the order of `SubnetIds` and `SecurityGroupIds` still affects `JSON.stringify()` and therefore the MD5 hash.
>
> A focused fix would normalize those two arrays only for the version-hash input. I would clone `VpcConfig` and sort an array only when all entries are resolved strings; unresolved CloudFormation intrinsics should retain their current order rather than being passed through JavaScript's default object comparator.
>
> Regression coverage can construct two functions, add equivalent `VpcConfig` overrides with reversed ID order, and assert equal hashes. A second case should change one ID and assert the hash still changes. This keeps the patch local to Lambda version identity and avoids globally reordering the synthesized template.

## Outcome

The issue is a strong small-PR candidate: one source file, one existing unit-test file, a clear semantic invariant, and no open PR referencing the issue at the time of inspection.

No public comment was posted during this analysis.