# Case Study 022: AWS CDK construct dependency expansion across nested stacks

## Upstream issue

- Repository: `aws/aws-cdk`
- Issue: `#38406`
- Symptom: upgrading from CDK 2.259.0 to 2.262.1 expands a resource's `DependsOn` list from roughly 49 entries to more than 1,600, increasing a nested-stack template from about 710 KB to almost 9 MB.

## Symptom

A construct-level dependency such as:

```ts
source.node.addDependency(largeConstruct);
```

is reified into a very large set of resource dependencies when `largeConstruct` contains many resources distributed across nested stacks.

The generated CloudFormation template can exceed the 1 MB nested-stack template limit even though its real resource count barely changes.

## Regression boundary

The regression aligns with the dependency-dispatch refactor merged in `aws/aws-cdk#38314` and released in CDK 2.262.0.

That refactor correctly avoids the previous Cartesian-product cost for dependencies between top-level stacks. The same-stack branch, however, expands construct containers through `dependingResourcesFor()`.

For ordinary constructs, `dependingResourcesFor()` delegates to `findCfnResources()`, which performs an unrestricted preorder traversal:

```ts
function* findCfnResources(root: IConstruct): IterableIterator<CfnResource> {
  for (const node of iterateDfsPreorder(root)) {
    if (CfnResource.isCfnResource(node))
      yield node;
  }
}
```

That traversal can descend through nested-stack construct boundaries and expose every child resource instead of representing each nested stack by its parent-stack `AWS::CloudFormation::Stack` resource.

## Previous semantic boundary

Before the refactor, resource dependencies crossed nested-stack boundaries through `resourceInCommonStackFor()`.

That helper repeatedly moved a resource toward the deepest common stack and used the nested stack's representative resource at each boundary. It did not flatten every resource contained inside descendant nested stacks into the parent template's `DependsOn` list.

## Root-cause hypothesis

The new traversal preserves the broad conceptual rule that all source descendants depend on all target descendants, but it loses the CloudFormation boundary rule used by the previous implementation:

> A nested stack should normally be represented by its `AWS::CloudFormation::Stack` resource when a dependency is materialized in its parent stack.

As a result, a dependency on one large L3 construct can be converted into dependencies on the entire transitive resource tree.

## Focused fix direction

Change construct resource enumeration so traversal does not descend through nested-stack boundaries.

Conceptually:

```ts
function* findDependencyResources(root: IConstruct): IterableIterator<CfnResource> {
  for (const child of root.node.children) {
    if (NESTED_STACK_TYPE.isMarked(child)) {
      const nested = child as NestedStack;
      if (nested.nestedStackResource)
        yield nested.nestedStackResource;
      continue;
    }

    if (CfnResource.isCfnResource(child))
      yield child;

    yield* findDependencyResources(child);
  }
}
```

The exact implementation should use the existing construct-iteration utilities where possible, but it must prune traversal after yielding a nested stack's representative resource.

## Regression tests

Add a focused test in `packages/aws-cdk-lib/core/test/deps.test.ts`:

1. Create one parent stack.
2. Add a source `CfnResource` in the parent stack.
3. Create a target construct containing several nested stacks.
4. Put many resources inside each nested stack.
5. Call `source.node.addDependency(targetConstruct)`.
6. Synthesize the parent template.
7. Assert that the source depends on the nested-stack resources, not every resource inside every nested stack.

Also verify:

- direct resources under the target construct still become dependencies;
- dependencies entirely inside one nested stack still resolve at that stack's resource level;
- cross-stack dependency performance from `#38314` remains linear;
- duplicate nested-stack dependencies are deduplicated.

## Verification commands

```bash
npx jest packages/aws-cdk-lib/core/test/deps.test.ts --runInBand
npx integ-runner --dry-run
```

A reduced synthesis comparison should additionally confirm that the generated `DependsOn` list remains stable between small and large nested-stack contents.

## Draft diagnostic comment

I compared the current dependency dispatcher with the implementation removed by `#38314`, and the likely regression is nested-stack boundary flattening rather than simple duplicate insertion.

In the new same-stack path, an ordinary target construct is expanded through `findCfnResources()`, which performs an unrestricted preorder traversal. If the target L3 contains nested stacks, that traversal can return every resource below those stacks. The source resource then receives a dependency on each returned resource.

The previous `resourceInCommonStackFor()` path moved dependencies toward the deepest common stack and represented a nested stack through its `AWS::CloudFormation::Stack` resource. That preserved the parent-template boundary instead of flattening all descendant resources into the parent resource's `DependsOn` list.

A focused regression test should create a target construct containing several nested stacks with many child resources, then assert that a parent-stack source depends only on the nested-stack representative resources. The likely fix is to prune resource enumeration at nested-stack boundaries while preserving direct child-resource discovery and the cross-stack performance improvement from `#38314`.

## Engineering lesson

Performance refactors that change graph traversal must preserve abstraction boundaries as well as asymptotic complexity. In CloudFormation, nested stacks are deployment containers, not transparent folders of parent-stack resources.
