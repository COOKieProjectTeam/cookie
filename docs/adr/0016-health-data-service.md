# ADR 0016 — Health data, nutrition assessment and goal acceptance

Status: accepted for the Health implementation.

Health owns weight, daily activity and recommendation policy. User owns profile,
onboarding, immutable accepted nutrition and drinking-water targets, and monthly
cheat days. Nutrition owns diary facts. Progress will project those facts and
effective goals. Notification consumes versioned snapshots.

This supersedes the synchronous User → Health dependency in ADR 0015. User
locally commits an attempt and an outbox command, then returns HTTP 202. Health
verifies the preview receipt and writes its execution, first weight (initial
attempts only), inbox and result together. User commits the result, profile,
accepted goal and inbox together. Broker ACK follows commit with at-least-once
delivery. A pending attempt leaves the previous goal active. A valid attempt
can complete without a calorie goal. Only User can write its goal history.

## Assessment policy

Use the [adult EER 2023 equations][eer], with age in completed years, height
in centimetres and weight in kilograms. The four categories are INACTIVE,
LOW_ACTIVE, ACTIVE and VERY_ACTIVE. People under 19 or outside the standard
adult policy receive no calorie goal. No clinical BMI gate is defined here.

For 30 years, 170 cm and 70 kg, unrounded results in category order are
2520.17, 2713.37, 2902.02, 3148.62 (male) and 2166.70, 2337.27, 2475.55,
2722.63 (female). Use decimal arithmetic. Add −275, 0 or +275 kcal/day for
−0.25, 0 or +0.25 kg/week, then round half up to the integer centre. The
inclusive range is centre ±100 kcal. The lower bound must be at least 1500.
At EER 1875 the loss range is 1500–1700. At EER 1800 loss is unavailable,
but the user can separately preview and accept maintenance at 1700–1900.

Product presets are BALANCED 20/30/50 and HIGH_PROTEIN 25/30/45 percentages
for protein/fat/carbohydrate. Use 4/9/4 kcal per gram. LOW_CARB caps
carbohydrates at min(20% of centre divided by 4, 120 g), with 25% protein.
KETO caps carbohydrates at 30 g with 20% protein. Fat uses remaining energy
at each calorie edge. Neither cap implies a minimum. Round grams half up
to two decimal places at output. Fibre guidance is [14 g per 1000 kcal][dri]
rounded half up to whole grams. Default plain drinking water is 1500 mL;
acceptance does not replace an existing water goal.

## Observable invariants

- A preview always uses the authenticated account and creates no measurement,
  goal or business message. The signed receipt binds account, purpose, input,
  result, revision, policy and date. Expiry is at most 30 minutes and no later
  than local midnight. Clients cannot supply accepted nutrition numbers.
- Repeated initial execution creates one first weight. Recalculation never
  creates a first weight. User serializes concurrent acceptances per account.
- The import writer must present the current generation and the next sequential
  packet, or exactly replay a committed packet. An invalid item rolls back its
  entire packet. The external record key is scoped to its connection.
- One primary connection contributes to each local activity day. New choices
  apply prospectively and historical days retain their original assignment.
- The Identity deletion request erases owned data and leaves an append-only
  tombstone. HTTP and inbox share the same account lock. Valid previously
  issued JWTs may still work before the deletion message arrives. Recovery
  outside broker retention requires an authorized reconciliation before ingress.
- Only CHEAT_DAY overrides a date and at most one exists per calendar month.
  Notification uses inclusive calorie bounds. It suppresses calorie warnings
  for unknown consumption, a missing goal or CHEAT_DAY.
- Addressed assessment messages use recipient-specific encryption. Logs,
  traces, metrics and quarantine exclude health inputs and message bodies.

## Compatibility

Keep previously stored point goals as legacy history and preserve manual
hydration and cheat days. The old activity categories cannot be mapped to
EER categories. Old numeric overrides cease to be effective. Deploy consumers
before producers, retain the original outbox event versions, and use a
coordinated writer cutover for the changed User API. After new writes, use a
forward repair or coordinated restore, with an explicit recovery point.

The Miro Health frame still labels its API “Progress API” and shows
`account.deletion.completed`. This service owns the Health API. ADR 0011
governs the deletion lifecycle and defines only the request event.

[eer]: https://www.canada.ca/en/health-canada/services/food-nutrition/healthy-eating/dietary-reference-intakes/tables/equations-estimate-energy-requirement.html
[dri]: https://www.canada.ca/en/health-canada/services/food-nutrition/healthy-eating/dietary-reference-intakes/tables/reference-values-macronutrients.html
