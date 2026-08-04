# website/ — MEMORY
owner: web-next

marketing/ Next15, static export where possible, Tailwind, self-host behind Caddy
admin/    React+Vite+TanStack. INTERNAL ONLY. VPN-gated.
shared/   primitives from design-tokens

## rules
- tokens from mobile/design-tokens. never hardcode a colour or size.
- admin = product constraint (moderation throughput). build it properly, P2. not a side project.
- anyone with moderation power: SSO + MANDATORY hardware-key 2FA. no exceptions.
- admin actions ALL audit-logged: who, what, when, why. immutable log.
- marketing site: no tracker that ships PII to a 3rd party. self-host analytics.
- never render a map. never show precise location. same invariants as app.
- never decrypt a thread. ONLY consented report bundles are viewable, as sealed evidence
  scoped to one report id. never a browsable conversation.
