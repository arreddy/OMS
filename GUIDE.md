# Guide: testing end-to-end, extending attributes, configuring workflow

Companion to [README.md](README.md) (how to start everything) and
[SPEC.md](SPEC.md) / [UI-SPEC.md](UI-SPEC.md) (design). This is the
"how do I actually do X" doc.

All `curl` examples assume the backend is running on `:8080` (see README) and
use `X-User-Id` to set who an action is attributed to — there's no real auth,
so any value is accepted.

The OMS is multi-tenant (SPEC.md §10): every request also needs an
`X-Tenant-Id` header. There's no fallback — a missing header returns `400`,
an unrecognized one returns `404`. Flyway seeds a `default` tenant
(`V4__add_multi_tenancy.sql`), which every example below uses; see §4 for how
to register another one.

---

## 1. Testing end-to-end

### 1.1 Automated

```bash
mvn test     # unit tests — guard evaluator, JSON Schema validation. No Docker needed.
mvn verify   # + OrderWorkflowIT — drives the real REST API against a real Postgres
             # (via Testcontainers). Needs Docker running.
```

`OrderWorkflowIT` is the closest thing to a full e2e suite today: it exercises
the seeded `STANDARD` workflow (low-amount auto-progression to delivery,
high-amount credit-review approve/reject, optimistic-lock conflicts), the
publish-validation rules, the status taxonomy, and the schema-extension
endpoint added in §2 below. Read it for more worked examples than this guide
has room for.

### 1.2 Manual, via curl — the full STANDARD lifecycle

