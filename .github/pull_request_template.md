## Change

Describe the concrete problem and resulting behavior.

## Verification

List commands/tests actually run, results and the reviewed revision. Identify
failed, skipped or unavailable checks; link CI for the current PR head.

For new services or changes to behavioral guarantees, follow the repository
workflow at `.agents/skills/service-review/SKILL.md`:

- Link the maintained service invariants and record the evidence below.
- Include affected contracts, schema/locks/permissions and failure paths even
  when their files did not change.
- Include independent review coverage, remaining findings and unverified areas.
  Mark irrelevant checks as not applicable with a reason.

| Invariant | Implementation path | Test/assertion or command | Result and revision | Unverified area |
| --- | --- | --- | --- | --- |

For a confirmed defect fix, identify the regression that fails before the fix
and passes after it, or explain why that demonstration is unavailable.
