#!/usr/bin/env python3
"""Validate evidence fields for newly published case studies."""

from __future__ import annotations

import re
import sys
from pathlib import Path


REQUIRED_HEADINGS = (
    "## Contribution status",
    "## User-visible failure",
    "## Reproduction",
    "## Root cause",
    "## Implementation",
    "## Verification",
    "## Upstream outcome",
    "## Engineering lesson",
)

ISSUE_RE = re.compile(
    r"^- Upstream issue: https://github\.com/([^/]+)/([^/]+)/issues/(\d+)$",
    re.MULTILINE,
)
INTENT_RE = re.compile(
    r"^- Intent comment: https://github\.com/([^/]+)/([^/]+)/issues/(\d+)#issuecomment-(\d+)$",
    re.MULTILINE,
)
PR_RE = re.compile(
    r"^- Upstream PR: https://github\.com/([^/]+)/([^/]+)/pull/(\d+)$",
    re.MULTILINE,
)
DATE_FIELDS = (
    "Checked issue comments",
    "Checked open and closed PRs",
)
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
ALLOWED_PR_STATES = {"open", "merged"}
ALLOWED_CLASSIFICATIONS = {
    "upstream contribution in progress",
    "merged upstream contribution",
}


def field(text: str, name: str) -> str | None:
    match = re.search(rf"^- {re.escape(name)}: (.+)$", text, re.MULTILINE)
    return match.group(1).strip() if match else None


def validate(path: Path) -> list[str]:
    errors: list[str] = []
    text = path.read_text(encoding="utf-8")

    for heading in REQUIRED_HEADINGS:
        if heading not in text:
            errors.append(f"missing heading: {heading}")

    issue = ISSUE_RE.search(text)
    intent = INTENT_RE.search(text)
    pull_request = PR_RE.search(text)
    if not issue:
        errors.append("missing canonical '- Upstream issue:' GitHub URL")
    if not intent:
        errors.append("missing canonical '- Intent comment:' GitHub issue-comment URL")
    if not pull_request:
        errors.append("missing canonical '- Upstream PR:' GitHub URL")

    if issue and intent and issue.groups() != intent.groups()[:3]:
        errors.append("intent comment must belong to the upstream issue")
    if issue and pull_request and issue.groups()[:2] != pull_request.groups()[:2]:
        errors.append("upstream issue and pull request must belong to the same repository")

    for name in DATE_FIELDS:
        value = field(text, name)
        if value is None or not DATE_RE.fullmatch(value):
            errors.append(f"{name} must use YYYY-MM-DD")

    if field(text, "Assignment status") != "unassigned":
        errors.append("Assignment status must be 'unassigned' when the candidate is selected")
    if field(text, "Reproduction") != "confirmed":
        errors.append("Reproduction must be 'confirmed' before publication")

    pr_state = field(text, "PR state at publication")
    if pr_state not in ALLOWED_PR_STATES:
        errors.append("PR state at publication must be 'open' or 'merged'")

    ai_disclosure = field(text, "AI assistance disclosed upstream")
    if ai_disclosure not in {"yes", "not used"}:
        errors.append("AI assistance disclosed upstream must be 'yes' or 'not used'")

    classification = field(text, "Portfolio classification")
    if classification not in ALLOWED_CLASSIFICATIONS:
        errors.append(
            "Portfolio classification must be 'upstream contribution in progress' "
            "or 'merged upstream contribution'"
        )
    if pr_state == "open" and classification != "upstream contribution in progress":
        errors.append("an open PR must be classified as an upstream contribution in progress")
    if pr_state == "merged" and classification != "merged upstream contribution":
        errors.append("a merged PR must be classified as a merged upstream contribution")

    if re.search(r"\b(?:TODO|TBD)\b|<[^>]+>|OWNER/REPOSITORY|YYYY-MM-DD", text):
        errors.append("template placeholders must be replaced")
    if text.count("```console") < 2:
        errors.append("reproduction and verification must each include a console command block")

    return errors


def main(argv: list[str]) -> int:
    if not argv:
        print("No new case-study README files to validate.")
        return 0

    failed = False
    for raw_path in argv:
        path = Path(raw_path)
        if path.name != "README.md" or path.parent.parent.name != "case-studies":
            print(f"SKIP {path}: not a numbered case-study README")
            continue
        if not path.is_file():
            print(f"FAIL {path}: file does not exist")
            failed = True
            continue
        errors = validate(path)
        if errors:
            failed = True
            print(f"FAIL {path}")
            for error in errors:
                print(f"  - {error}")
        else:
            print(f"PASS {path}")

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