**Low-value order** (skips credit review — `STANDARD`'s guard is `amount > 1000`):

```bash
ORDER_ID=$(curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" -H "X-User-Id: dev" -H "X-Tenant-Id: default" \
  -d '{"orderTypeCode":"STANDARD","customerRef":"cust-1","currency":"USD","totalAmount":"500.00"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['orderId'])")

# CREATED -> PAYMENT_PENDING
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/workflow/transitions \
  -H "Content-Type: application/json" -H "X-User-Id: dev" -H "X-Tenant-Id: default" \
  -d '{"triggerType":"EVENT","triggerCode":"order.submitted"}'

# PAYMENT_PENDING -> FULFILLMENT_QUEUED (guard didn't fire since 500 <= 1000)
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/workflow/transitions \
  -H "Content-Type: application/json" -H "X-User-Id: dev" -H "X-Tenant-Id: default" \
  -d '{"triggerType":"EVENT","triggerCode":"payment.captured"}'

# FULFILLMENT_QUEUED -> SHIPPED -> DELIVERED
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/workflow/transitions \
  -H "Content-Type: application/json" -H "X-User-Id: dev" -H "X-Tenant-Id: default" \
  -d '{"triggerType":"EVENT","triggerCode":"shipment.dispatched"}'
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/workflow/transitions \
  -H "Content-Type: application/json" -H "X-User-Id: dev" -H "X-Tenant-Id: default" \
  -d '{"triggerType":"EVENT","triggerCode":"shipment.delivered"}'

curl -s -H "X-Tenant-Id: default" http://localhost:8080/orders/$ORDER_ID | python3 -m json.tool   # status: DELIVERED
```

**High-value order** (guard fires, lands in `CREDIT_REVIEW`, needs a human decision):

```bash
ORDER_ID=$(curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" -H "X-User-Id: dev" -H "X-Tenant-Id: default" \
  -d '{"orderTypeCode":"STANDARD","customerRef":"cust-2","currency":"USD","totalAmount":"5000.00"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['orderId'])")

# CREATED -> PAYMENT_PENDING -> CREDIT_REVIEW (guard fires immediately, no payment.captured needed)
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/workflow/transitions \
  -H "Content-Type: application/json" -H "X-User-Id: dev" -H "X-Tenant-Id: default" \
  -d '{"triggerType":"EVENT","triggerCode":"order.submitted"}'

# Find the task it created
TASK=$(curl -s -H "X-Tenant-Id: default" "http://localhost:8080/tasks?status=UNASSIGNED&assigneeGroup=credit-team")
TASK_ID=$(echo "$TASK" | python3 -c "import json,sys; print(json.load(sys.stdin)['content'][0]['taskId'])")

# Claim it (If-Match is the task's current version — 0 for a fresh task)
curl -s -X POST http://localhost:8080/tasks/$TASK_ID/claim \
  -H "If-Match: 0" -H "X-User-Id: reviewer-1" -H "X-Tenant-Id: default"

# Approve (version is now 1 after the claim) -> fires TASK_APPROVED -> FULFILLMENT_QUEUED
curl -s -X POST http://localhost:8080/tasks/$TASK_ID/approve \
  -H "Content-Type: application/json" -H "If-Match: 1" -H "X-User-Id: reviewer-1" -H "X-Tenant-Id: default" \
  -d '{"comment":"looks fine"}'
```

Reject instead of approve to see it land in `CANCELLED`:
`POST /tasks/{id}/reject` with `{"reason":"fraud risk"}`. The UI requires a
non-blank reason before enabling the Reject button, but the API itself
doesn't reject a blank one — unlike `escalate`, which returns `400` for a
blank reason at the API level, not just in the UI.

**Optimistic-lock conflict** — PATCH with a stale version:

```bash
curl -s -i -X PATCH http://localhost:8080/orders/$ORDER_ID \
  -H "Content-Type: application/json" -H "If-Match: 999" -H "X-Tenant-Id: default" \
  -d '{"customerRef":"new-ref"}' | head -1   # HTTP/1.1 409
```

### 1.3 Manual, via the UI

Same flows as above, clicking instead of curling — see [README.md](README.md)
for how to start `web/`. Ops Console (`/ops/orders`, `/ops/tasks`) covers the
order-submission and task-decision paths; Admin Console (`/admin/order-types`)
covers schema/workflow changes (§2, §3 below); `/track/{orderId}` is the
customer-facing view of whatever order ID you paste in.

`/ops/orders/new` ("New order" button on the order list) creates an order
through the UI directly — fields for order type, customer ref, currency,
total amount, and a schema-driven form for attributes and lines (UI-SPEC.md
§2.1a). The curl walkthrough above is still worth knowing for scripting
and for exercising workflow transitions, which have no UI trigger of their
own outside Order Detail's "Available actions."

---

## 2. Extending order attributes

This is SPEC.md §3.3's core promise: new custom fields are a data change
(`order_type.attribute_schema`), never a migration.

### 2.1 Adding a field to an existing order type

```bash
curl -s -X PATCH http://localhost:8080/order-types/STANDARD \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" \
  -d '{
    "attributeSchema": {
      "type": "object",
      "properties": {
        "giftMessage": {"type": "string", "maxLength": 500, "title": "Gift message"},
        "priorityShipping": {"type": "boolean", "title": "Priority shipping", "x-show-in-task": true}
      },
      "additionalProperties": true
    }
  }'
```

Notes:
- `attributeSchema` and `lineAttributeSchema` are independently optional — send
  just the one you're changing. Whichever you send **replaces** that schema
  wholesale (it's not a merge-patch on individual fields), so include every
  field you want to keep, not just the new one.
- This never touches already-stored `order.attributes` — existing orders keep
  whatever they have, valid or not against the new schema, and are never
  re-validated unless they're written to again (UI-SPEC.md §4.2).
- `x-show-in-task: true` is a vendor extension the JSON Schema validator
  ignores but the Task Detail screen reads, to decide which attributes to
  surface in a reviewer's context panel (UI-SPEC.md §2.4). There's an
  equivalent `x-customer-visible` the schema builder UI writes but nothing
  currently reads (the Customer Portal never renders `order.attributes` at
  all — see UI-SPEC.md §3 — it's there for spec fidelity / a future screen).
- **There's no Admin UI for this yet** — `OrderTypeEditorPage` only supports
  *creating* a new order type's schema, not editing an existing one's. This
  endpoint is real and tested (see `OrderWorkflowIT`), just not wired into a
  form yet.
- Field type, in both the API and the schema-builder UI, is a real JSON
  Schema type (`string`/`number`/`boolean`/`array`/`object`) plus a separate
  `format` for date-like strings (`{"type":"string","format":"date"}`, not a
  `"date"` type) and a separate `enum` keyword for restricting to a fixed set
  of values — see UI-SPEC.md §4.2 for why these aren't folded into one "Type"
  dropdown.

### 2.2 Creating a new order type from scratch

Via the Admin Console: `/admin/order-types/new` → fill in code/name → use the
two schema builders (order attributes, line attributes) → "Create & configure
workflow" takes you straight into the Workflow Designer, because an order
type with no published workflow can't have orders created against it (see §3).

Via the API:

```bash
curl -s -X POST http://localhost:8080/order-types \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" \
  -d '{
    "code": "EXPRESS",
    "name": "Express Order",
    "attributeSchema": {"type": "object", "properties": {}, "additionalProperties": true},
    "lineAttributeSchema": {"type": "object", "properties": {}, "additionalProperties": true}
  }'
```

Both schemas are required at creation time (unlike the PATCH above, where
either is optional).

---

## 3. Configuring workflow

### 3.1 Via the Workflow Designer (UI)

`/admin/order-types/{code}/workflow`. If the type already has a published
workflow, the canvas loads it pre-populated; otherwise you're starting from
an empty canvas.

- **Add a state**: "+ Add state" button, then click it to edit code,
  state type (`AUTOMATIC`/`MANUAL`/`WAIT`), initial/terminal, and (if
  terminal) the success/failure outcome that drives badge coloring
  everywhere else in the UI (UI-SPEC.md §6).
- **Add a transition**: drag from one state's right edge to another's left
  edge, then click the new edge to set trigger type/code, an optional JSON
  Logic guard (§3.3), and sequence (tie-breaker when a state has more than
  one outbound transition — lower fires first).
- **Publish**: disabled until the same validation the server enforces passes
  (§3.4) — the error list above the canvas tells you what's still wrong.
  Publishing always creates a **new version**; orders already in flight keep
  running on whatever version they started on (SPEC.md §4.1, §8).
- **View past version**: the dropdown loads an older version read-only
  alongside the canvas (states + transitions list). It's view-only, not a
  diff against your current draft — there's no version-diffing yet.

### 3.2 Via the API directly

`PUT /order-types/{code}/workflow`. Body shape (mirrors the Designer's
underlying model — SPEC.md §4.1–§4.3):

```bash
curl -s -i -X PUT http://localhost:8080/order-types/EXPRESS/workflow \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" \
  -d '{
    "name": "Express Workflow v1",
    "states": [
      {"code": "CREATED", "stateType": "AUTOMATIC", "initial": true, "terminal": false,
       "defaultAssigneeGroup": null, "customerVisible": true, "customerFacingLabel": "Order received",
       "terminalOutcome": null, "canvasX": 40, "canvasY": 40},
      {"code": "SHIPPED", "stateType": "AUTOMATIC", "initial": false, "terminal": true,
       "defaultAssigneeGroup": null, "customerVisible": true, "customerFacingLabel": "Shipped",
       "terminalOutcome": "SUCCESS", "canvasX": 260, "canvasY": 40}
    ],
    "transitions": [
      {"fromStateCode": "CREATED", "toStateCode": "SHIPPED", "sequence": 0,
       "triggerType": "EVENT", "triggerCode": "order.submitted", "guardExpression": null, "sideEffect": null}
    ]
  }'
```

A successful publish returns `201` with a `Location` header pointing at the
new `workflow_definition`; a failed one returns `400` with the specific rule
that was violated (§3.4) — same validation either way, so scripting this
directly is just as safe as using the Designer.

### 3.3 Guard expressions

`guardExpression` is JSON Logic (SPEC.md §9 — chosen for being safe-by-
construction, no arbitrary code execution), evaluated against
`{"order": {orderId, orderNumber, orderTypeCode, status, customerRef,
currency, totalAmount, attributes: {...}}}`. A `null` guard always passes. A
transition with `triggerCode: null` is evaluated immediately on entering the
state (no external signal needed) — that's how `STANDARD`'s credit-review
branch works:

```json
{">": [{"var": "order.totalAmount"}, 1000]}
```

To branch on something in `attributes` instead: `{"var": "order.attributes.someField"}`.

### 3.4 Validation rules (enforced both server-side and in the Designer)

A publish is rejected (`400`) unless:
1. Exactly one state has `initial: true`.
2. Every state is reachable from the initial state by following transitions.
3. Every non-terminal state has at least one outbound transition (no dead ends).
4. Every `MANUAL` state has both a `TASK_APPROVED` and a `TASK_REJECTED` outbound transition.
5. `terminalOutcome` is set if and only if `terminal: true`.

(1)–(4) live in `OrderTypeService#validateGraph`; (5) is enforced both there
and by a DB check constraint (`chk_terminal_outcome_consistency`) as a
backstop.

### 3.5 Versioning semantics, worth knowing before you publish

- `workflow_definition` rows are immutable once published — "editing" in the
  Designer never mutates one in place, it always produces a new version.
- `order_type.workflow_definition_id` is the *only* place "the active
  version" is recorded (SPEC.md §4.1) — publishing repoints it atomically.
- A `workflow_instance` pins the version it started on at order-creation
  time and never moves, even after a newer version is published — so
  changing a workflow can't retroactively change behavior for an order
  that's already mid-flight.

---

## 4. Multi-tenancy

See SPEC.md §10 for the design. The practical bits:

### 4.1 Registering a new tenant

**There's no API for this yet** — `tenant` is a plain registry table with no
controller in front of it. Insert directly:

```bash
docker compose exec postgres psql -U oms -d oms \
  -c "INSERT INTO tenant (tenant_id, name) VALUES ('acme', 'Acme Corp');"
```

`default` is seeded by `V4__add_multi_tenancy.sql` and is what every other
example in this guide uses.

### 4.2 Every request needs `X-Tenant-Id`

Omit it and the API returns `400`; send a `tenant_id` with no matching row in
`tenant` and it returns `404`:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/order-types                       # 400
curl -s -o /dev/null -w "%{http_code}\n" -H "X-Tenant-Id: ghost-corp" http://localhost:8080/order-types  # 404
curl -s -o /dev/null -w "%{http_code}\n" -H "X-Tenant-Id: default" http://localhost:8080/order-types     # 200
```

### 4.3 Seeing isolation in practice

Two tenants can register the same order-type `code` independently, and
neither sees the other's data:

```bash
curl -s -X POST http://localhost:8080/order-types -H "X-Tenant-Id: acme" -H "Content-Type: application/json" -d '{
  "code": "STANDARD",
  "name": "Acme Standard Order",
  "attributeSchema": {"type":"object","properties":{}},
  "lineAttributeSchema": {"type":"object","properties":{}}
}'

curl -s -H "X-Tenant-Id: default" http://localhost:8080/order-types | python3 -c "import json,sys; print([o['code'] for o in json.load(sys.stdin)])"
curl -s -H "X-Tenant-Id: acme"    http://localhost:8080/order-types | python3 -c "import json,sys; print([o['code'] for o in json.load(sys.stdin)])"
```

`default`'s list includes its own `STANDARD` (and anything else seeded for
it); `acme`'s list includes only the `STANDARD` just created for `acme` — the
two never overlap, because every query Hibernate runs against a tenant-owned
entity (`order_type` included) carries an implicit `tenant_id = ?` filter
(SPEC.md §10).

### 4.4 In the frontend

`web/src/lib/api.ts` sends `X-Tenant-Id` on every call, resolved the same way
as the acting user (`getActingTenant()` / `setActingTenant()`, backed by
`localStorage`, default `'default'`). **There's no tenant-switcher UI yet** —
to act as a different tenant in the browser, run
`localStorage.setItem('oms.actingTenant', 'acme')` in the devtools console
and reload.
