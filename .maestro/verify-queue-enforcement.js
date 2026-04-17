// Queue-enforcement assertion. Parallel structure to verify-rule-enforcement.js
// but for the Queue action path. Delegates adb ops to the local HTTP helper
// (rule-enforcement-helper.py's /verify-queue endpoint) because Maestro 2.x's
// GraalJS sandbox has no Java interop.
//
// Trigger title marker: 'qt-trig' (distinct from flow 16's 'rulet-trig').
// Helper endpoint: http://127.0.0.1:18765/verify-queue
// Helper response shape:
//   { matched: bool, disposition: str|null, notifications: int }
// Asserts matched == true AND disposition == "queued" on the newest
// trigger NotificationRecord (packageName=com.android.shell, title contains
// 'qt-trig'). Throws on any mismatch to fail the flow.

var resp = http.get('http://127.0.0.1:18765/verify-queue');

if (resp.status !== 200) {
  throw 'queue-enforcement helper returned status ' + resp.status + ': ' + resp.body;
}

var result = JSON.parse(resp.body);

if (!result || typeof result.notifications !== 'number') {
  throw 'queue-enforcement helper response malformed: ' + resp.body;
}

if (!result.matched) {
  throw 'No qt-trig trigger notification found in export (total notifications=' +
        result.notifications + ')';
}

if (result.disposition !== 'queued') {
  throw 'Queue enforcement FAILED: expected disposition "queued", got "' +
        result.disposition + '"';
}
