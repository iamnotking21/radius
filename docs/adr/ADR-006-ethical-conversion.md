# ADR-006 · Conversion through legitimate psychology, not dark patterns

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** CTO, founder

## Context

Radius is a subscription business. The founder asked for a psychological strategy to maximise subscription conversion. Dating apps have a well-documented history of aggressive monetization mechanics, and there is genuine competitive pressure to match them.

There is also a live regulatory environment. The FTC's Click-to-Cancel Rule was vacated by the Eighth Circuit in July 2025, but the FTC has continued enforcing subscription practices under ROSCA and Section 5 throughout, with recent settlements in the tens of millions, and reopened negative-option rulemaking in March 2026. In the EU, the Digital Fairness Act — explicitly targeting dark patterns, addictive design, and "hidden renewal clauses, difficult cancellations, automatic subscriptions" — is expected as a draft proposal in Q3/Q4 2026.

And there is a brand fact: Radius's entire premium positioning is restraint, honesty, and the absence of manipulation. That is what the customer is paying for.

## Decision

Build an aggressive conversion system using **legitimate behavioural psychology**, and refuse deceptive patterns entirely.

**In scope, and to be used deliberately:** anchoring (annual price first, monthly as the crossed-out anchor), per-day price framing, the centre-stage effect (three tiers with a truthful "most chosen" middle), loss aversion applied only to true statements, reciprocity via a no-card gift, endowed progress, the goal-gradient effect, the peak-end rule for upsell timing, commitment and consistency, genuine social proof, decoy tier architecture where every tier is a real purchasable product, and — the largest and least glamorous lever — ruthless friction removal between intent and purchase.

**Out of scope, permanently:** fake countdown timers and fabricated scarcity; fake likes, bot profiles, or invented "someone viewed your profile" signals; blurred faces implying attention that may not exist; hidden or obstructed cancellation; confirmshaming; deliberately throttling match quality to induce desperation; and auto-renewal without conspicuous disclosure.

**Structural commitments.** Cancellation is at most two taps from Settings with a single genuine retention offer. The renewal disclosure appears above the fold on the purchase screen, never behind a link. The free tier is generous enough for a user to complete the full emotional loop — a match, a real conversation, a Radar handshake, and a voice call — because a user who has never experienced value has nothing to buy more of.

**The gift, not the trial.** Day 3, a no-card, nothing-to-cancel week of Gold. Uptake approaches 100% because there is no risk, it creates real reciprocity, and it converts on demonstrated value rather than on the user forgetting to cancel — which is precisely the mechanism regulators are moving against.

**Upsell fatigue is hard-coded, not advisory.** At most two contextual upsells per session, sixty seconds apart, never during onboarding, verification, an active call, the Handshake, the Match, the first message in a thread, or any safety flow. Three declines of the same trigger suppresses it for thirty days. All upsells are suppressed for twenty-four hours after a report, a block, or a negatively-rated call.

## Alternatives considered

**Match the industry's most aggressive practices.** Higher short-term conversion, and competitors demonstrably do it. Rejected on three grounds: live FTC enforcement under ROSCA plus imminent EU regulation; the refund and chargeback profile, which is already elevated in the dating category and which Apple and Google weight in review decisions; and the brand contradiction — you cannot sell "we're not like the others" through a fake countdown timer.

**A card-required free trial.** Industry standard and converts well on paper. Rejected because uptake is a fraction of a no-card gift, and the conversions it produces are disproportionately from users who intended to cancel and forgot — which is both the source of refunds and the exact target of current rulemaking.

**No conversion psychology at all.** Rejected as naïve. Anchoring, framing, and timing are how *all* pricing is communicated; declining to think about them means doing them badly rather than not doing them.

## Consequences

**Good.** No regulatory rebuild required in eighteen months. Lower refund and chargeback rates, which protects payment standing and store review outcomes. Subscriber retention that reflects real value. Brand consistency, which for a premium product is the asset being sold. And an honest answer available when a journalist or regulator asks how monetization works.

**Bad / accepted costs.** Lower day-1 conversion than a fully manipulative design would produce. Requires discipline when a growth number is behind. Requires measuring net retained subscriber months rather than the flattering headline conversion rate.

**The honesty check.** The founder dashboard carries one ratio: conversation-started and reply rate for paying users versus free users. If people who pay do not measurably do better, the product is not worth its price, and no conversion psychology fixes that. This is the metric that distinguishes a subscription business from a churn treadmill.

**Reversibility:** Technically cheap to reverse, but reversing it means rebuilding the brand promise. Treat it as a one-way door.

## Revisit when

Regulation changes materially, or the honesty check shows paying users are not outperforming free users — in which case the problem is the product, not the paywall.
