# Case Study 061 — Vitest focus matcher loses shadow-root focus identity

## Contribution status

- Upstream issue: https://github.com/vitest-dev/vitest/issues/11311
- Checked issue comments: 2026-09-19
- Checked open PRs: 2026-09-19
- Assignment status: unassigned
- Intent comment: none
- Reproduction: reporter-provided reproduction inspected; not independently executed in this investigation
- Upstream PR: none at time of review
- Portfolio classification: source-inspected investigation, not an upstream contribution

## User-visible failure

Vitest Browser Mode's `toHaveFocus()` matcher fails for an element that is actually focused inside a shadow root. The failure message reports the custom-element host as the focused element instead of the focused shadow descendant.

That makes a normal browser focus state impossible to assert directly for Web Components and other shadow-DOM controls.

## Evidence

Current `packages/browser/src/client/tester/expect/toHaveFocus.ts` resolves the subject element and then compares:

```ts
htmlElement.ownerDocument.activeElement === htmlElement
```

The same `ownerDocument.activeElement` value is used in the failure message.

That is correct for light DOM but loses the inner focus target at a shadow boundary. When focus is inside a shadow tree, the document's active element is the shadow host, while the shadow root exposes the focused descendant through `ShadowRoot.activeElement`.

Existing `test/browser/fixtures/expect-dom/toHaveFocus.test.ts` covers only ordinary light-DOM elements, so the boundary is currently untested.

## Root cause

The matcher asks the wrong focus scope. Focus identity is scoped to a document or shadow root, but the implementation always asks the owner document.

For a leaf element inside a shadow root:

```text
Document.activeElement      -> <my-component> host
ShadowRoot.activeElement    -> <input> leaf
matcher subject             -> <input> leaf
```

Comparing the subject only with `Document.activeElement` therefore produces a false negative even though the browser focus state is valid.

The diagnostic path has the same scope error, so the matcher also reports the host instead of the focused leaf.

## Bounded fix path

Resolve the active element from the subject element's own tree root once, then use that value for both the boolean result and the failure message.

A conservative implementation should distinguish a real `ShadowRoot` from other possible `DocumentFragment` roots:

```ts
const root = htmlElement.getRootNode()
const activeElement = root instanceof ShadowRoot
  ? root.activeElement
  : htmlElement.ownerDocument.activeElement
```

Then compare `activeElement === htmlElement` and print `activeElement` in the diagnostic.

This preserves existing light-DOM semantics while correctly handling a focused descendant inside an open shadow root. It also naturally follows the immediate focus scope for nested shadow trees.

Avoid changing the matcher into a generic "contains focus" check. A shadow host may be `document.activeElement` while a deeper descendant owns the actual focus; the matcher should continue answering whether the specific subject is the focused element in its own focus scope.

## Regression matrix

Add focused browser tests beside the existing `toHaveFocus` fixture:

1. light-DOM focused input still passes;
2. input inside an attached open shadow root passes after `input.focus()`;
3. unfocused sibling inside the same shadow root fails;
4. failure diagnostics for that sibling report the focused shadow descendant, not the host;
5. optional nested-shadow case verifies that the leaf is compared against its immediate `ShadowRoot.activeElement`.

## Verification plan

Run the focused Browser Mode expect-DOM fixture across the browser providers used by the repository, followed by the browser package's normal type/lint/test checks. The reporter's reproduction is a useful external control, but the upstream regression should pin the DOM focus-scope invariant directly.

## Engineering lesson

Browser state is often scoped by DOM tree boundaries. When an API is expected to work with Web Components, reading state exclusively from `ownerDocument` can collapse shadow-local state into the host and produce false diagnoses. The assertion should query state at the same ownership boundary as the element being asserted.
