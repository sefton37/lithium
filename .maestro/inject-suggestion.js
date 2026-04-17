// Flow 18 helper: calls the local rule-enforcement-helper's /inject-suggestion
// endpoint, which broadcasts INJECT_SUGGESTION to SuggestionInjectReceiver.
// The receiver inserts a fresh Report + pending Suggestion with a known
// rationale ("maestro-suggestion-approve") so the Chat/Briefing home renders
// the "Yes, try it" card deterministically.
//
// Helper response shape (on success):
//   { status: "ok", rationale: "maestro-suggestion-approve", action: "suppress" }
// Throws on non-200 status or malformed body to fail the flow.

var resp = http.get('http://127.0.0.1:18765/inject-suggestion');

if (resp.status !== 200) {
  throw 'inject-suggestion helper returned status ' + resp.status + ': ' + resp.body;
}

var result = JSON.parse(resp.body);

if (!result || result.status !== 'ok') {
  throw 'inject-suggestion helper response malformed: ' + resp.body;
}
