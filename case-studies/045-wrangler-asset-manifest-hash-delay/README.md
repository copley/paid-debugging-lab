# Case Study 045 — Wrangler asset manifest hashing looks like an upload stall

## Problem

Cloudflare Workers SDK issue `cloudflare/workers-sdk#15259` reports a roughly 61-second gap between these Wrangler messages for an assets directory containing 16,280 entries:

```text
🌀 Building list of assets...
✨ Read 16280 files from the assets directory ...

[~61 seconds]

🌀 Starting asset upload...
🌀 Found 486 new or modified static assets to upload...
```

At first glance this looks like the uploader or Cloudflare API is idle for a minute after the directory has already been read.

The important diagnostic question is: **what does the `Read ... files` log line actually mean?**

## Root cause boundary

Current `syncAssets()` does this:

```ts
logger.info("🌀 Building list of assets...");
const manifest = await buildAssetManifest(assetDirectory);

logger.info("🌀 Starting asset upload...");
const initializeAssetsResponse = await fetchResult(...);
```

So everything between the two top-level messages is inside `buildAssetManifest()`.

Inside that function, however, the reassuring-looking `Read ... files` message is emitted immediately after recursive directory enumeration:

```ts
const files = await readdir(dir, { recursive: true });
logReadFilesFromDirectory(dir, files);
```

The expensive work happens **after** that log line. Wrangler then maps over every enumerated path and, for each non-ignored file:

1. calls asynchronous `stat(filepath)`;
2. checks type and size;
3. calls `hashFile(filepath)`;
4. inserts the hash and size into the manifest.

Only after all of those operations complete does `buildAssetManifest()` return and `syncAssets()` print `Starting asset upload`.

The source of `hashFile()` makes the cost clearer:

```ts
export const hashFile = (filepath: string) => {
    const contents = readFileSync(filepath);
    const base64Contents = contents.toString("base64");
    const extension = extname(filepath).substring(1);

    return blake3hash(base64Contents + extension)
        .toString("hex")
        .slice(0, 32);
};
```

That means every candidate asset is synchronously read into memory, converted to base64, concatenated with its extension, and hashed before the upload-session request begins.

The observed gap is therefore primarily a **local manifest-construction phase**, not an unexplained network/upload delay.

## Why "only 486 files changed" does not make the current path cheap

Wrangler does not know which files Cloudflare already has until it submits the complete local manifest to the assets-upload-session endpoint.

The server then returns the buckets of hashes that actually require upload.

So the current workflow is effectively:

```text
recursive readdir
    ↓
"Read 16280 files" log  ← currently sounds more complete than it is
    ↓
stat every candidate
    ↓
read + base64 + BLAKE3 hash every candidate
    ↓
manifest complete
    ↓
"Starting asset upload"
    ↓
POST full manifest to assets-upload-session
    ↓
server says 486 hashes need uploading
```

The fact that only 486 assets are eventually uploaded does not prevent the client from hashing all 16,280 candidates first.

## Performance concern

There is an additional implementation detail worth measuring carefully.

`buildAssetManifest()` uses `Promise.all(files.map(async ...))`, which gives the `stat()` calls asynchronous concurrency, but `hashFile()` itself uses `readFileSync()` and synchronous encoding/hashing. Once each callback reaches that stage, the synchronous work blocks the Node.js event loop.

That combination can create:

- a large burst of filesystem metadata requests;
- thousands of outstanding promises for large trees;
- synchronous whole-file reads on the event loop;
- a full base64 copy of each file before hashing;
- misleading timing because the last visible log preceded all of this work.

The correct optimization should be benchmark-driven rather than replacing one bottleneck blindly.

## Likely fix path

### 1. Fix the observability first

The lowest-risk patch is to make the phase boundary truthful.

For example:

```text
✨ Enumerated 16280 asset paths in 230 ms
🌀 Building asset manifest (stat + hash)...
✨ Built asset manifest for 15842 files in 61.2 s
🌀 Starting asset upload...
```

That alone turns an apparent API stall into an actionable local-performance measurement.

### 2. Bound filesystem concurrency

Instead of creating work for every path at once, use a bounded queue for `stat`/hash preparation. The repository already uses `PQueue` later in this same file for upload concurrency, so the pattern is familiar locally.

The optimal concurrency should be measured on SSD, networked filesystems and CI volumes; "maximum parallelism" is not automatically fastest for filesystem workloads.

### 3. Remove synchronous whole-file work from the hot event-loop path

A stronger optimization would make hashing stream- or worker-based if the selected BLAKE3 implementation supports it without changing the hash contract.

Any rewrite must preserve the exact existing digest semantics:

```text
BLAKE3(base64(file_contents) + extension).hex.slice(0, 32)
```

Changing that formula would invalidate Cloudflare's existing asset identity/cache behavior, so this is not a place for a casual hashing refactor.

### 4. Consider a local hash cache only with a safe invalidation contract

A cache keyed by path plus reliable metadata could avoid re-reading unchanged files on repeated deployments, but this is a larger design decision. `mtime + size` is fast but not a cryptographic statement that content is unchanged, and unusual filesystems can have timestamp-resolution issues.

It should be treated separately from the straightforward logging/concurrency patch.

## Regression and benchmark strategy

A useful test suite should separate correctness from performance:

### Deterministic unit coverage

- `logReadFilesFromDirectory` is not presented as "manifest complete";
- all non-ignored regular files still appear in the manifest;
- directories and symbolic links remain excluded;
- file-size validation still runs;
- the hash output remains byte-for-byte compatible with the existing implementation;
- bounded scheduling never exceeds its configured concurrency.

### Benchmark/instrumentation coverage

Generate a temporary tree with thousands of small files and record:

```text
readdir duration
stat/filter duration
hash duration
manifest total duration
assets-session API duration
actual upload duration
```

Do not make CI correctness depend on a strict wall-clock threshold. The valuable regression signal is which phase consumed the time and whether concurrency is bounded; absolute filesystem performance varies too much across runners.

## Small PR shape

A safe first contribution can stay deliberately narrow:

1. rename the existing `Read ... files` message to `Enumerated ... asset paths`;
2. add a completion/timing message after `buildAssetManifest()` has actually finished;
3. optionally split manifest timing into enumeration versus stat/hash;
4. add a unit test that pins the message ordering.

That gives maintainers real evidence before attempting a larger hashing optimization and fixes the user's immediate mystery without changing asset identity or upload behavior.

## General debugging lesson

Performance logs are part of the program's diagnostic API. A log line can be technically true and still point investigators at the wrong subsystem if it is emitted before the expensive part of the phase it appears to summarize.

When a timestamp gap looks like network latency, trace the exact code between the two log calls before profiling the network. Here, the source shows that the "missing minute" contains thousands of local `stat`, synchronous read, base64, and BLAKE3 operations before the first upload-session request is even sent.
