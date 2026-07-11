# Case Study 006 — Firefox worker WebSocket crashes the Playwright page session

## Problem

A WebSocket opened inside a dedicated Web Worker causes Playwright Firefox 1.61.x to terminate the page session with a bare `Assertion error`. The same WebSocket works when opened by the main page, and the regression does not reproduce in Chromium or WebKit.

Upstream issue inspected: `microsoft/playwright#41742`.

## Signal from the issue

The report includes a self-contained WebSocket server and a version bisect:

- Playwright 1.60.0 passes;
- Playwright 1.61.0 and 1.61.1 fail;
- the worker receives the WebSocket `open` event immediately before the page session is detached;
- the protocol trace contains `Page.webSocketOpened`, followed by `Browser.detachedFromTarget`.

That sequence indicates that the browser completed the WebSocket handshake. The crash occurs while Playwright translates Firefox protocol events into its internal WebSocket lifecycle.

## Source inspection

`packages/playwright-core/src/server/firefox/ffPage.ts` records the WebSocket request and response by request ID. When `Page.webSocketOpened` arrives, `_onWebSocketOpened()` requires both records to exist:

```ts
const request = this._webSocketRequests.get(event.requestId);
assert(request);

const response = this._webSocketResponses.get(event.requestId);
assert(response);
```

The records are populated through `FFNetworkManager`.

In `packages/playwright-core/src/server/firefox/ffNetworkManager.ts`, `_onRequestWillBeSent()` resolves the event's `frameId` to a Playwright frame and returns immediately when no frame is found. The WebSocket-specific branch comes after that return:

```ts
const frame = event.frameId
  ? this._page._page.frameManager.frame(event.frameId)
  : null;

if (!frame)
  return;

if (event.cause === 'TYPE_WEBSOCKET') {
  this._webSocketRequestIds.add(event.requestId);
  this._page._onWebSocketRequestWillBeSent(
    event.requestId,
    event.url,
    event.headers,
  );
  return;
}
```

A dedicated worker request can have no corresponding page frame at this boundary. The early return therefore drops the WebSocket upgrade request before `_webSocketRequests` is populated. Firefox still emits `Page.webSocketOpened`, so `_onWebSocketOpened()` receives an event for an unregistered request and the assertion terminates the session.

## Root cause

The network event handler applies a page-frame requirement before classifying WebSocket traffic.

That requirement is valid for ordinary `network.Request` construction because Playwright needs a concrete frame. It is not required for the temporary WebSocket request/response bookkeeping, which only stores the request ID, URL, headers and handshake response until the later page-level WebSocket event arrives.

The effective failure path is:

```text
worker starts WebSocket upgrade
        |
        v
Network.requestWillBeSent
        |
        +-- frame lookup returns null
        +-- handler returns before TYPE_WEBSOCKET branch
        |
        v
Page.webSocketOpened arrives
        |
        +-- request map has no entry
        +-- assert(request) throws
        +-- Firefox page session is detached
```

## Focused fix path

Handle `TYPE_WEBSOCKET` before the frame lookup in `FFNetworkManager._onRequestWillBeSent()`:

```ts
_onRequestWillBeSent(event: Protocol.Network.requestWillBeSentPayload) {
  if (event.cause === 'TYPE_WEBSOCKET') {
    this._webSocketRequestIds.add(event.requestId);
    this._page._onWebSocketRequestWillBeSent(
      event.requestId,
      event.url,
      event.headers,
    );
    return;
  }

  const redirectedFrom = event.redirectedFrom
    ? this._requests.get(event.redirectedFrom) || null
    : null;
  const frame = redirectedFrom
    ? redirectedFrom.request.frame()
    : event.frameId
      ? this._page._page.frameManager.frame(event.frameId)
      : null;

  if (!frame)
    return;

  // existing HTTP request handling
}
```

This keeps normal HTTP request creation frame-bound while allowing worker-originated WebSocket handshakes to reach the dedicated WebSocket bookkeeping path.

A defensive guard in `_onWebSocketOpened()` could prevent a process-level crash when protocol events are incomplete, but silently ignoring the event would lose WebSocket request and response metadata. Reordering the classification is the primary fix; defensive handling is secondary hardening.

## Tests to add

A regression test should:

1. run only for Firefox;
2. start a minimal local WebSocket upgrade server;
3. navigate a page to the local origin;
4. create a dedicated worker that opens a WebSocket;
5. wait for the worker to report the `open` event;
6. verify that the page remains usable after the handshake;
7. optionally assert that the page-level WebSocket event receives the expected URL.

A focused unit test for `FFNetworkManager` can also send a synthetic `Network.requestWillBeSent` event with:

- `cause: 'TYPE_WEBSOCKET'`;
- a request ID and headers;
- a `frameId` that does not resolve to a page frame.

The assertion should confirm that `_onWebSocketRequestWillBeSent()` is still called and the request ID is tracked.

## Draft public diagnostic comment

```markdown
I traced the missing request entry one step earlier than `_onWebSocketOpened()`.

In `FFNetworkManager._onRequestWillBeSent()`, Playwright resolves `event.frameId` to a page frame and returns when no frame exists *before* it checks `event.cause === 'TYPE_WEBSOCKET'`. A dedicated worker's upgrade request can therefore be dropped at that early return, even though Firefox later emits `Page.webSocketOpened` for the same request ID.

That produces the reported sequence: the browser completes the handshake, `_webSocketRequests` was never populated, and `FFPage._onWebSocketOpened()` fails at `assert(request)`.

The narrow fix is to move the `TYPE_WEBSOCKET` branch ahead of the frame lookup. Ordinary HTTP request construction should remain frame-bound, but the WebSocket bookkeeping path only needs the request ID, URL and headers. I would add a Firefox regression test with a dedicated worker plus a unit test where a WebSocket `requestWillBeSent` event has no resolvable page frame.

A null guard in `_onWebSocketOpened()` would prevent the process crash, but it would only mask the dropped request metadata. Reordering the event classification addresses the source of the mismatch.
```

## Why this is a strong portfolio case

This issue demonstrates commercially useful debugging skills:

- reading a browser-protocol trace rather than stopping at the user-facing error;
- following state across two event handlers and two source files;
- identifying an event-ordering and classification boundary;
- separating the primary fix from defensive crash hardening;
- designing both an end-to-end browser regression test and a deterministic unit test;
- keeping the proposed patch limited to Firefox WebSocket request handling.
