# Case Study 051 — Wrangler disabled metrics still trigger agent-skills GitHub fetch

## Problem

Wrangler can make outbound requests to the public `cloudflare/skills` GitHub repository during an otherwise local command when it detects an AI coding agent, even when metrics are disabled.

Observed configuration:

```text
CODEX_THREAD_ID=<present>
WRANGLER_SEND_METRICS=false
WRANGLER_NO_SKILLS_UPDATE_PROMPTS=true
wrangler types --check
```

The command still contacts `api.github.com` to inspect Cloudflare agent skills.

## Root cause

The bug is an ordering problem in the metrics pipeline.

`getMetricsDispatcher()` builds the asynchronous telemetry enrichment first:

```ts
telemetryCurrentAgentSkillsInstalled()
  .catch(() => null)
  .then((currentAgentSkillsInstalled) => dispatch(...))
```

The telemetry enrichment can perform GitHub requests. Only later, inside `dispatch()`, does Wrangler evaluate `getMetricsConfig(options)` and return when `metricsConfig.enabled` is false.

So the opt-out suppresses the final Sparrow telemetry POST, but it does not suppress the network work performed while computing a property for an event that will never be sent.

This affects both command events and ad-hoc events because both construct the enrichment promise before `dispatch()` performs the enabled check.

## Why this matters

A telemetry opt-out should suppress telemetry-related side effects, not only the final analytics request. Unexpected network activity can:

- trigger host firewall prompts,
- break offline or hermetic builds,
- violate users' assumptions about telemetry opt-outs,
- add latency to local commands,
- make audit logs misleading because telemetry is nominally disabled.

## Fix direction

Move the metrics-enabled decision ahead of optional asynchronous enrichment.

A clean shape is to determine whether dispatching is enabled before calling `telemetryCurrentAgentSkillsInstalled()`. Both `sendCommandEvent()` and `sendAdhocEvent()` should short-circuit before starting the enrichment promise when metrics are disabled.

Avoid merely adding another check inside `dispatch()`: by that point the GitHub fetch may already have happened.

The skill-prompt opt-out is a separate policy question. The deterministic correctness fix is that telemetry enrichment must never run when telemetry itself is disabled.

## Regression tests

Add tests around the dispatcher boundary that prove:

1. metrics disabled + detected agent → `telemetryCurrentAgentSkillsInstalled()` is not invoked;
2. metrics enabled → enrichment is invoked and its value is attached to the event;
3. enrichment failure still degrades to `null` without failing the command;
4. ad-hoc and command-event paths follow the same rule;
5. no pending telemetry work is registered when metrics are disabled.

An integration-level check can stub the skills GitHub request and assert zero requests for `wrangler types --check` with metrics disabled.

## Verification

```text
WRANGLER_SEND_METRICS=false CODEX_THREAD_ID=test wrangler types --check
```

Expected after the fix: no request to `api.github.com` caused by agent-skills telemetry enrichment.

## Prevention

For opt-out systems, perform eligibility checks before constructing expensive or externally visible enrichment work. A late return at the transport layer is insufficient when event-property computation itself has side effects.

## Upstream reference

- cloudflare/workers-sdk#15344
