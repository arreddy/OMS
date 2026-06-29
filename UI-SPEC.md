# Order Management System — UI Specification

Companion to `SPEC.md`. Covers three surfaces: **Ops Console** (back-office), **Customer Portal** (order tracking), **Admin Console** (order-type / workflow config).

---

## 1. Screen Inventory

| # | Screen | Surface | Backing entities |
|---|---|---|---|
| 1 | Order list / search | Ops | `order` |
| 1a | Order create | Ops | `order`, `order_line` |
| 2 | Order detail | Ops | `order`, `order_line`, `workflow_instance` |
| 3 | Task queue | Ops | `task` |
| 4 | Task detail (approve/reject) | Ops | `task`, `task_comment`, `workflow_transition_log` |
| 5 | Order tracking | Customer | `order`, `workflow_transition_log` (filtered/public subset) |
| 6 | Order type list | Admin | `order_type` |
| 7 | Order type editor (schema builder) | Admin | `order_type.attribute_schema` |
| 8 | Workflow designer | Admin | `workflow_definition/state/transition` |

---

## 2. Ops Console

### 2.1 Order list / search

**Layout:** left filter rail (220px) + main table, full width.

| Filter rail | Table columns |
|---|---|
| Order type (multi-select) | Order # · Type · Status (badge) · Customer ref · Total · Created · Updated |
| Status (multi-select, grouped by category — see §6 badge mapping) | |
| Date range (created) | |
| Customer ref (text) | |
| Has open task (toggle) | |

- Status badge color encodes workflow category: gray = WAIT, blue = AUTOMATIC, amber = MANUAL, green = terminal-success, red = terminal-failure/cancelled. This mapping is config-driven from `workflow_state.state_type` + `is_terminal` + `terminal_outcome`, not hardcoded per order type. **Color is never the only cue** — every badge also renders its status text (the raw `workflow_state.code` for Ops); color alone fails for colorblind users (WCAG 1.4.1), so it's a reinforcing signal, not the sole one.
- The status filter spans every order type, but a status code only has a category in the context of *some* order type's workflow (`order.status` is a literal code, not a category, and the category mapping lives on `workflow_state`). Populate the grouped multi-select from `GET /order-types/status-taxonomy`, which returns the distinct status codes in use across all active order types' workflows together with each one's badge category — there's no other way to build this filter without hardcoding category-per-code in the frontend.
- Row click → Order detail (Screen 2).
- Empty state: "No orders match these filters" + "Clear filters" link.
- Bulk actions: none in v1 (manual steps are per-order via task, not batch).
- "New order" button, top-right → Screen 1a.

### 2.1a Order create

**Layout:** single column form.

Originally scoped out of v1 on the assumption that orders only ever arrive
from an upstream system (checkout, B2B intake) calling the API directly —
revisited because ops needs a way to create orders by hand too (phone
orders, customer-service-initiated orders, manual testing). Both paths are
real now: the API doesn't distinguish who calls it, and this screen is just
another caller.

- **Core fields**: order type (select, populated from `GET /order-types`),
  customer ref, currency, total amount.
- **Attributes**: rendered from the selected order type's `attribute_schema`
  — one input per property, widget chosen by JSON Schema `type`/`format`
  (`enum` → select, `boolean` → checkbox, `number`/`integer` → number input,
  `format: date` → date input, otherwise text), labeled by `title` and
  marked `*` if listed in `required`. This is the same schema-driven
  rendering principle as Zone D's read-only attribute display on Order
  Detail, just editable — a new order type never needs a hand-written form
  here either (SPEC.md §3.3).
- **Lines**: optional, repeatable (item ref, quantity, unit price, plus the
  same schema-driven attributes block using `line_attribute_schema`). Orders
  can be created with zero lines.
- Required-field validation is server-side only (the same `400` + violation
  list every other form in this app relies on) — this screen doesn't
  duplicate JSON Schema's `required` semantics into client-side checks.
- On success, navigates straight to Screen 2 for the new order.

### 2.2 Order detail

**Layout:** 3 stacked zones, no tabs (everything visible on scroll — ops users need full context without clicking around).

**Zone A — Header band**
Order # (mono), type, status badge, customer ref, total, created/updated timestamps. Top-right: "View workflow history" link (scrolls to Zone C — Zone C is already rendered, this is a scroll anchor, not a reveal/toggle).

