# Case Study 013: Poetry request-timeout import crash

## Incident

A malformed `POETRY_REQUESTS_TIMEOUT` value can prevent Poetry from starting, even for a command that does not make an HTTP request:

```bash
POETRY_REQUESTS_TIMEOUT=abc python -m poetry --version
```

The process fails during module import with:

```text
ValueError: invalid literal for int() with base 10: 'abc'
```

Upstream issue: [python-poetry/poetry#10969](https://github.com/python-poetry/poetry/issues/10969)

## Boundary isolation

The failure occurs before command dispatch:

```text
Poetry process starts
  -> modules import poetry.utils.constants
  -> module evaluates int(POETRY_REQUESTS_TIMEOUT)
  -> invalid value raises ValueError
  -> CLI never reaches command selection
```

The current constant is evaluated eagerly:

```python
REQUESTS_TIMEOUT = int(os.getenv("POETRY_REQUESTS_TIMEOUT", 15))
```

This creates two coupled defects:

1. an HTTP-only setting can break unrelated commands such as `poetry --version`;
2. the user receives a raw Python conversion traceback rather than a Poetry-facing configuration error.

## Existing configuration path

Poetry already normalizes environment-backed settings through `Config.get()`. Integer settings such as `requests.max-retries` are passed through `int_normalizer` at the configuration boundary.

`POETRY_REQUESTS_TIMEOUT` bypasses that mechanism and is imported as a process-wide constant. The timeout is then used by request code in repositories, authentication, publishing, and helper functions.

## Smallest safe fix direction

The smallest behavior-preserving patch is to make timeout parsing lazy:

```python
def get_requests_timeout() -> int:
    value = os.getenv("POETRY_REQUESTS_TIMEOUT", "15")
    try:
        return int(value)
    except ValueError as error:
        raise ValueError(
            "POETRY_REQUESTS_TIMEOUT must be an integer number of seconds"
        ) from error
```

Request call sites would import and call `get_requests_timeout()` only when constructing or sending an HTTP request.

This changes the lifecycle from:

```text
parse at import time
```

into:

```text
parse when an HTTP timeout is actually required
```

A more complete follow-up could add `requests.timeout` to `Config.default_config`, register it with `int_normalizer`, and route request clients through their existing `Config` objects. That would unify the setting with Poetry's normal configuration system, but it touches more call paths and should be treated as a separate design decision.

## Regression tests

A focused patch should cover:

1. `POETRY_REQUESTS_TIMEOUT=abc poetry --version` does not fail during import;
2. an HTTP operation with the invalid value fails with a concise message naming the variable;
3. an unset value uses the existing 15-second default;
4. a valid integer value is preserved;
5. timeout parsing is not performed merely by importing `poetry.utils.constants`.

Potential test locations:

```text
tests/console/test_application.py
tests/utils/test_helpers.py
tests/repositories/test_http_repository.py
```

The exact HTTP test should patch the request layer so it verifies the timeout argument without performing network I/O.

## Diagnostic comment

```text
I traced this to an eager configuration conversion rather than to command execution.

`poetry.utils.constants` evaluates `int(os.getenv("POETRY_REQUESTS_TIMEOUT", 15))` at module import time. Because request-related modules are imported during normal CLI startup, a malformed HTTP timeout aborts unrelated commands before Poetry has selected or executed the command.

A narrow fix is to replace the eager constant with a lazy accessor and call it only at HTTP request boundaries. That preserves the current environment variable and 15-second default while allowing commands such as `poetry --version` to start normally.

The accessor should convert the value and raise a concise error naming `POETRY_REQUESTS_TIMEOUT` when an HTTP operation actually needs it. Regression coverage should verify that importing Poetry and running `--version` do not parse the timeout, while the first request path does.

A larger follow-up could add `requests.timeout` to `Config.default_config` and `int_normalizer`, but that is a broader configuration refactor rather than a prerequisite for fixing the import-time crash.
```

## Commercial debugging signal

This incident demonstrates lifecycle debugging: the visible command is not the failing operation. The root cause is an eager side effect during module import, and the useful fix is to move validation to the boundary where the value is consumed.