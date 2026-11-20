#!/usr/bin/env python3
"""Drive the NFC Bridge app on an Android phone from a computer, over adb.

Each run: `adb forward` the bridge port, read the per-launch token with
`run-as` (debug build only), authenticate, send one command, print the JSON reply.
Exit status 1 when the bridge answers ok:false.

  nfcb.py launch                      open the app on the phone
  nfcb.py status
  nfcb.py show "Hold card 1 to the back of the phone"
  nfcb.py wait [--timeout 60] [--current]
  nfcb.py info | read
  nfcb.py write-uri https://example.com
  nfcb.py write-text "hello" [--lang en]
  nfcb.py write-ha-tag [TAG_ID]       Home Assistant tag (companion app fires tag_scanned)
  nfcb.py raw NfcA 3004               any tech: NfcA NfcB NfcF NfcV IsoDep MifareUltralight MifareClassic
  nfcb.py mfc-read 4 [--key FFFFFFFFFFFF] [--key-b]
  nfcb.py json '{"cmd":"status"}'     anything else, verbatim
"""
import argparse
import itertools
import json
import os
import socket
import subprocess
import sys
import uuid

_SDK_ADB = os.path.expanduser('~/Android/Sdk/platform-tools/adb')
ADB = os.environ.get('NFCB_ADB') or (_SDK_ADB if os.path.exists(_SDK_ADB) else 'adb')
ADB_PORT = os.environ.get('NFCB_ADB_PORT')  # set to use a non-default adb server port
PKG = 'io.blaha.nfcbridge'
REMOTE = 47800
LOCAL = int(os.environ.get('NFCB_LOCAL_PORT', '47800'))


def adb(*args, serial=None):
    cmd = [ADB] + (['-P', ADB_PORT] if ADB_PORT else []) + (['-s', serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f'adb {" ".join(args)} failed: {r.stderr.strip() or r.stdout.strip()}')
    return r.stdout


class Bridge:
    def __init__(self, serial=None):
        self.serial = serial or os.environ.get('NFCB_SERIAL')
        adb('forward', f'tcp:{LOCAL}', f'tcp:{REMOTE}', serial=self.serial)
        token = adb('shell', 'run-as', PKG, 'cat', 'files/token', serial=self.serial).strip()
        if len(token) != 32:
            sys.exit(f'no bridge token ({token!r}); is the app installed and open? try: nfcb.py launch')
        try:
            self.sock = socket.create_connection(('127.0.0.1', LOCAL), timeout=10)
        except OSError as e:
            sys.exit(f'cannot reach the bridge: {e}; open the app (nfcb.py launch)')
        self.f = self.sock.makefile('rw', encoding='utf-8', newline='\n')
        self.ids = itertools.count(1)
        r = self.call('auth', token=token)
        if not r.get('ok'):
            sys.exit(f'auth failed: {r}')

    def call(self, cmd, wait_s=15, **kw):
        req = {'id': next(self.ids), 'cmd': cmd, **kw}
        self.sock.settimeout(wait_s)
        self.f.write(json.dumps(req) + '\n')
        self.f.flush()
        line = self.f.readline()
        if not line:
            sys.exit('bridge closed the connection (app closed or a new launch rotated the token)')
        return json.loads(line)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('-s', '--serial')
    sub = p.add_subparsers(dest='op', required=True)
    sub.add_parser('launch')
    sub.add_parser('status')
    sub.add_parser('info')
    sub.add_parser('read')
    sub.add_parser('close')
    s = sub.add_parser('show'); s.add_argument('text')
    s = sub.add_parser('wait'); s.add_argument('--timeout', type=float, default=60)
    s.add_argument('--current', action='store_true', help='accept a tag already on the phone')
    s = sub.add_parser('write-uri'); s.add_argument('uri')
    s = sub.add_parser('write-text'); s.add_argument('text'); s.add_argument('--lang', default='en')
    s = sub.add_parser('write-ha-tag'); s.add_argument('tag_id', nargs='?')
    s = sub.add_parser('raw'); s.add_argument('tech'); s.add_argument('hex')
    s.add_argument('--allow-lock', action='store_true'); s.add_argument('--allow-config', action='store_true')
    s.add_argument('--timeout-ms', type=int, default=0)
    s = sub.add_parser('mfc-read'); s.add_argument('block', type=int)
    s.add_argument('--key', default='FFFFFFFFFFFF'); s.add_argument('--key-b', action='store_true')
    s = sub.add_parser('json'); s.add_argument('request')
    a = p.parse_args()

    if a.op == 'launch':
        print(adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity', serial=a.serial).strip())
        return

    b = Bridge(a.serial)
    if a.op in ('status', 'info', 'close'):
        r = b.call(a.op)
    elif a.op == 'read':
        r = b.call('read_ndef')
    elif a.op == 'show':
        r = b.call('show', text=a.text)
    elif a.op == 'wait':
        r = b.call('wait_tag', wait_s=a.timeout + 10, timeout_ms=int(a.timeout * 1000),
                   accept_current=a.current)
    elif a.op == 'write-uri':
        r = b.call('write_ndef', records=[{'kind': 'uri', 'value': a.uri}])
    elif a.op == 'write-text':
        r = b.call('write_ndef', records=[{'kind': 'text', 'value': a.text, 'lang': a.lang}])
    elif a.op == 'write-ha-tag':
        tag_id = a.tag_id or str(uuid.uuid4())
        # Same layout the Home Assistant companion app writes: the tag URI plus an Android
        # Application Record so the tap opens the HA app rather than a browser.
        r = b.call('write_ndef', records=[
            {'kind': 'uri', 'value': f'https://www.home-assistant.io/tag/{tag_id}'},
            {'kind': 'aar', 'value': 'io.homeassistant.companion.android'}])
        r['ha_tag_id'] = tag_id
    elif a.op == 'raw':
        r = b.call('transceive', tech=a.tech, hex=a.hex, allow_lock=a.allow_lock,
                   allow_config=a.allow_config, timeout_ms=a.timeout_ms)
    elif a.op == 'mfc-read':
        r = b.call('mfc_read', block=a.block, key_hex=a.key, key_type='B' if a.key_b else 'A')
    elif a.op == 'json':
        req = json.loads(a.request)
        r = b.call(req.pop('cmd'), wait_s=req.pop('wait_s', 30), **req)
    print(json.dumps(r, indent=2))
    sys.exit(0 if r.get('ok') else 1)


if __name__ == '__main__':
    main()