**Zone B — Lines table**
Line # · Item ref · Qty · Unit price · Line total · Status. Inline edit only for fields the current workflow state's `state_type` permits (read-only once order is in a terminal state).

**Zone C — Workflow panel**
- Horizontal state-progress strip (visited states filled, current state outlined, future states ghosted) — built from `workflow_definition` states in order.
- Below it: transition history table — From → To · Trigger · By · When · Comment. This list is unbounded in principle (a workflow with rework/retry loops can revisit the same state repeatedly), so render it in a fixed-height, internally-scrolling container rather than letting it grow the page indefinitely — paginate (or virtualize) past ~50 rows.
- If current state is `MANUAL` and has an open task: inline card "Awaiting: {task_type}, assigned to {assignee or assignee_group}" with a "Go to task" button → Screen 4.
- If current state is `WAIT`: inline note "Waiting for {trigger_code}" — no action available here, informational only.

**Zone D — Extension attributes**
Collapsed `<details>` block rendering `order.attributes` as a key-value list, keyed by `order_type.attribute_schema` field labels (not raw JSON keys) — this is what makes the screen survive new order types without a UI change.

### 2.3 Task queue

**Layout:** identical filter-rail + table pattern as Order list, for consistency.

| Filter rail | Table columns |
|---|---|
| Assignee group (queue) | Order # · Task type · Priority · SLA due (relative + color) · Status · Assignee |
| My tasks (toggle — assignee = current user) | |
| Status | |
| Priority | |

- SLA due column: green if >4h remaining, amber if <4h, red + "overdue" label if past due. Every state in this column carries a text label, not just color (green = "on track", amber = "due soon", red = "overdue") — same color-plus-text rule as §2.1's badges, scoped to this column so it doesn't collide with order-status reading.
- Row action buttons inline (no need to open detail for the common path): **Claim** (if unassigned and in my group), **Approve**, **Reject** — Approve/Reject open a small inline confirm with a required reason field for Reject, optional for Approve.
- Default sort: SLA due ascending.

### 2.4 Task detail

Opened from queue row or from Order detail's "Go to task" link.

**Layout:** single card.
- Header: task type, order # (link → Screen 2), status, SLA countdown.
- Context panel: pulls relevant `order.attributes` fields flagged as `show_in_task: true` in the order type's schema (e.g., credit review shows credit score, not shipping notes) — keeps the reviewer from having to cross-reference the full order.
- Action bar: **Claim** / **Assign to…** (user picker, group-scoped) / **Approve** / **Reject**.
  - Approve: optional comment field, primary button "Approve order".
  - Reject: **required** reason field, secondary-danger button "Reject order". Reason is stored in `decision_reason` and shown in the order's workflow history (Zone C above) so it's never a black box to ops or customer-facing support.
- Comment thread below action bar (`task_comment`), oldest-first, simple text + author + timestamp.
- Escalate button (ghost, top-right) — manual override, **required** reason field (server rejects a blank reason with `400`).

---

## 3. Customer Portal — Order Tracking

**Layout:** single column, mobile-first, max-width 640px.

- Order # + status as a plain-language label (not the raw `workflow_state.code`) — admin screen (§4.2) lets ops define a customer-facing label per state, separate from the internal code, so internal renames never break customer-facing copy.
- Vertical timeline (not horizontal strip — better for mobile): only states marked `is_customer_visible` in the workflow definition render here. Internal-only states (e.g., `CREDIT_REVIEW`) are skipped — the timeline shows the prior visible state until the order exits review, so no confusing "stuck" appearance.
- No transition history table, no attributes dump, no task info. Customers never see task queue concepts — only macro status. This is a hard boundary: same workflow data, deliberately narrower projection.
- If terminal+failed (e.g., `CANCELLED`): clear plain-language reason if one exists, plus a support contact link. Never expose internal `decision_reason` verbatim — ops can set a customer-facing message separately.

---

## 4. Admin Console

### 4.1 Order type list

Simple table: Code · Name · Active · Workflow version · Last updated. "New order type" button top-right.

### 4.2 Order type editor

**Layout:** form, 2 sections.

