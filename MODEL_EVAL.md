# Model Evaluation — Choosing the GGUF to Ship

**Status: Harness implemented; eval dataset not yet created.**

The eval harness classes exist (`ModelEvalHarness.kt`, `EvalViewModel.kt`, `EvalScreen.kt`,
`EvalDataset.kt`) and the Settings screen wires into an eval runner. However, the dataset
files described below (`app/src/main/assets/eval/test_cases.json`, `apps.json`,
`contacts.json`) have not been created yet. The harness will fail to load until those
files exist. Running the eval before creating the dataset is a no-op.

No GGUF model has been evaluated or selected. The bundling step (Step 3 under
"Bundling the winner") has not been done.

---

The Chat tab's **Create rule** tool runs per-field LLM extraction on-device.
We need to pick the smallest GGUF model that extracts reliably across varied
phrasings. This doc covers how to download candidate models, sideload them, and
run the on-device eval harness to pick a winner.

## Candidate models

All INT4 quantised (Q4_K_M). Smaller is better if accuracy is comparable — the
winner gets bundled into the APK's `assets/models/` at ~300–900 MB.

| Model | Params | Approx Q4 size | Expected latency / field (Pixel 8 Pro) | HF source |
|---|---|---|---|---|
| SmolLM2-135M-Instruct | 135M | ~90 MB  | ~80 ms  | `HuggingFaceTB/SmolLM2-135M-Instruct-GGUF` |
| Qwen2.5-0.5B-Instruct | 500M | ~350 MB | ~250 ms | `Qwen/Qwen2.5-0.5B-Instruct-GGUF` |
| Llama-3.2-1B-Instruct | 1B   | ~770 MB | ~450 ms | `bartowski/Llama-3.2-1B-Instruct-GGUF` |
| Qwen2.5-1.5B-Instruct | 1.5B | ~940 MB | ~650 ms | `Qwen/Qwen2.5-1.5B-Instruct-GGUF` |
| Phi-3.5-mini-instruct | 3.8B | ~2.3 GB | ~1800 ms | `bartowski/Phi-3.5-mini-instruct-GGUF` (upper bound; likely too big to ship) |

Latency figures are rough and assume `n_threads = 4`, context `1024`, ~30 output
tokens per field. Real numbers come out of the harness.

## Downloading

Use any of:

```bash
# Option A — huggingface-cli (fastest)
pip install -U "huggingface_hub[cli]"
huggingface-cli download HuggingFaceTB/SmolLM2-135M-Instruct-GGUF \
  smollm2-135m-instruct-q4_k_m.gguf --local-dir ~/lithium-models

# Option B — direct wget
wget -O ~/lithium-models/qwen2.5-0.5b-instruct-q4_k_m.gguf \
  "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
```

## Sideload onto the device

The app looks for `*.gguf` files inside its private `filesDir/models/`
directory. Only one model is loaded at a time; `LlamaEngine` picks the
alphabetically-first `.gguf` in the directory.

```bash
# Pick exactly one model to evaluate; remove any others first.
adb shell "run-as ai.talkingrock.lithium.debug ls files/models/"
adb shell "run-as ai.talkingrock.lithium.debug rm -rf files/models" && \
adb shell "run-as ai.talkingrock.lithium.debug mkdir -p files/models"

# Push via cat (works under run-as, unlike `adb push` which cannot write to app-private dirs)
adb push ~/lithium-models/smollm2-135m-instruct-q4_k_m.gguf /sdcard/Download/current.gguf
adb shell "run-as ai.talkingrock.lithium.debug sh -c 'cat /sdcard/Download/current.gguf > files/models/current.gguf'"
adb shell "rm /sdcard/Download/current.gguf"
```

Force-stop the app after swapping models so the next run reloads fresh:

```bash
adb shell am force-stop ai.talkingrock.lithium.debug
```

## Running the eval

1. Open the app → **Settings** → scroll to bottom → **Model evaluation (debug)**.
2. The screen shows the currently-loaded `.gguf` filename.
3. Tap **Run evaluation**. Progress bar ticks once per phrasing.
4. The harness runs every scenario × phrasing from `assets/eval/test_cases.json`
   (~50 scenarios × 4 tones = ~200 extractions). Total runtime scales with
   model size:

   | Model size | Total runtime |
   |---|---|
   | 135M | ~5 min |
   | 500M | ~15 min |
   | 1.5B | ~35 min |

5. Results show: overall field accuracy, per-field accuracy, scenarios fully
   passed, and an expandable per-phrasing list with failure details.

## Comparing models

Repeat for each candidate:

1. Swap the GGUF (see **Sideload** above), force-stop the app.
2. Reopen → Settings → Model evaluation → Run.
3. Screenshot the summary card. Paste filename, overall accuracy, per-field
   scores, and avg latency into a table.

The winner is **the smallest model that clears ≥85% overall accuracy and
≥90% on `packageName` and `action` fields** (the fields rule utility depends
on most). If nothing clears 85%, we either expand the prompt-engineering,
revise the dataset, or accept the top scorer.

## Bundling the winner

Once chosen:

1. Move the winning `.gguf` into `app/src/main/assets/models/llm.gguf`.
2. Update `LlamaEngine.loadModel` to copy from assets on first run if
   `filesDir/models/` is empty (implementation TBD — a follow-up issue
   will track this).
3. Update this doc with the chosen model + its eval numbers.

## Dataset notes

The synthetic datasets live in `app/src/main/assets/eval/`:

- `apps.json` — 30 realistic packages spanning messaging, social, shopping, news, productivity, system.
- `contacts.json` — 50 synthetic contact names (full names, first-only, and role-based like "Mom", "Boss Dan").
- `test_cases.json` — ~50 scenarios, each with 4 phrasings in different tones
  (direct, verbose, vague, emotional/casual/formal/coarse/nervous). A scenario
  is "fully passed" only when all 4 phrasings extract correctly — this
  penalises models that only handle pristine inputs.

Edit these files to expand coverage without touching Kotlin.
