# Lithium — RETIRED

**Status:** Retired 2026-04-18
**Superseded by:** cairn-mobile (issue #73, Spec #47)

Lithium's notification-capture responsibility has been ported to cairn-mobile as
`CairnNotificationListener`. The local-first, privacy-first Talking Rock philosophy
continues in cairn-mobile.

## What was NOT ported

- Scoring / channel-training logic (`Scorer`, `ScoringRefit`, `TierClassifier`, etc.)
- Shade Mode, rule engine, notification suppression / queuing / resurfacing
- API server, Ktor, WorkManager, Hilt

These are abandoned. The outstanding items in
`~/.claude/projects/-home-kellogg/memory/lithium_scoring_outstanding.md` are closed
by this retirement.

## Repository

The Forgejo repo (`talking-rock/Lithium`) has been archived. The local directory
`~/dev/Lithium` is retained as a reference but will receive no further commits.
