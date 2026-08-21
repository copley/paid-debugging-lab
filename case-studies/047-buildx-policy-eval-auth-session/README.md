# Case Study 047 — Buildx policy metadata resolution drops registry authentication

## Symptom

`docker login` succeeds and the image can be pulled, but `docker buildx policy eval --print --fields image.user docker-image://...` fails while resolving image metadata with an anonymous-token `401 Unauthorized`.

The same `policy eval --print` command without metadata fields succeeds because it does not need to resolve the remote image fields.

## Root cause

The policy command creates its source metadata resolver with:

```go
metaResolver := sourcemeta.NewResolver(c)
```

The resolver already supports session attachments through `sourcemeta.WithSession(...)`, and only forwards those attachments into its internal BuildKit `SolveOpt` when they are supplied.

This means the credential state is present in the Docker CLI configuration but is not propagated across the internal BuildKit source-metadata session boundary. When `--fields` causes remote image metadata to be resolved, that solve has no Docker auth provider attached and falls back to anonymous registry authentication.

This is an authentication-context propagation bug, not a missing `docker login`.

## Fix direction

Create the Docker registry auth provider from the active Docker CLI configuration and attach it to the policy metadata resolver through `sourcemeta.WithSession(...)`, using the same credential source conventions as normal Buildx build/bake paths.

Keep the change scoped to the resolver session. Do not copy credentials into request fields or special-case `dhi.io`.

## Regression coverage

Use an authenticated test registry and verify:

1. `policy eval --print` without remote fields remains successful without forcing metadata resolution.
2. `policy eval --print --fields image.user` succeeds when the Docker CLI config contains matching registry credentials.
3. The same field lookup fails cleanly when credentials are absent.
4. Credentials for one registry are not leaked to another registry.
5. Credential-helper-backed configuration follows the same path if the existing test harness supports it.

## Senior engineering lesson

Credentials are capabilities with scope and lifetime. Having credentials in a host CLI configuration does not make them available to an internal RPC, BuildKit solve, subprocess, or session automatically. When a new internal boundary is introduced, authentication context must be deliberately propagated through the boundary using the framework's supported credential/session mechanism.

## Upstream reference

- docker/buildx issue #3984 — `docker buildx policy eval --fields` fails to authenticate against `dhi.io`
- `commands/policy/eval.go`
- `util/sourcemeta/resolver.go`