- **Core**: code, name, active toggle.
- **Schema builder**: repeatable row UI for `attribute_schema` fields — Field key · Label · Type · Required · Show in task (checkbox feeding §2.4 context panel) · Customer visible (checkbox). "Type" is one of the actual JSON Schema types — string / number / boolean / array / object — plus a separate **Format** control that only appears for `string` (e.g. `date`, `date-time`, `email`); "date" is not its own type, it's `{"type": "string", "format": "date"}`. Likewise, **enum** is a separate "Restrict to a fixed list of values" control available alongside any type (it's the JSON Schema `enum` keyword, not a type), not an entry in the Type dropdown. Underlying storage is still raw JSON Schema; this is a form over it, not a JSON text box, so non-engineers can extend the model.
- Save validates the schema is well-formed JSON Schema before allowing publish; does not retroactively touch existing orders' `attributes`.

### 4.3 Workflow designer

**Layout:** canvas (left, ~70%) + inspector panel (right, ~30%).

- Canvas: nodes = states (draggable boxes, colored by the same badge-category mapping as §6: blue=AUTOMATIC, amber=MANUAL, gray=WAIT — plus the node always shows its code as text, same color-is-reinforcement rule as §2.1), edges = transitions (labeled with `trigger_code`).
- Click a state node → inspector shows: code, state_type, is_initial/is_terminal toggles, and — only if MANUAL — task_type + default assignee_group + customer-facing label + `is_customer_visible` toggle. If `is_terminal` is on, an additional **outcome** toggle (success / failure) appears and is required — this drives the green/red badge split in §6 and the server rejects a publish where a terminal state has no outcome set.
- Click an edge → inspector shows: trigger_type, trigger_code, guard_expression (raw text field — this stays a power-user field, no visual builder in v1), side_effect identifier.
- **Publish** button: disabled until validation passes (every state reachable from initial, every non-terminal state has ≥1 outbound transition, MANUAL states have both an approve and reject path, every terminal state has an outcome set). This mirrors server-side validation — the API rejects a non-conforming graph with `400` regardless of what the UI allows, so a direct API call can't bypass it either. Publishing creates a new `workflow_definition` version and does not affect in-flight `workflow_instance` rows — banner reminds the admin of this every time: "Existing in-flight orders stay on the current version."
- Version history dropdown (top of inspector): view/diff prior published versions, read-only.

---

## 5. Cross-Surface Rules

- **Same status taxonomy, three projections.** Ops sees raw `workflow_state.code` + full history. Customers see a curated label + filtered timeline. Admin sees the state graph itself. One source of truth, three views — never three separate "status" fields to keep in sync.
- **Optimistic lock conflicts** (any surface, any optimistically-locked record — orders, tasks, lines all carry a `version`): on `409`, show "This {record} changed since you loaded it" + auto-refresh the record, do not silently overwrite. The record name in the message must match what the user was acting on (e.g. "This task changed..." for an inline queue-row Approve/Reject, not "This order changed..." — the task queue's inline actions can 409 independently of anything happening to the order itself).
- **Color is never the only signal.** Every place this spec uses color to encode meaning (status badges, SLA due column, designer node colors) also carries a text label or icon. This is a hard rule, not a per-screen suggestion — re-check it whenever a new color-coded element is added.
- **Empty/error voice**: system copy, not personified — "Couldn't load tasks. Retry", never "Oops! Something went wrong on our end!"

---

## 6. Status Badge Color Mapping (reference)

| `state_type` | `is_terminal` | Badge color | Example |
|---|---|---|---|
| AUTOMATIC | false | Blue | `PAYMENT_PENDING` |
| MANUAL | false | Amber | `CREDIT_REVIEW` |
| WAIT | false | Gray | `FULFILLMENT_QUEUED` |
| any | true, success path | Green | `DELIVERED` |
| any | true, failure path | Red | `CANCELLED` |

`success path` vs `failure path` for terminal states is the `terminal_outcome` flag set in the Admin workflow designer per terminal node (§4.3, required whenever `is_terminal` is on) — `is_terminal` alone doesn't say which side of the outcome it's on. `GET /order-types/status-taxonomy` (§2.1) returns this pre-computed as `badgeCategory` (`AUTOMATIC` / `MANUAL` / `WAIT` / `TERMINAL_SUCCESS` / `TERMINAL_FAILURE`) so no client needs to re-derive it from the three underlying columns.
