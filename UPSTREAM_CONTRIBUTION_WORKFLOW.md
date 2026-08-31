# Upstream Contribution Workflow

This repository separates private candidate research from public contribution evidence. A diagnosis is useful, but a case study is not presented as an upstream contribution until an implementation exists in an upstream pull request.

## Required sequence

```text
Search issue
-> read every comment
-> search open and closed pull requests
-> announce intent
-> reproduce
-> implement and test
-> open upstream pull request
-> create portfolio case study
```

Do not reorder the last two steps. Draft notes belong outside `case-studies/` until the upstream pull request exists.

## 1. Qualify the candidate

Record all of the following before announcing work:

- canonical issue URL and current state;
- issue author, assignees, labels, and milestone;
- every issue comment, including recent claims of intent;
- every open and closed pull request referencing the issue;
- repository contribution guide, pull-request template, and AI policy;
- evidence that the issue is reproducible and appropriately scoped.

Reject the candidate when another contributor is actively implementing it, an equivalent pull request already exists, the fix belongs to a private backend, or the reproduction is too weak to verify.

## 2. Announce intent

Post a concise, human-owned comment only when the repository welcomes claim comments. State that you plan to reproduce and work on the issue. Do not publish an AI-generated diagnosis merely to establish visibility.

Save the comment URL. If maintainers explicitly discourage intent comments, record that policy instead and proceed according to their guidance.

## 3. Reproduce first

Reproduce against the upstream repository's current default branch and pin the tested commit. Capture:

- environment and dependency versions;
- exact setup and failing command;
- expected and actual results;
- smallest reliable reproducer;
- a control case demonstrating that the harness itself works.

No reproduction means no implementation claim.

## 4. Implement independently

Create a topic branch from current upstream. Keep the patch focused, add a regression test that fails before the fix, and avoid copying another contributor's abandoned patch without attribution or maintainer direction.

Follow the upstream project's formatting, changelog, authorship, and AI-disclosure rules. AI assistance never replaces personal review or the ability to explain every changed line.

## 5. Verify

Run the narrow regression test, adjacent package or module tests, formatting and static analysis, and the broadest practical suite. Record commands and outcomes, including unrelated failures and how they were classified.

## 6. Open and maintain the upstream pull request

The pull request must link the issue, explain the root cause and behavioral contract, include verification evidence, and disclose AI assistance when required. Respond to review personally and keep the branch current until merged or explicitly declined.

## 7. Publish the case study

Only now copy `case-studies/CASE_STUDY_TEMPLATE.md` into a new numbered directory. The entry must link the upstream issue, intent comment, and pull request. Classify it accurately:

- `upstream contribution in progress` while the pull request is open;
- `merged upstream contribution` after merge.

If a pull request is closed without merge, update the case study immediately. Do not describe issue comments, private experiments, or speculative patches as upstream contributions.

## Candidate record

Use this private working checklist before adding a case study:

```text
Issue URL:
Default-branch commit checked:
All comments read at:
Open PR search performed at:
Closed PR search performed at:
Assignee/claim status:
Contribution guide read:
AI policy read:
Intent comment URL:
Reproduction command:
Reproduction result:
Regression test:
Implementation branch:
Verification commands:
Upstream PR URL:
```

