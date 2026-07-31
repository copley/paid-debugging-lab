# Case Study 026 — Transient Buildx feature-probe failure becomes permanent

## Incident

A multi-platform Docker build can intermittently fail with:

```text
ERROR: failed to build: docker exporter does not currently support exporting manifest lists
```

The same builder and command usually work. The failure is most likely when a `docker:dind` builder is still initializing or the host is under load.

Upstream issue: `docker/buildx#3266`.

## High-signal clues

- The failure is intermittent even though the builder driver is unchanged.
- The error claims that a capability is unsupported rather than reporting that the capability probe failed.
- Buildx stores feature detection behind a client-wide `sync.Once`.
- The probe ignores errors from both Docker client creation and the daemon `Info` call.
- An error therefore produces the same empty feature map as a successful probe against a daemon that genuinely lacks the feature.

## Root-cause boundary

`util/dockerutil/client.go` currently models feature detection as a one-shot operation:

```go
func (c *Client) Features(ctx context.Context, name string) map[Feature]bool {
    c.featuresOnce.Do(func() {
        c.featuresCache = c.features(ctx, name)
    })
    return c.featuresCache
}
```

The internal probe then converts every communication failure into an empty map:

```go
func (c *Client) features(ctx context.Context, name string) map[Feature]bool {
    features := make(map[Feature]bool)
    if dapi, err := c.API(name); err == nil {
        if res, err := dapi.Info(ctx, dockerclient.InfoOptions{}); err == nil {
            // populate capabilities
        }
    }
    return features
}
```

This collapses two materially different outcomes:

```text
successful probe + unsupported feature -> cache false
transient probe failure               -> cache false
```

Because `sync.Once` is completed in both cases, a builder that becomes ready moments later is never probed again during that Buildx client lifetime. The later exporter validation then emits a deterministic compatibility error based on a transient bootstrap failure.

## Safe fix direction

Cache only a successful probe result.

A focused implementation can preserve the public `Features(ctx, name) map[Feature]bool` API while changing the internal contract:

1. Make the probe return `(map[Feature]bool, error)`.
2. Propagate errors from `API()` and `Info()` instead of converting them into an unsupported-feature result.
3. Protect initialization with a mutex or single-flight state rather than `sync.Once`.
4. Store the feature map and mark it initialized only when the probe returns `nil` error.
5. On a transient error, return a conservative empty map for that call without poisoning the cache, allowing a later caller to retry.
6. After the first successful probe, retain current permanent caching behavior.

The implementation should avoid holding a mutex across arbitrary caller work, but it may serialize the short initial daemon probe so concurrent startup requests do not stampede the Docker API.

## Regression tests

The narrow regression matrix is:

- first `Info` call fails; second succeeds with `driver-type=io.containerd.snapshotter.v1`; the second `Features()` call reports `OCIImporter`;
- a successful first probe is cached and later calls do not invoke `Info` again;
- an `API()` construction error is also retryable;
- concurrent callers are race-free and do not publish a partially initialized map;
- a successful probe that genuinely lacks the snapshotter still caches an empty feature set.

The most important assertion is that **probe failure is not cached while a valid negative capability result is cached**.

## Likely PR scope

```text
util/dockerutil/client.go
- replace sync.Once initialization with success-only cache state
- make features() return an error

util/dockerutil/client_test.go (or nearest existing dockerutil test file)
- add transient-failure/retry test
- add successful-negative-result cache test
- add concurrent access coverage
```

Verification should include the focused Go tests, `go test -race` for the package, and the repository's normal lint/test targets.

## Draft upstream diagnostic comment

> The cache is currently applied to the probe attempt rather than to a successful probe result.
>
> `Client.Features()` wraps the entire operation in `sync.Once`, while `features()` converts both `API()` and daemon `Info()` errors into an empty feature map. A transient startup failure is therefore indistinguishable from a successful “feature unsupported” result and remains cached for the lifetime of the client.
>
> I would keep the public return type, but make the internal probe return `(map[Feature]bool, error)` and cache only `nil`-error results under a mutex or single-flight guard. On error, the current call can remain conservative, but the next call must be allowed to retry.
>
> A focused regression should make the first `Info` call fail, the second return `driver-type=io.containerd.snapshotter.v1`, and assert that the second `Features()` call reports `OCIImporter`; a successful negative probe should still be cached.

## Status

Source-level diagnosis and PR plan only. No upstream comment or patch was posted because the retry and concurrency tests have not yet been executed against the Buildx repository.
