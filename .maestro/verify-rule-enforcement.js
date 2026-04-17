// Rule-enforcement assertion. Maestro 2.3.0's GraalJS sandbox does not expose
// Java interop, so adb operations are delegated to a local HTTP helper
// (rule-enforcement-helper.py) started by run-tests.sh around this flow.
//
// Helper endpoint: http://127.0.0.1:18765/verify-suppress
// Helper response shape:
//   { matched: bool, disposition: str|null, notifications: int }
// We assert matched == true AND disposition == "suppressed" on the newest
// trigger NotificationRecord (packageName=com.android.shell,
// title contains 'rulet-trig'). Throw on any mismatch to fail the flow.

var resp = http.get('http://127.0.0.1:18765/verify-suppress');

if (resp.status !== 200) {
  throw 'rule-enforcement helper returned status ' + resp.status + ': ' + resp.body;
}

var result = JSON.parse(resp.body);

if (!result || typeof result.notifications !== 'number') {
  throw 'rule-enforcement helper response malformed: ' + resp.body;
}

if (!result.matched) {
  throw 'No trigger notification found in export (total notifications=' +
        result.notifications + ')';
}

if (result.disposition !== 'suppressed') {
  throw 'Rule enforcement FAILED: expected disposition "suppressed", got "' +
        result.disposition + '"';
}
