# ADR-002 · Go backend, Connect-RPC, single monorepo

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** CTO

## Context

The backend must serve two native mobile clients and two web surfaces, hold tens of thousands of concurrent WebSocket connections for realtime messaging, run a matching pipeline, and be operable by a single SRE during the first year. The team will be 8–15 people, with three backend engineers.

We also have a hard constraint from ADR-003: no managed third-party services. That means we operate what we choose, so operational simplicity carries unusual weight.

## Decision

**Go 1.23+** for all backend services. **Connect-RPC** with **buf** for interface definition, generating gRPC for internal service-to-service calls and plain JSON/HTTP for mobile and web from the same protobuf source. **sqlc** for database access — type-safe Go generated from hand-written SQL, no ORM. **PostgreSQL 16** as the primary datastore. **River** for background jobs, backed by the same Postgres.

Ten services — identity, profile, discovery, proximity, messaging, calling, gateway, media, safety, billing — deployed as separate binaries but built from a **single monorepo** with a shared `pkg/`. Modular, not micro.

`backend/proto/` is the organisation's single source of truth for every interface. Contract-first is mandatory: a protobuf change is reviewed, merged alone, and its consumers notified, before any implementation depends on it.

## Alternatives considered

**Node/TypeScript.** One language across web and backend, the widest hiring pool. Rejected primarily on the realtime path — holding many thousands of concurrent WebSocket connections per instance is meaningfully more efficient in Go, and our messaging service is the hottest path in the product.

**Elixir/Phoenix.** Arguably the best-fitting runtime for exactly this realtime workload. Rejected on hiring: we cannot staff three backend engineers plus on-call coverage in this market.

**Rust.** Best performance and safety. Rejected on development velocity for a pre-launch product and on hiring depth.

**gRPC alone, or REST alone.** Connect-RPC gives us both from one definition, which removes a whole category of drift between what mobile expects and what services promise.

**Polyrepo.** Rejected outright at this team size. A monorepo means an API change and its four consumers land in one atomic, reviewable commit. Polyrepo buys version skew and coordination overhead we cannot afford.

## Consequences

**Good.** Static binaries deploy trivially. Strong standard library reduces dependency surface. One protobuf definition keeps four clients honest. `sqlc` means every query that runs is a query a human wrote and can read — no ORM surprises under load. Reasonable hiring pool.

**Bad / accepted costs.** Go's error handling is verbose. No generics-heavy abstraction, so some repetition. Monorepo CI must be configured with path-based filtering early or build times degrade. Contract-first requires real discipline and will feel slow in week two.

**Reversibility:** Moderate. Service boundaries are clean enough that a single service could be rewritten in another language behind its protobuf contract.

## Revisit when

A single service becomes a performance bottleneck that Go cannot address, or the monorepo's CI exceeds roughly 15 minutes on a typical change.
