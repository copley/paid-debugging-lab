# Case Study 009 — Playwright client-certificate proxy caches a rejected TLS-upgrade promise

## Upstream issue

- Repository: `microsoft/playwright`
- Issue: `#41765`
- Area: TypeScript, TLS, SOCKS proxying, client certificates, asynchronous state

## Symptom

Playwright's client-certificate proxy can permanently poison one `SocksProxyConnection` after a transient failure while upgrading the browser-facing socket to TLS.

The first TLS-upgrade attempt rejects. A later recovery path calls the same helper again to complete the browser-side handshake and return a diagnostic HTTP 503 response, but it receives the already-rejected promise instead of making a fresh attempt.

## Source-level diagnosis

`SocksProxyConnection._upgradeToTLSIfNeeded()` stores the in-flight operation in `_brorwserDecrypted` using nullish assignment:

```ts
this._brorwserDecrypted ??= new Promise<tls.TLSSocket>((resolve, reject) => {
  const dummyServer = tls.createServer(/* ... */);
  dummyServer.emit('connection', socket);
  dummyServer.once('secureConnection', tlsSocket => {
    dummyServer.close();
    resolve(tlsSocket);
  });
  dummyServer.once('error', error => {
    dummyServer.close();
    reject(error);
  });
});
return this._brorwserDecrypted;
```

Caching is correct while the promise is pending and after it resolves: concurrent callers should share one TLS upgrade and successful callers should reuse the resulting socket.

The missing state transition is rejection. A rejected promise remains truthy, so every subsequent call returns the same terminal failure. The connection never gets another chance to execute the recovery path.

## Why a simple `??=` replacement is insufficient

Removing caching entirely would allow concurrent calls to create multiple dummy TLS servers over the same duplex socket. That risks duplicate listeners, competing reads and a second class of race condition.

The intended state machine is:

```text
undefined -> pending promise -> resolved promise
                         \
                          -> rejection -> undefined
```

Only the rejected state should be evicted.

## Focused fix path

Keep a stable local reference to the cached promise and clear the field only when that exact promise rejects:

```ts
private async _upgradeToTLSIfNeeded(
  socket: stream.Duplex,
  alpnProtocol: string | false | null
): Promise<tls.TLSSocket> {
  if (!this._brorwserDecrypted) {
    this._brorwserDecrypted = new Promise<tls.TLSSocket>((resolve, reject) => {
      const dummyServer = tls.createServer({
        ...dummyServerTlsOptions,
        ALPNProtocols: [alpnProtocol || 'http/1.1'],
      });
      dummyServer.emit('connection', socket);
      dummyServer.once('secureConnection', tlsSocket => {
        dummyServer.close();
        resolve(tlsSocket);
      });
      dummyServer.once('error', error => {
        dummyServer.close();
        reject(error);
      });
    });
  }

  const upgrade = this._brorwserDecrypted;
  try {
    return await upgrade;
  } catch (error) {
    if (this._brorwserDecrypted === upgrade)
      this._brorwserDecrypted = undefined;
    throw error;
  }
}
```

The identity check matters. If a future refactor starts another attempt before an older caller handles rejection, the older caller must not clear the newer promise.

## Regression tests

The smallest useful test should prove all three cache semantics:

1. **Pending deduplication:** two concurrent calls share one `tls.createServer` attempt.
2. **Success caching:** a later call returns the resolved TLS socket without constructing another server.
3. **Failure eviction:** the first attempt rejects, the next call creates a second server and can succeed.

A higher-level client-certificate test should also confirm that a recoverable upstream TLS error does not leave the browser navigation hanging and that the browser receives the existing diagnostic response path.

## Risk notes

Retry is only safe when the failed upgrade has not irreversibly consumed or corrupted the browser-side TLS stream. The regression test should therefore reproduce the actual failure boundary being fixed rather than merely rejecting an arbitrary mocked promise after bytes have been consumed.

If the underlying socket is no longer reusable, the correct behavior is to destroy the connection promptly and surface the failure—not to retain a rejected promise and hang later callers.

## Draft upstream diagnostic comment

I confirmed the cached-promise boundary in `SocksProxyConnection._upgradeToTLSIfNeeded()`.

The cache is useful for pending and successful upgrades, but a rejected promise becomes a permanent terminal state because `_brorwserDecrypted` remains truthy. The second call from the server-error recovery path therefore cannot perform another upgrade attempt.

I would preserve deduplication and evict only the failed promise. A robust pattern is to capture the cached promise in a local variable, `await` it, and in `catch` clear `_brorwserDecrypted` only when it still points to that same promise. The identity guard prevents an older rejection handler from clearing a newer attempt.

Regression coverage should verify pending-call deduplication, successful reuse and rejection eviction. The failure test should occur before the dummy TLS server irreversibly consumes the socket; otherwise retrying the same stream may not be valid and the correct fallback would be prompt connection teardown.

## Commercial relevance

This is representative of production debugging work where the visible symptom is a browser hang, but the root cause is an incomplete asynchronous state machine: an in-flight promise is treated as a permanent cache entry even when it rejects.
