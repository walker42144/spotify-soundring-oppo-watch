"""Local-only PKCE setup. No credentials are written to disk or printed."""
import base64
import hashlib
import http.server
import json
import os
import re
import secrets
import subprocess
import time
import urllib.parse
import urllib.request
import webbrowser
from getpass import getpass
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

REDIRECT = 'http://127.0.0.1:8765/callback'
SCOPES = 'user-read-playback-state user-modify-playback-state playlist-read-private playlist-read-collaborative'

def b64(data):
    return base64.b64encode(data).decode('ascii')

def request_json(url, *, headers=None, data=None):
    request = urllib.request.Request(url, data=data, headers=headers or {})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)

def envelope(payload, public_key):
    key = AESGCM.generate_key(bit_length=128)
    iv = os.urandom(12)
    public = serialization.load_der_public_key(base64.b64decode(public_key))
    wrapped = public.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    data = AESGCM(key).encrypt(iv, json.dumps(payload).encode('utf-8'), None)
    return json.dumps({'key': b64(wrapped), 'iv': b64(iv), 'data': b64(data)}).encode('ascii')

def main():
    print('声环 SoundRing for Spotify：Windows 本地配置工具')
    print('请先在 Spotify Developer 应用中添加 Redirect URI：' + REDIRECT)
    print('不需要 Client Secret。请在手表打开应用并点击“电脑配对”。')
    client = input('Client ID：').strip()
    if not re.fullmatch('[a-fA-F0-9]{32}', client):
        raise ValueError('Client ID 应为 32 位十六进制字符')
    adb = Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'
    if not adb.is_file():
        adb = Path(input('adb.exe 完整路径：').strip().strip('"'))
    devices = subprocess.check_output([str(adb), 'devices'], text=True)
    ready = [line.split()[0] for line in devices.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == 'device']
    if len(ready) != 1:
        raise RuntimeError('请只连接这一台手表，或断开其他调试设备后重试')
    code = getpass('手表显示的 8 位配对码（输入隐藏）：').strip()
    if not re.fullmatch(r'\d{8}', code):
        raise ValueError('配对码需要 8 位数字')
    base = [str(adb), '-s', ready[0]]
    # A dynamically assigned host port avoids disrupting existing forwards.
    port = subprocess.check_output(base + ['forward', 'tcp:0', 'tcp:18765'], text=True).strip()
    if not port.isdigit():
        raise RuntimeError('无法建立本地调试通道')
    try:
        local = 'http://127.0.0.1:' + port
        headers = {'Authorization': 'Bearer ' + code}
        session = request_json(local + '/session', headers=headers)
        verifier = secrets.token_urlsafe(64)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode('ascii')).digest()).rstrip(b'=').decode('ascii')
        state = secrets.token_urlsafe(32)
        outcome = {}
        class Callback(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass  # OAuth query contains an authorization code.
            def do_GET(self):
                parsed = urllib.parse.urlsplit(self.path)
                query = urllib.parse.parse_qs(parsed.query)
                valid = parsed.path == '/callback' and secrets.compare_digest(query.get('state', [''])[0], state)
                if valid:
                    if 'error' in query:
                        outcome['error'] = '授权被拒绝'
                    elif query.get('code', [''])[0]:
                        outcome['code'] = query['code'][0]
                self.send_response(200 if valid else 400)
                self.send_header('Content-Type', 'text/plain; charset=utf-8')
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(('已收到授权，请返回配置工具。' if valid else '无效回调。').encode('utf-8'))
        with http.server.HTTPServer(('127.0.0.1', 8765), Callback) as server:
            server.timeout = 1
            url = 'https://accounts.spotify.com/authorize?' + urllib.parse.urlencode({
                'client_id': client, 'response_type': 'code', 'redirect_uri': REDIRECT,
                'scope': SCOPES, 'code_challenge_method': 'S256', 'code_challenge': challenge, 'state': state,
            })
            webbrowser.open(url)
            print('已打开官方登录页面，请在 4 分钟内完成授权。')
            deadline = time.monotonic() + 240
            while not outcome and time.monotonic() < deadline:
                server.handle_request()
        if 'code' not in outcome:
            raise RuntimeError(outcome.get('error', '授权超时，请重新配对'))
        tokens = request_json('https://accounts.spotify.com/api/token', data=urllib.parse.urlencode({
            'grant_type': 'authorization_code', 'code': outcome['code'], 'redirect_uri': REDIRECT,
            'client_id': client, 'code_verifier': verifier,
        }).encode('ascii'), headers={'Content-Type': 'application/x-www-form-urlencoded'})
        payload = {'client_id': client, 'access_token': tokens['access_token'],
                   'refresh_token': tokens['refresh_token'], 'expires_at': int(time.time() * 1000) + int(tokens['expires_in']) * 1000}
        encrypted = envelope(payload, session['public_key'])
        result = request_json(local + '/provision', headers={**headers, 'Content-Type': 'application/json'}, data=encrypted)
        if result.get('ok') is not True:
            raise RuntimeError('手表未确认保存授权')
        print('配置成功。电脑无需保持运行。请在手机或电脑打开 Spotify，再查看手表上的状态。')
    finally:
        subprocess.run(base + ['forward', '--remove', 'tcp:' + port], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\n已取消。')
    except Exception as exc:
        # Never print HTTP response bodies, credential-bearing URLs or tracebacks.
        if isinstance(exc, urllib.error.HTTPError):
            print('配置失败：HTTP', exc.code, '。请检查 Redirect URI、白名单和配对码，然后重试。')
        else:
            print('配置失败：', str(exc) if isinstance(exc, (ValueError, RuntimeError)) else type(exc).__name__)
