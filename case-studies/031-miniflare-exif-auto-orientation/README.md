# Case Study 031 — Miniflare local image transforms ignore EXIF orientation

## Target

- Repository: `cloudflare/workers-sdk`
- Issue: [#15029 — Local Images binding ignores EXIF orientation](https://github.com/cloudflare/workers-sdk/issues/15029)
- Area: TypeScript, Miniflare, image processing, local/production fidelity

## Symptom

Phone photos often store landscape-oriented pixel data with an EXIF orientation tag that tells viewers how to display the image. Miniflare's local Images polyfill transforms the stored pixels without first applying that orientation.

The result is an environment-dependent correctness bug:

```text
wrangler dev          -> transformed photo is sideways
wrangler dev --remote -> transformed photo is upright
production            -> transformed photo is upright
```

The transform succeeds and returns a valid image, so the defect is silent.

## Source-level diagnosis

The affected implementation is:

```text
packages/miniflare/src/plugins/images/fetcher.ts
```

The `env.IMAGES` path currently constructs Sharp as:

```ts
const transformer = sharp(await body.arrayBuffer(), {});
```

The `cf.image` path similarly creates transformed-output pipelines with:

```ts
const jsonTransformer = sharp(source);
const transformer = sharp(source);
```

The shared transform functions apply rotation only when the caller explicitly supplies a `rotate` value. They never request EXIF-based auto-orientation before resize, crop, padding, or encoding.

This explains the fidelity gap: production normalizes orientation before applying user transforms, while the local implementation operates on the raw stored pixel matrix.

## Small PR candidate

### Source change

Use Sharp's supported auto-orientation option for pipelines that produce transformed output:

```ts
sharp(source, { autoOrient: true })
```

Apply it to:

1. the `imagesLocalFetcher` transform pipeline;
2. the `cfImageLocalFetcher` JSON transformed-dimension pipeline;
3. the `cfImageLocalFetcher` encoded-output pipeline.

Keep the separate raw metadata read unchanged unless production's `original` metadata contract says otherwise. This preserves the distinction between:

- original stored dimensions and file metadata; and
- dimensions after orientation and user transforms.

That boundary matters because an EXIF orientation of 6 or 8 swaps the effective display width and height.

### Regression coverage

Relevant test areas include:

```text
packages/miniflare/test/plugins/core/cf-image.spec.ts
packages/miniflare/test/plugins/images/
```

Add a small committed JPEG fixture with a known EXIF orientation and asymmetric dimensions. The test matrix should include:

1. orientation 1 remains unchanged;
2. orientation 6 is upright and has swapped effective dimensions;
3. `env.IMAGES` resize operates on the oriented image;
4. `cf.image` encoded output follows the same behavior;
5. `format: "json"` reports transformed dimensions consistently while preserving the documented `original` fields;
6. an explicit user rotation composes once on top of EXIF orientation rather than applying or retaining the EXIF rotation twice.

Pixel-level assertions are preferable to checking only dimensions, because a sideways image can still have dimensions that appear plausible after a square resize.

## Verification

Run the focused Miniflare image tests and the package checks required by the repository:

```text
pnpm test --filter miniflare -- cf-image
pnpm test --filter miniflare -- images
pnpm lint
pnpm check:type
```

Then compare the same EXIF-oriented fixture through local and remote Images binding execution.

The exact workspace commands should be adjusted to the repository's current package scripts before opening the PR.

## Diagnostic comment draft

> I confirmed the local/production difference at the Sharp construction boundary. Both local image paths create transformed-output pipelines without EXIF auto-orientation, while the shared transform code calls `rotate()` only for an explicit user option. The local implementation therefore resizes and encodes the stored pixel matrix rather than the display-oriented image.
>
> A focused fix is to construct the transformed-output Sharp pipelines with `{ autoOrient: true }` in `imagesLocalFetcher` and `cfImageLocalFetcher`. I would keep the separate raw metadata read unchanged unless the production contract requires oriented values in the `original` object.
>
> Regression coverage should use an asymmetric orientation-6 JPEG and verify pixels as well as dimensions. It should cover `env.IMAGES`, `cf.image`, JSON transformed dimensions, and an explicit user rotation to prove that EXIF orientation is applied exactly once before later transforms.

## Scope

This case study identifies a contained implementation defect and test boundary. It does not claim that Miniflare can reproduce every production image-processing detail; it targets a specific baseline invariant: the same EXIF-tagged input and transform request must not be sideways locally and upright in production.
