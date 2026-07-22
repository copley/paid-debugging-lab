# Case Study 017: AWS CDK Lambda target-group documentation failure

## Upstream issue

- Repository: `aws/aws-cdk`
- Issue: `#38367`
- Area: Application Load Balancer Lambda targets

## Symptom

The documented multi-value-headers example creates an `ApplicationTargetGroup` with a `LambdaTarget` and `multiValueHeadersEnabled: true`, but omits an explicit Lambda target type.

Users copying the example receive:

```text
multiValueHeadersEnabled is only supported for Lambda targets.
```

## Source-level diagnosis

The constructor validates `multiValueHeadersEnabled` before it attaches the targets supplied through `props.targets`.

At that point, the target group has not yet learned that `LambdaTarget` implies `TargetType.LAMBDA`. The later `addTarget(...props.targets)` call arrives too late for the validation branch.

The README currently shows:

```ts
const targetGroup = new elbv2.ApplicationTargetGroup(this, 'LambdaTargetGroup', {
  vpc,
  targets: [new targets.LambdaTarget(lambdaFunction)],
  multiValueHeadersEnabled: true,
});
```

## Small PR candidate

Update the documentation example to set the type explicitly:

```ts
const targetGroup = new elbv2.ApplicationTargetGroup(this, 'LambdaTargetGroup', {
  vpc,
  targetType: elbv2.TargetType.LAMBDA,
  targets: [new targets.LambdaTarget(lambdaFunction)],
  multiValueHeadersEnabled: true,
});
```

## Why the documentation fix is appropriate

A constructor-level behavior change would require reordering target attachment and validation, which could alter side effects for every target-group construction path. The open issue is specifically a broken example, and the explicit property matches the constructor's current contract.

## Verification path

1. Run the existing documentation example without `targetType` and confirm the validation error.
2. Add `targetType: TargetType.LAMBDA` and confirm synthesis succeeds.
3. Verify the generated target group contains the Lambda target type and the `lambda.multi_value_headers.enabled` attribute.
4. Run the package documentation and example checks.

## Draft diagnostic comment

I confirmed this against the current README and constructor order.

`ApplicationTargetGroup` validates `multiValueHeadersEnabled` before it calls `addTarget(...props.targets)`. The `LambdaTarget` therefore has not yet attached and set the target group's lazy type when the validation runs, so the example fails unless `targetType` is explicit.

The smallest safe PR is documentation-only: add `targetType: elbv2.TargetType.LAMBDA` to the TypeScript example. The generated Python documentation should then include `target_type=elbv2.TargetType.LAMBDA`.

Changing constructor order would be a broader behavioral change and is not required to repair the published example.
