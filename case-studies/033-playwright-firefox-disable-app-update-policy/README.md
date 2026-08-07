# Case Study 033 — Playwright Firefox: automatic updater lock race survives preference-based disabling

## Incident

Parallel Playwright Firefox launches can fail with an error such as:

```text
ENOENT: no such file or directory, stat '/ms-playwright/firefox-1509/firefox/lock'
```

Upstream issue: [microsoft/playwright#41356](https://github.com/microsoft/playwright/issues/41356)

The reporter traced the shared `lock` file to Firefox's automatic update service. Multiple Firefox processes using the same installed browser can contend over that install-level updater lock, and one process can remove the lock while another is checking it.

The important observation is that Playwright already attempts to disable Firefox updates with preferences, yet current Firefox still creates `UpdateService` and the shared update lock.

## Source-backed boundary

Playwright's Firefox defaults currently include several updater preferences:

```js
pref("app.update.enabled", false);
pref("app.update.checkInstallTime", false);
pref("app.update.disabledForTesting", true);
pref("app.update.auto", false);
pref("app.update.mode", 0);
pref("app.update.service.enabled", false);
```

However, the reporter instrumented Firefox's update service and showed that it still reports itself able to check and apply updates.

The same report provides a strong discriminator: supplying a Firefox enterprise policy containing:

```json
{
  "policies": {
    "DisableAppUpdate": true
  }
}
```

prevents the update lock and update log from appearing.

Playwright already exposes a supported custom-policy hook in its patched Firefox preferences:

```js
pref(
  "browser.policies.alternatePath",
  getenv("PLAYWRIGHT_FIREFOX_POLICIES_JSON") || ""
);
```

and the launcher test suite already verifies that a user-provided policy file is honored.

## Root cause

The failure is not primarily a missing retry around `stat(lock)`.

The deeper lifecycle problem is that Playwright assumes legacy update preferences are sufficient to make its bundled Firefox effectively non-updating. In current Firefox, updater eligibility is governed by the update service and enterprise-policy state strongly enough that those preferences no longer establish the required invariant.

That leaves an install-global updater lock active inside a browser distribution intentionally launched many times in parallel by test runners.

The invariant Playwright needs is stronger:

```text
A Playwright-managed Firefox launch must not start the browser updater.
```

A retry around the lock race would only hide one symptom while leaving background update activity and cross-process mutation of the installed browser in place.

## Small PR candidate

### Likely fix direction

Make `DisableAppUpdate: true` part of Playwright's default Firefox policy rather than adding another updater preference.

The implementation needs to preserve the existing custom-policy capability. A user's `PLAYWRIGHT_FIREFOX_POLICIES_JSON` file must not accidentally remove Playwright's mandatory updater-disable policy.

Two viable designs are:

1. ship a Playwright default enterprise-policy file with `DisableAppUpdate: true` and merge user policies into that effective policy; or
2. enforce the update-disable policy in the patched browser layer while continuing to use `browser.policies.alternatePath` only for user-supplied additions.

The exact packaging hook should be selected after inspecting the Firefox patch/build pipeline. The important constraint is that this must be an enterprise-policy-level disable, not another preference-level approximation.

## Regression matrix

Add coverage for:

1. A default Playwright Firefox launch has application updates disabled.
2. Two or more Firefox processes can remain alive concurrently without creating the install-level updater lock.
3. Waiting long enough for the updater's normal startup/check interval still does not create that lock.
4. User-supplied `PLAYWRIGHT_FIREFOX_POLICIES_JSON` policies continue to work.
5. A custom policy such as certificate installation is combined with the mandatory updater-disable behavior rather than replacing it.
6. Persistent and non-persistent launches share the same updater-disable invariant.
7. Linux container coverage exercises the shared installed-browser path used by the original report.

A deterministic assertion on updater state is preferable to relying only on the rare `ENOENT` race. The parallel-launch stress case should remain as an end-to-end regression.

## Verification

Relevant existing test area:

```text
tests/library/firefox/launcher.spec.ts
```

The current suite already contains a custom Firefox policy test, so the new regression should extend that boundary rather than inventing a separate policy harness.

Before submission, run the focused Firefox launcher tests and the repository's browser-patch validation required for changes to patched Firefox defaults.

## Diagnostic comment draft

> I traced this to the updater-disable boundary rather than the `stat(lock)` call itself.
>
> Playwright's Firefox defaults already set `app.update.enabled=false`, `app.update.disabledForTesting=true`, `app.update.auto=false`, and related preferences, but the reporter's update-service logging shows current Firefox still starts `UpdateService` and creates the install-level lock. Their `DisableAppUpdate` enterprise-policy experiment is the useful discriminator: with that policy active, the lock and update log disappear.
>
> I would make updater disablement a default Playwright Firefox policy invariant instead of adding another preference or retrying the lock race. The implementation should preserve `PLAYWRIGHT_FIREFOX_POLICIES_JSON` by merging user policy with the mandatory `DisableAppUpdate: true` behavior rather than allowing a custom policy file to replace it.
>
> A focused regression can reuse `tests/library/firefox/launcher.spec.ts`: verify the effective updater-disabled state, keep two Firefox processes alive long enough for the normal update-service startup window, and assert that the shared browser-install `lock` is never created. The existing custom-policy certificate test should also continue to pass.

## Status

- Root-cause boundary is supported by the issue's updater instrumentation and current Playwright Firefox configuration.
- No matching open upstream PR was found.
- No public upstream comment was posted.
- No patch or upstream test run is claimed here.
