#!/usr/bin/env python3
"""
Helper HTTP server for flow 16 (rule-enforcement E2E), flow 17 (queue-
enforcement E2E), and flow 18 (suggestion-approve E2E).

Maestro 2.3.0's runScript sandbox (GraalJS) does NOT expose Java interop —
`java.lang.Runtime` is undefined. But it does expose an `http` global. This
tiny server bridges adb operations into http calls the JS can make.

Endpoints:
  GET /verify-suppress
    Trigger tag 'rulet-trig'. Caller's JS asserts disposition=='suppressed'.
  GET /verify-queue
    Trigger tag 'qt-trig'. Caller's JS asserts disposition=='queued'.
  GET /inject-suggestion
    Fires INJECT_SUGGESTION broadcast at SuggestionInjectReceiver with
    rationale/action/conditionJson extras so flow 18 can deterministically
    cause a "Yes, try it" card to render without real LLM analysis.

verify-suppress/verify-queue pipeline:
    1. adb shell cmd notification post  (trigger notification, title has the
       tag, packageName=com.android.shell)
    2. sleep 1500ms  (async disposition DB write)
    3. adb shell rm -f <export file>  (scoped-storage EACCES workaround —
       app cannot overwrite an existing file it previously created)
    4. adb shell am broadcast EXPORT_NOTIFICATIONS_PLAINTEXT
    5. sleep 1500ms  (goAsync + IO write)
    6. adb pull /sdcard/Download/lithium-notifications-export.json
    7. parse ExportPayload.notifications, find newest record with
       packageName=='com.android.shell' AND title containing the trigger tag
    8. return {matched, disposition, notifications}

inject-suggestion pipeline:
    1. adb shell am broadcast INJECT_SUGGESTION with --es extras for rationale,
       action, conditionJson
    2. sleep 1000ms  (receiver goAsync + Room insertReport + insertSuggestions)
    3. return {status: "ok", rationale, action}

Usage:
  rule-enforcement-helper.py [--port 18765]

Lifecycle:
  run-tests.sh starts this server after Phase 2.5 and kills it on EXIT trap.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

TRIGGER_TAG = 'rulet-trig'
QUEUE_TRIGGER_TAG = 'qt-trig'
EXPECTED_PACKAGE = 'com.android.shell'
EXPORT_FILE_ON_DEVICE = '/sdcard/Download/lithium-notifications-export.json'
EXPORT_ACTION = 'ai.talkingrock.lithium.debug.EXPORT_NOTIFICATIONS_PLAINTEXT'
INJECT_SUGGESTION_ACTION = 'ai.talkingrock.lithium.debug.INJECT_SUGGESTION'
DEBUG_PACKAGE = 'ai.talkingrock.lithium.debug'
DISPOSITION_WAIT_SEC = 1.5
EXPORT_WRITE_WAIT_SEC = 1.5
INJECT_WAIT_SEC = 1.0
DEFAULT_INJECT_RATIONALE = 'maestro-suggestion-approve'
DEFAULT_INJECT_ACTION = 'suppress'
DEFAULT_INJECT_CONDITION = '{"type":"package_match","packageName":"com.android.shell"}'
ADB = os.environ.get('ADB', 'adb')
ANDROID_SERIAL = os.environ.get('ANDROID_SERIAL', '')


def adb(*args, timeout=30):
    cmd = [ADB]
    if ANDROID_SERIAL:
        cmd += ['-s', ANDROID_SERIAL]
    cmd += list(args)
    return subprocess.run(cmd, capture_output=True, timeout=timeout)


def _do_adb_broadcast(action, package_name, extras=None):
    """Fire an `adb shell am broadcast` with the given action, package target,
    and optional string extras (dict of key → value, rendered as `--es <k> <v>`).
    Returns the completed subprocess result; caller decides whether to inspect
    the return code / stderr.
    """
    args = ['shell', 'am', 'broadcast', '-a', action, '-p', package_name]
    for k, v in (extras or {}).items():
        args += ['--es', k, v]
    return adb(*args)


def _do_trigger_and_export(trigger_tag):
    """Post a notification with `trigger_tag` in its title, broadcast the
    DbExportReceiver, pull the resulting JSON, and return the newest
    matching record's disposition.

    The returned `disposition` can be any value the engine wrote: "suppressed"
    from a Suppress rule, "queued" from a Queue rule, "allowed" when no user
    rule matches, etc. The caller's JS decides what value is acceptable.
    """
    adb('shell', 'cmd', 'notification', 'post',
        '-t', f'trigger-{trigger_tag}',
        f'{trigger_tag}-tag', 'TriggerApp')
    time.sleep(DISPOSITION_WAIT_SEC)

    # Delete any stale export file. On Android 11+ with scoped storage, the app
    # can CREATE a fresh file in /sdcard/Download but cannot OVERWRITE an existing
    # one it previously wrote (EACCES). The receiver succeeds iff the target
    # path is empty when it fires.
    adb('shell', 'rm', '-f', EXPORT_FILE_ON_DEVICE)

    _do_adb_broadcast(EXPORT_ACTION, DEBUG_PACKAGE)
    time.sleep(EXPORT_WRITE_WAIT_SEC)

    with tempfile.NamedTemporaryFile(suffix='.json', delete=False) as tf:
        local_path = tf.name
    try:
        pull = adb('pull', EXPORT_FILE_ON_DEVICE, local_path)
        if pull.returncode != 0:
            return 502, {
                'error': 'adb pull failed',
                'stderr': pull.stderr.decode(errors='replace'),
            }
        with open(local_path, 'r') as f:
            payload = json.load(f)
    finally:
        try:
            os.unlink(local_path)
        except OSError:
            pass

    records = payload.get('notifications') or []
    match = None
    for n in records:
        if (n.get('packageName') == EXPECTED_PACKAGE
                and n.get('title')
                and trigger_tag in n['title']):
            if match is None or n.get('postedAtMs', 0) > match.get('postedAtMs', 0):
                match = n

    return 200, {
        'matched': match is not None,
        'disposition': (match or {}).get('disposition'),
        'notifications': len(records),
    }


def verify_suppress():
    """Flow 16 endpoint. Caller's JS asserts disposition=='suppressed'."""
    return _do_trigger_and_export(TRIGGER_TAG)


