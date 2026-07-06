# Case Study 003: Cross-Resource Validation

## Symptom

An API authorizes access to one parent resource but accepts a child resource identifier from the request body without checking that the child belongs to the parent.

## Reproduction path

Use a valid account for parent A. Submit a request for parent A while passing a child identifier that belongs to parent B.

## Root cause pattern

Authorization and relationship validation are treated as the same thing. They are separate checks.

## Fix direction

After authorization, validate that every submitted child identifier belongs to the authorized parent before changing state.

## Verification

A cross-parent request should fail without updating state. A valid parent-child request should continue to work.

## Prevention

Add regression tests for cross-parent misuse, not just valid-path updates.

## Engineering lesson

Access control must include both user authorization and object relationship validation.
