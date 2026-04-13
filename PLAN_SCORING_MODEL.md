# Plan: Hierarchical Scoring Model

**Status: Original sketch is superseded. The "Revision after codebase audit" section
at the bottom of this file reflects what was actually built.**

The original sketch (greenfield content model, separate `app_scores`/`channel_scores`/
`content_model` tables) was abandoned after an audit found `AppRanking`, `AppBehaviorProfile`,
and `NotificationClassifier` already covered most of the ground. The revision section
(starting at "Revision after codebase audit") describes what was implemented:
`Scorer`, `TierMapper`, `ScoringRefit`, `ChannelRanking`, `ScoreQuantiles`, and the
revised DoD. All of those are in place as of DB v12.

Read the revision section, not the original sketch, for implementation truth.

---

Sketch of the inference-side math that training data feeds. **Not an
implementation plan** — a design artifact to decide the shape before we commit
the schema and scorer.

---

## Output

For each incoming notification `x` belonging to `(app a, channel c)`, produce a
scalar importance score `s(x) ∈ [0, 1]`, then threshold to a tier
`{0 Invisible, 1 Noise, 2 Worth, 3 Interrupt}`.

Everything below is one way to compute `s(x)` that makes the three training
modes compose instead of compete.

---

## The three signals

| Level | What training produces | Shape |
|---|---|---|
| App | Rating `rₐ` per package | Bradley-Terry / Elo from app-battle judgments |
| Channel | Rating `r_c` per `(pkg, channel)` | B-T from channel-pair judgments |
| Content | Classifier `f(x) → p_content(x)` | Learned from same-channel notification pairs |

Ratings convert to scalars via the sigmoid of the rating gap to a reference:
`θ = σ(r − r₀)`. All three priors live in `[0, 1]`.

---

## Shrinkage: app → channel → content

Channel posteriors shrink toward their parent app when thin; apps shrink
toward the global mean. This is empirical Bayes with conjugate Beta-Binomial
math under the hood — here in pseudo-count form so it's obvious what the knobs
do.

```
θ_global = 0.5                                      # uninformative

θ_a = (n_a · w_a  +  k₀ · θ_global) / (n_a + k₀)    # app shrinks to global
θ_c = (n_c · w_c  +  k₁ · θ_a)     / (n_c + k₁)     # channel shrinks to app
```

- `n_a, n_c` = number of pairwise judgments the bucket has participated in.
- `w_a, w_c` = empirical win rate (equivalently, B-T posterior mean).
- `k₀, k₁` = pseudo-counts. Start `k₀ = 10`, `k₁ = 5`. Tune later.

**What this buys:** a channel with 2 judgments is ~70% its app's score and
~30% its own. A channel with 50 judgments is ~90% its own. No hand-coded
switches, no "use channel if ≥N judgments else app" — the transition is smooth
and derived from data volume.

**What it says about contradictions:** if Gmail is θ=0.8 but Gmail/Promotions
has 30 judgments averaging 0.2, the channel posterior sits near 0.25. The app
prior's influence fades exactly as the contradicting evidence accumulates.
Nothing is overridden — the disagreement *is* the learning.

---

## Content as residual, not peer

Content model output `p_content(x)` is the probability this notification wins
a pairwise match **against an average notification from the same channel**.
Train it on judgments where both sides share `(pkg, channel)` — that isolates
content effect from bucket bias.

Combine in logit space so the content model only has to learn *deviations* from
the channel mean:

```
logit(s) = logit(θ_c) + β · logit(p_content(x))
```

with `β` shrunk toward 0 when the content model has few same-channel pairs to
learn from:

```
β = m / (m + k₂)      # m = same-channel judgment count, k₂ ≈ 20
```

Fresh install: `β → 0`, so `s ≈ θ_c`, so `s ≈ θ_a`, so `s ≈ 0.5`. Everything
degrades gracefully to the next-coarser signal.

---

## One scoring function, any training mode

The user picks what they train. The scorer always uses all three levels —
the ones they didn't train explicitly get populated by aggregation:

- **Channel-only trainer** → app score = weighted mean of their channel
  posteriors under that app. App participates in shrinkage for unseen
  channels of the same app.
- **Notification-only trainer** → channel score derived from per-channel
  aggregation of content-model predictions over that channel's history.
  Noisier but non-empty.
- **App-only trainer** → every channel of that app inherits the app score as
  its prior; `θ_c ≈ θ_a` until channel data arrives.
- **All three** → hierarchical posterior, content as residual. The intended
  path.

No mode produces zeros for the others. No mode "wins." Each layer contributes
confidence-weighted evidence to the same `s(x)`.

---

## Tier assignment

Map `s(x) → tier` by **user-adjustable quantiles** of their own recent score
distribution (not fixed thresholds):

