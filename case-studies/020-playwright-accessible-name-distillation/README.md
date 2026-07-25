# Case Study 020: Playwright accessible name lost during AI snapshot distillation

## Upstream issue

`microsoft/playwright#41985` — a button with nested text spans and an `aria-hidden` SVG is rendered as a nameless button in the Playwright MCP accessibility snapshot, even though role-based lookup and Chromium's accessibility tree both report the correct name.

## Symptom

Given markup shaped like:

```html
<button>
  <span>
    <span><svg aria-hidden="true"></svg></span>
    <span>Add New Item</span>
  </span>
</button>
```

Playwright can locate the element with:

```ts
page.getByRole('button', { name: 'Add New Item' })
```

but the AI-oriented snapshot may render:

```text
- button [ref=e5]
```

instead of:

```text
- button "Add New Item" [ref=e5]
```

## Evidence boundary

The tree builder obtains the name through `getElementAccessibleName()` before snapshot distillation. This is the same accessibility computation family used by role-based matching, so the missing text is unlikely to originate in Chromium or the initial accessible-name calculation.

The relevant boundary is the AI-mode distiller in:

```text
packages/injected/src/ariaSnapshotDistiller.ts
```

Two compression passes interact:

1. `removeRedundantNames` removes a node's accessible name when all content contributors appear elsewhere in the rendered tree.
2. `removeNameRepeatingChild` removes a generic child whose text repeats the parent's accessible name.

The intended invariant is that one representation survives: either the control keeps its name or the contributing text remains in its descendants.

## Root-cause hypothesis

With nested generic wrappers, contributor refs can be marked as represented, making the button name eligible for removal. During child post-order processing, the text-bearing generic subtree can also be removed because it repeats the button's name at that moment.

When the button later exits `removeRedundantNames`, its name is cleared based on contributor bookkeeping that no longer matches the final distilled tree. The result preserves neither representation.

This is an information-preservation defect between two individually reasonable compression passes, not an accessible-name computation failure.

## Focused fix direction

Coordinate the two plugins so removing a name-repeating child cannot also authorize removal of the parent's name.

Viable implementation strategies include:

- when `removeNameRepeatingChild` removes a subtree, mark any parent-name contributor refs in that subtree as pending again, causing `removeRedundantNames` to retain the parent name;
- or prevent removal of the repeating child when the parent name is itself scheduled to be removed from content-ref bookkeeping;
- or perform a final invariant pass that restores the original accessible name when distillation removed both the name and every rendered representation of its contributing text.

The first option preserves the existing compact output: the redundant child disappears and the actionable control keeps its name.

## Regression tests

Add an AI-mode snapshot test using the exact nested-span/SVG structure and verify:

```text
- button "Add New Item" [ref=...]
```

Also cover:

1. a simple button with direct text;
2. nested spans without an SVG;
3. an `aria-hidden` decorative SVG before the text;
4. an explicitly labelled button using `aria-label`;
5. a content-derived name whose child text remains rendered, where removing the parent name is still valid;
6. no regression to standard `ariaSnapshot()` mode, which uses only normalization plugins.

## Verification commands

```bash
npm test -- tests/page/page-aria-snapshot.spec.ts
npm test -- tests/mcp
npm run lint
```

Exact project commands should be confirmed from the current Playwright contributor documentation before submission.

## Draft upstream diagnostic comment

I traced this past the accessible-name computation boundary. The snapshot builder calls `getElementAccessibleName()` before distillation, and the successful `getByRole(..., { name })` result plus Chromium AX tree output indicate that the original name is available.

The likely loss occurs in the AI distiller. `removeRedundantNames` may clear the button name when its content-contributor refs appear represented in descendants, while `removeNameRepeatingChild` can remove the generic descendant because its text repeats the button name. With nested spans, those two decisions can leave neither representation in the final tree.

I would add the reported markup as an AI-mode regression test and preserve the invariant that either the control name or its contributing text survives. A focused implementation is to make removal of a name-repeating child restore the relevant contributor ref to the pending set, so the parent retains its accessible name.

## Prevention lesson

Lossy tree compression needs explicit semantic invariants. Each optimization can be locally correct while their composition removes the only remaining representation of information required by downstream automation.
