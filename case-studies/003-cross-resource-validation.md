# Case Study 003: Cross-Resource Validation

## Symptom

An API authorizes access to one parent resource but accepts a child resource identifier from the request body without checking that the child belongs to the parent.

## Root cause pattern

Authorization and relationship validation are treated as the same thing. They are separate checks.

## Fix direction

After authorization, validate that every submitted child identifier belongs to the authorized parent before changing state.

## Prevention

Add regression tests for cross-parent misuse, not just valid-path updates.
