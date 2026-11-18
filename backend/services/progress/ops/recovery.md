# Progress recovery

Provision the durable with the filters in `jetstream.json` before first
write. The broker operator owns stream/durable changes. Progress runtime
uses the listed subjects and read-only broker metadata, with TLS and a
dedicated credential. Configure the owner publishers and pull consumers
using their service descriptors. Preserve the broker durability settings
and owner journals in both development and operator-managed environments.

1. Before restoring an owner, pause its HTTP writer. Restore the owned
   PostgreSQL backup and WAL through the intended recovery point. Verify
   the publication, slot, timeline and stored `progress_wal_cursor` together.
   Compare per-account `captured_seq` to the sequence recorded in pending
   cut markers. A replacement slot on a partially populated source leaves
   a detectable gap. If WAL is unavailable, retain affected days in
   CLOSING and investigate from the source backup and retained raw fault rows.
2. Restore Progress and JetStream consistently. Preserve both broker epochs,
   stream and delivery ACK floors, broker receipts, application inbox, projected facts and account
   tombstones. Test a cut/replay request for missing User and Nutrition
   versions. Confirm the source sends original event IDs and Progress
   advances only after all required groups arrive.
3. If reading is blocked, compare `progress_broker_checkpoint` with
   operator-provided stream/consumer metadata. Restore the missing broker
   prefix from backup when possible. If restoration is impossible, reconcile
   every affected account with each owner before enabling ingress.
   Specifically confirm Identity deletion and Health measurement/activity
   tombstones, reopen any missing owner group through authorized replay,
   and validate that pending requests and day results agree.
4. After the owner reconciliation, record the verified stream and durable creation
   timestamps, durable ACK floor, backup/replay positions and the checks
   used. Only a database operator may clear `reconciliation_required`
   and repin the broker epochs. Restart or allow the monitor to revalidate
   before enabling ingress. Keep Progress workers behind the readiness
   guard during reconciliation.

The guard checks both epochs, the exact filter and ACK policies, the broker
retention boundary and a local receipt committed before ACK. A receipt for
the ACK floor alone cannot certify arbitrary point-in-time restores with
earlier concurrently processed messages. Use the coordinated restore and
reconciliation steps whenever a backup is restored.

Observe age and count of CLOSING days, deadline backlog, oldest outbox age,
readiness gate, source account capture faults and quarantine/retry events.
Inspect broker retention and replication slot WAL sizes during an incident.
Telemetry should contain service names, event classes, counts and durations.
Keep owner identifiers, health values and event bodies in the restricted
storage and evidence workflow.