```
tier 3 (Interrupt)  if s ≥ q_user(0.90)
tier 2 (Worth)      if s ≥ q_user(0.60)
tier 1 (Noise)      if s ≥ q_user(0.20)
tier 0 (Invisible)  else
```

Quantiles make the system self-calibrating — if the user's baseline noisy
distribution shifts, tier boundaries shift with it. The 0.90/0.60/0.20 defaults
are what the user's Rules screen can expose as "how picky are you."

---

## What the schema needs

Current `training_judgments` stores the **raw pairwise data** — that's the
source of truth and should not change. Add three materialized tables that
the scorer reads on every incoming notification:

- `app_scores(package_name PK, rating REAL, n_judgments INT, updated_at INT)`
- `channel_scores(package_name, channel_id, rating REAL, n_judgments INT,
  updated_at INT, PRIMARY KEY(package_name, channel_id))`
- `content_model(version INT PK, weights BLOB, trained_at INT,
  n_training_pairs INT)`

These are **recomputed**, not authored — a periodic `WorkManager` job
(debounced after N new judgments, or nightly) replays `training_judgments`
into Bradley-Terry fits and refreshes `app_scores` / `channel_scores`, and
retrains the content model.

Inference path on notification arrival:
1. Lookup `θ_a, θ_c` (two indexed reads).
2. Apply shrinkage (pure arithmetic, no DB).
3. Run content model (in-memory, probably <1ms).
4. Combine in logit space.
5. Map to tier via user quantiles.

Everything here is local, offline, Ollama-free. The content model is small
enough to live in-process; no LLM needed for per-notification scoring.

---

## What we should confirm before building

1. **Does `app_behavior_profiles` already cover `app_scores`?** It has
   `(package_name, channel_id)` uniqueness — possibly already the channel-score
   table in spirit. Reuse beats parallel.
2. **Bradley-Terry vs plain win rate for `w_a, w_c`.** B-T handles opponent
   strength (beating a strong app > beating a weak one). Worth the complexity
   only once judgment counts justify it — phase 2.
3. **Content model choice.** Options: bag-of-words logistic regression (tiny,
   fast, interpretable), or a small on-device embedding + linear head. Start
   with BoW logistic; upgrade if accuracy plateaus.
4. **Quantile window.** Per-user quantile needs a rolling window (last 30
   days?) or it ossifies. Decide before exposing the Rules screen knob.

---

## Open question: training UX feedback loop

The scorer can expose per-notification **signal attribution**: "this notif is
tier 3 because channel X contributed +0.4, content +0.1." Surfacing that in
the History screen closes the loop — user sees *why* the system decided, can
disagree, and that disagreement becomes a new judgment. Out of scope for this
plan but a natural follow-up once the scorer exists.

---

# Revision after codebase audit

The original sketch assumed a greenfield. The audit (see conversation history)
changed several decisions. What follows supersedes the relevant sections above
where they conflict.

## What already exists that we reuse

| Signal | Existing table/class | Reuse |
|---|---|---|
| App rating (Elo) | `AppRanking` (K=32, wins/losses/ties, `elo_score`) | **Yes — this IS `app_scores`.** Extend, don't duplicate. |
| Behavioral priors per (pkg, channel) | `AppBehaviorProfile` (tap rate, dismiss rate, session_ms, category votes, user_reclassified) | **Yes — observational evidence layer.** |
| Content features | `NotificationClassifier` → `aiClassification` (6-category) + `aiConfidence` on every `NotificationRecord` | **Yes — use category as a feature, don't train a new content model.** |
| Pairwise judgment log | `TrainingJudgment` (v10 has channel columns) | **Yes — source of truth, unchanged.** |
| Periodic compute home | `AiAnalysisWorker` (24h, charging+idle) | **Yes — add a re-fit step.** |

## What actually needs to be new

1. **`ChannelRanking` entity** — parallel to `AppRanking`, keyed on `(package_name, channel_id)`. Columns: `elo_score` (default 1200), wins/losses/ties, judgments, `updated_at_ms`. Updated on every `ChannelPair` judgment submit (synchronous, same pattern as `AppRanking`).

2. **Judgment-count DAO methods** — none exist today:
   - `TrainingJudgmentDao.countByPackage(pkg)` → Int
   - `TrainingJudgmentDao.countByChannel(pkg, channelId)` → Int
   - `TrainingJudgmentDao.countSameChannelPairs(pkg, channelId)` → Int (for β shrinkage)

3. **Scorer function** (`ai/scoring/Scorer.kt`) — the `s(x)` pipeline. No persistent state; pure function over DB lookups.

4. **Tier assignment replacement** — `TierClassifier.classify()` becomes the **cold-start fallback** used only when the scorer has no signal (brand-new install, package never seen). Once `AppRanking` exists for a package, scorer wins.

