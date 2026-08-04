# ADR-003 · Self-hosted open source on Tencent Cloud, Singapore region

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** CTO, founder

## Context

The founder's constraints: minimise recurring cost during pre-launch, avoid third-party service dependencies, and make use of an existing Tencent Cloud relationship. The team includes one SRE.

There is also a strategic consideration specific to a dating app. We hold unusually sensitive data — sexual orientation, photographs, private messages, and proximity encounters. Handing that to a third-party backend-as-a-service means a vendor holds it too, and it means our privacy posture is only as good as their terms. Self-hosting is not merely cheaper here; it is a better answer to the question a regulator or a journalist will eventually ask.

## Decision

Self-host the entire stack on **Tencent Cloud CVM instances in the Singapore region**, using Tencent as raw infrastructure — compute, network, object storage, load balancing — and explicitly not as a managed-services platform.

**Region: Singapore.** Not a mainland China region. Hosting in mainland China triggers **ICP filing** obligations, which for a foreign-operated consumer app is a lengthy process requiring a Chinese corporate entity, and mainland regions sit behind the national firewall with the reachability and latency consequences that implies for a global product. Singapore gives roughly 30–50 ms to Manila, our launch market.

**Licence-safe substitutions, deliberately chosen:**

- **Valkey**, not Redis — Redis's licence change makes it a commercial risk.
- **SeaweedFS**, not MinIO — MinIO is AGPL, which is a liability for closed-source software.
- **OpenTofu**, not Terraform — Terraform's licence is no longer open source.
- **vodozemac**, not libsignal — libsignal is AGPL; vodozemac is Apache 2.0 and provides the same Double Ratchet primitives.

**Progression:** Docker Compose on three CVMs (Phase 0) → K3s (Phases 1–2) → Tencent Kubernetes Engine (Phase 3+). Everything containerised and defined in OpenTofu and Ansible at every step.

## Alternatives considered

**Supabase or Firebase.** Dramatically faster to an MVP. Rejected on the founder's no-third-party constraint, on the data-sensitivity argument above, and on the cost cliff — both become expensive precisely when you succeed.

**AWS or GCP.** Better managed-service ecosystems and deeper documentation. Rejected because we have an existing Tencent relationship and credits, and because our deliberately portable architecture means the provider matters less than it normally would.

**Tencent managed services.** Rejected for now on cost and lock-in. Because we chose the compatible open-source originals, migrating later is a genuine option rather than a rewrite. The only one Phase 3 is likely to take is **TencentDB for PostgreSQL**, to shed operational load from a single SRE — and that requires its own ADR. **Tencent Redis is permanently excluded**: we run Valkey specifically because Redis's licence is a commercial risk, and a managed Redis reintroduces exactly that. CKafka is excluded unless NATS is genuinely outgrown, which we do not expect before 1M DAU.

## Consequences

**Good.** Near-zero recurring software cost. No vendor holds our users' data. Genuinely portable — leaving Tencent is a weekend, not a quarter. Full control over data residency, retention, and deletion, which matters for our privacy commitments.

**Bad / accepted costs.** We operate Postgres, Valkey, NATS, and object storage ourselves, including backups, upgrades, and 3am pages. This is real work for one SRE. We carry the security patching burden. We have no managed-service SLA to point at during an incident.

**Mandatory mitigations.** Everything in OpenTofu and Ansible — nothing configured by hand, ever. Monthly restore drills, logged. A weekly encrypted backup copy stored **off Tencent entirely**; never keep the only backup at the only provider. A documented escape hatch to managed equivalents, so the option to buy relief stays open.

**Reversibility:** Cheap by construction, which is the point.

## Revisit when

Operational load exceeds one SRE, monthly infrastructure spend passes roughly $2,000, or we need a second geographic region.
