#!/usr/bin/env python3
"""
Helper HTTP server for flow 16 (rule-enforcement E2E).

Maestro 2.3.0's runScript sandbox (GraalJS) does NOT expose Java interop —
`java.lang.Runtime` is undefined. But it does expose an `http` global. This
tiny server bridges adb operations into http calls the JS can make.

Endpoint:
  GET /verify-suppress
    1. adb shell cmd notification post  (trigger notification, title has
       'rulet-trig', packageName=com.android.shell)
    2. sleep 1500ms  (async disposition DB write)
    3. adb shell am broadcast EXPORT_NOTIFICATIONS_PLAINTEXT
    4. sleep 1500ms  (goAsync + IO write)
    5. adb pull /sdcard/Download/lithium-notifications-export.json
    6. parse ExportPayload.notifications, find newest record with
       packageName=='com.android.shell' AND title containing 'rulet-trig'
    7. return {matched, disposition, notifications}

The JS in verify-rule-enforcement.js asserts matched=true and
disposition=='suppressed', throwing otherwise.

Usage:
  rule-enforcement-helper.py [--port 18765]

Lifecycle:
  run-tests.sh starts this server at the top of Phase 3 and kills it after
  the suite completes.
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
EXPECTED_PACKAGE = 'com.android.shell'
EXPORT_FILE_ON_DEVICE = '/sdcard/Download/lithium-notifications-export.json'
DISPOSITION_WAIT_SEC = 1.5
EXPORT_WRITE_WAIT_SEC = 1.5
ADB = os.environ.get('ADB', 'adb')
ANDROID_SERIAL = os.environ.get('ANDROID_SERIAL', '')


def adb(*args, timeout=30):
    cmd = [ADB]
    if ANDROID_SERIAL:
        cmd += ['-s', ANDROID_SERIAL]
    cmd += list(args)
    return subprocess.run(cmd, capture_output=True, timeout=timeout)


def verify_suppress():
    adb('shell', 'cmd', 'notification', 'post',
        '-t', f'rule-enforcement-trigger-{TRIGGER_TAG}',
        'rulet-trigger-tag', 'TriggerApp')
    time.sleep(DISPOSITION_WAIT_SEC)

    # Delete any stale export file. On Android 11+ with scoped storage, the app
    # can CREATE a fresh file in /sdcard/Download but cannot OVERWRITE an existing
    # one it previously wrote (EACCES). The receiver succeeds iff the target
    # path is empty when it fires.
    adb('shell', 'rm', '-f', EXPORT_FILE_ON_DEVICE)

    adb('shell', 'am', 'broadcast',
        '-a', 'ai.talkingrock.lithium.debug.EXPORT_NOTIFICATIONS_PLAINTEXT',
        '-p', 'ai.talkingrock.lithium.debug')
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
                and TRIGGER_TAG in n['title']):
            if match is None or n.get('postedAtMs', 0) > match.get('postedAtMs', 0):
                match = n

    return 200, {
        'matched': match is not None,
        'disposition': (match or {}).get('disposition'),
        'notifications': len(records),
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.rstrip('/') == '/verify-suppress':
            status, body = verify_suppress()
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