5. **User-quantile threshold table** — a small `score_quantiles(window_days, q20, q60, q90, computed_at)` row, recomputed nightly in `AiAnalysisWorker`.

## Revised scoring function (concrete)

For incoming notification `x` with `(pkg a, channel c, category k, ai_conf p_k)`:

```
# Base ratings → probabilities
θ_a = σ((AppRanking[a].elo - 1200) / 400)
θ_c = σ((ChannelRanking[a, c].elo - 1200) / 400)

# Hierarchical shrinkage
n_a = AppRanking[a].judgments
n_c = ChannelRanking[a, c].judgments
θ_a_shrunk = (n_a · θ_a + k₀ · 0.5)       / (n_a + k₀)      # k₀ = 10
θ_c_shrunk = (n_c · θ_c + k₁ · θ_a_shrunk) / (n_c + k₁)     # k₁ = 5

# Category bias (replaces "content model")
# Six learned weights b_k, one per category. Fit by logistic regression
# against same-(pkg,channel) judgment outcomes. Confidence-weighted.
bias_content = p_k · b[k]

# Behavioral prior (from AppBehaviorProfile)
# Tap rate adds, dismiss rate subtracts. Bounded.
prof = AppBehaviorProfile[a, c]
bias_behavior = clip(0.3 · (prof.tap_rate - prof.dismiss_rate), -0.15, +0.15)

# Combine in logit space
logit_s = logit(θ_c_shrunk) + β · bias_content + γ · bias_behavior
s(x) = σ(logit_s)

# Weight β grows with same-channel judgment volume; γ grows with session count
β = m_same_channel / (m_same_channel + 20)
γ = prof.total_sessions / (prof.total_sessions + 30)
```

This replaces the "content model as residual" section above. **No new ML
model is trained** — we reuse `NotificationClassifier`'s category output and
learn six scalars `b[k]`. That's fit-able in <1ms.

## Tier mapping — unchanged

User-quantile mapping (`s → tier`) as originally specified, with the quantile
table recomputed nightly from the last 7 days of scored notifications.

## Fit/retrain pipeline (fits into AiAnalysisWorker)

Add a new step between existing Step 6 (profile accumulation) and Step 7
(retention cleanup):

- **Step 6.5: ScoringRefit**
  1. Recompute `AppRanking` and `ChannelRanking` from `training_judgments`
     via full BT replay (bounds drift; Elo updates are synchronous per
     judgment but can accumulate error over thousands of pairs).
  2. Refit `b[k]` category weights via logistic regression on same-channel
     pairs.
  3. Recompute user-quantile table over last 7 days of scored notifications.

  Debounce: skip if fewer than 10 new judgments since last run. Runs in the
  existing 24h cadence, same constraints (charging + idle).

## Cold-start ordering

On notification arrival, the scorer tries each path in order and uses the
first that has signal ≥ threshold:

1. `AppBehaviorProfile.user_reclassified` override → absolute priority (same as today).
2. Hierarchical score `s(x)` if `AppRanking[a]` exists (any data).
3. `TierClassifier` rule output → fallback for brand-new packages.

This preserves existing behavior on install while gradually ceding ground to
the learned scorer as data accumulates. No forced migration.

## Revised DoD for the scoring phase

- [ ] `ChannelRanking` entity + DAO + migration (v10 → v11).
- [ ] Judgment-count DAO methods added.
- [ ] `Scorer` class with pure `score(record, context): Double` function.
- [ ] `TierMapper` reading nightly quantile table.
- [ ] `submitChannelPair` updates `ChannelRanking` Elo (mirror `submitAppPair` path).
- [ ] `AiAnalysisWorker` Step 6.5 implemented.
- [ ] `LithiumNotificationListener` calls scorer before falling back to `TierClassifier`.
- [ ] Signal-attribution debug log per scored notification (string of contributions) — prep for the History-screen feedback loop.

## Remaining open questions

1. **Is Elo drift over thousands of judgments enough of a problem to justify BT replay, or can we rely on online updates?** Elo with K=32 is fine for hundreds of matchups per entity; likely fine at our scale. Start with online-only; add batch replay only if empirical drift shows up.
2. **Should category weights `b[k]` be user-specific or global?** Start user-specific. A global prior can be added later as a shrinkage target for the per-user weights.
3. **`AppBehaviorProfile` inclusion** — are tap/dismiss genuinely informative of importance, or do they track engagement-bait? Risk: a doomscrolling app gets high tap rate but low importance. Mitigation: `γ` is capped at 0.15 contribution; user judgments dominate when present. Revisit once real data lands.
4. **Where does `isFromContact` fit?** Today it's a strong tier-3 signal in rules. In the new scorer it could be a per-notification feature like `bias_contact = +0.2 if from_contact`. Preserves the behavior without hardcoding it.