def verify_queue():
    """Flow 17 endpoint. Caller's JS asserts disposition=='queued'."""
    return _do_trigger_and_export(QUEUE_TRIGGER_TAG)


def inject_suggestion():
    """Flow 18 endpoint. Fires INJECT_SUGGESTION at SuggestionInjectReceiver
    with default rationale/action/conditionJson, waits for the async Room
    write, and returns the injected rationale+action so the caller's JS can
    confirm the request reached the helper.

    The receiver inserts a Report (via markAllReviewed + insertReport) and a
    pending Suggestion referencing that report's id — together these cause
    BriefingViewModel to emit a non-empty suggestions list, which renders
    the "Yes, try it" card on the Chat/Briefing home.
    """
    # am broadcast's --es parsing on-device mangles values containing `:` into
    # the intent's data URI ("dat=packageName:" in the logs), which corrupts
    # the overall intent extras. The receiver's DEFAULT_CONDITION (declared in
    # SuggestionInjectReceiver.kt) matches exactly what we want for flow 18
    # (rationale="maestro-suggestion-approve", action="suppress", conditionJson
    # = PackageMatch com.android.shell), so we skip passing extras entirely
    # and let the receiver use its defaults. This avoids an on-device shell
    # escaping round-trip.
    rationale = DEFAULT_INJECT_RATIONALE
    action = DEFAULT_INJECT_ACTION
    result = _do_adb_broadcast(INJECT_SUGGESTION_ACTION, DEBUG_PACKAGE)
    time.sleep(INJECT_WAIT_SEC)
    if result.returncode != 0:
        return 502, {
            'error': 'adb broadcast failed',
            'stderr': result.stderr.decode(errors='replace'),
        }
    return 200, {
        'status': 'ok',
        'rationale': rationale,
        'action': action,
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.rstrip('/')
        if path == '/verify-suppress':
            status, body = verify_suppress()
        elif path == '/verify-queue':
            status, body = verify_queue()
        elif path == '/inject-suggestion':
            status, body = inject_suggestion()
        else:
            status, body = 404, {'error': 'unknown endpoint', 'path': self.path}
        raw = json.dumps(body).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *_args, **_kw):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=18765)
    args = ap.parse_args()
    srv = HTTPServer(('127.0.0.1', args.port), Handler)
    print(
        f'[rule-enforcement-helper] listening on 127.0.0.1:{args.port} '
        f'(ANDROID_SERIAL={ANDROID_SERIAL or "<default>"})',
        flush=True,
    )
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    except Exception as e:
        print(f'[rule-enforcement-helper] fatal: {e}', file=sys.stderr, flush=True)
        sys.exit(1)


if __name__ == '__main__':
    main()
