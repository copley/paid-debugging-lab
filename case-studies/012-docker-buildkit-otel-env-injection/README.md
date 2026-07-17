# Case Study 012: BuildKit OpenTelemetry variables inside Docker `RUN` steps

## Incident

A Docker build started exposing OpenTelemetry variables inside `RUN env` after the workflow switched to a Buildx builder configured by `docker/setup-buildx-action`:

```text
OTEL_TRACES_EXPORTER=otlp
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=unix:///dev/otel-grpc.sock
OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=grpc
TRACEPARENT=...
```

Upstream issue: [docker/setup-buildx-action#356](https://github.com/docker/setup-buildx-action/issues/356)

The initial symptom makes the action look as though it is copying the GitHub Actions environment into the Dockerfile build. Source inspection places the behavior one layer lower, inside BuildKit's OCI process-spec construction.

## Boundary isolation

The relevant path is:

```text
setup-buildx-action
  -> creates/selects a Buildx builder
  -> Buildx sends the build to BuildKit
  -> BuildKit creates the OCI process spec for each RUN instruction
  -> BuildKit injects tracing configuration and trace context
```

In `moby/buildkit/executor/oci/spec.go`, BuildKit defines the exporter variables it wants child processes to receive:

```go
var tracingEnvVars = []string{
    "OTEL_TRACES_EXPORTER=otlp",
    "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=" + getTracingSocket(),
    "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=grpc",
}
```

When a tracing socket is active, `GenerateSpec()` appends those variables to the build-step environment and also appends the result of `childprocess.Environ(ctx)`.

`moby/buildkit/util/tracing/childprocess/traceexec.go` injects the active W3C trace context as `TRACEPARENT` and, when present, `TRACESTATE`.

Therefore, the variables are not invented by the GitHub Action or copied accidentally from the workflow shell. They are deliberately added by the BuildKit daemon used by the selected builder.

## Why plain `docker build` can differ

A plain `docker build` invocation may use a different builder implementation, daemon version, driver, or tracing-socket configuration. The comparison is not necessarily the same execution path with and without one action step.

A useful confirmation sequence is:

```bash
docker buildx ls
docker buildx inspect --bootstrap
docker version
docker buildx version
```

Then compare the builder driver and BuildKit version used in both cases.

## Practical mitigation

BuildKit's current implementation preserves an explicitly supplied exporter setting instead of overwriting it. When the objective is to stop trace export from build-step processes, set:

```dockerfile
ENV OTEL_TRACES_EXPORTER=none
```

or provide the equivalent environment value through the frontend/build definition.

That disables the exporter, but it does not guarantee that every tracing-related variable will be absent from `RUN env`. If strict environment absence is required, the missing capability is an upstream BuildKit option that disables child-process trace propagation entirely.

## Upstream fix direction

This does not look like a focused `setup-buildx-action` defect. The useful upstream options are:

1. document that a trace-enabled BuildKit daemon propagates OpenTelemetry state into build-step processes;
2. add a BuildKit daemon or build option that disables child-process tracing propagation;
3. retain the current default while allowing strict opt-out without editing each Dockerfile.

Regression coverage in BuildKit should verify:

- a tracing socket causes exporter and trace-context injection;
- an existing `OTEL_TRACES_EXPORTER` value is preserved;
- a future propagation-disable option produces no tracing variables;
- ordinary user environment values remain unchanged.

## Diagnostic comment

```text
I traced the variables below setup-buildx-action, into BuildKit's OCI process-spec generation.

When a tracing socket is active, `moby/buildkit/executor/oci/spec.go` appends `OTEL_TRACES_EXPORTER`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, and `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` to the environment of each build process. It then calls `childprocess.Environ(ctx)`, which adds `TRACEPARENT` and `TRACESTATE` for cross-process trace propagation.

That means the action is not copying arbitrary workflow variables into the image build. Selecting the Buildx builder changes the BuildKit daemon/driver path, and that daemon is deliberately propagating tracing state into `RUN` processes.

For a practical opt-out from exporting spans, BuildKit currently preserves an explicit setting, so `OTEL_TRACES_EXPORTER=none` can be supplied in the build environment. If the requirement is that the variables be completely absent, that appears to require an upstream BuildKit propagation-disable option rather than a change to setup-buildx-action.

I would compare `docker buildx inspect --bootstrap`, the builder driver, and the BuildKit version between the working and failing paths to confirm the boundary.
```

## Commercial debugging signal

This incident demonstrates an important debugging pattern: the step that changes observed behavior is not always the component generating it. The actionable diagnosis came from following the execution boundary from GitHub Actions, through Buildx, into BuildKit's runtime process specification.
