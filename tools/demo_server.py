"""Nachgebauter Xtream-Anbieter für die Emulator-Tests. Alle Inhalte sind Testbilder von ffmpeg.

Aufruf: python3 tools/demo_server.py [Port]   (Anmeldung: demo / demo)
"""
import base64
import gzip
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
WORK = os.environ.get('DEMO_MEDIA') or os.path.join(tempfile.gettempdir(), 'klartv-demo')
CHANNELS = ['Alpha HD', 'Nord Eins', 'Sport Arena', 'Doku Welt']
MOVIES = [('Sternenstaub', 'mp4'), ('Dolby Nacht', 'mkv')]


def ffmpeg(*args):
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', *args], check=True)


def prepare():
    os.makedirs(os.path.join(WORK, 'hls'), exist_ok=True)
    video = ['-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000']
    x264 = ['-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-g', '25', '-b:v', '600k']
    ffmpeg(*video, '-t', '60', *x264, '-c:a', 'aac', '-f', 'mpegts', f'{WORK}/live.ts')
    ffmpeg(*video, '-t', '120', *x264, '-c:a', 'aac', '-movflags', '+faststart', f'{WORK}/movie0.mp4')
    # Dolby-Ton kann der Emulator nicht selbst dekodieren: prüft den Wechsel zu VLC
    srt = os.path.join(WORK, 'sub.srt')
    with open(srt, 'w', encoding='utf-8') as f:
        for i in range(29):
            f.write(f'{i + 1}\n00:00:{2 * i:02d},000 --> 00:00:{2 * i + 1:02d},800\nUntertitel {i + 1} mit Umlauten äöü\n\n')
    ffmpeg(*video, '-i', srt, '-t', '60', '-map', '0:v', '-map', '1:a', '-map', '2:s', *x264, '-c:a', 'eac3', '-c:s', 'srt',
           '-metadata:s:a:0', 'language=deu', '-metadata:s:s:0', 'language=deu', f'{WORK}/movie1.mkv')
    subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-re', '-stream_loop', '-1', '-i', f'{WORK}/live.ts', '-c', 'copy', '-f', 'hls',
                      '-hls_time', '2', '-hls_list_size', '6', '-hls_flags', 'delete_segments', f'{WORK}/hls/live.m3u8'])


def b64(s):
    return base64.b64encode(s.encode()).decode()


def programmes():
    """Halbstündige Sendungen von gestern bis morgen."""
    start = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(days=1)
    return [(start + timedelta(minutes=30 * i), start + timedelta(minutes=30 * (i + 1)), f'Sendung {i + 1}') for i in range(96)]


def xmltv():
    out = ['<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE tv SYSTEM "xmltv.dtd"><tv>']
    for i, name in enumerate(CHANNELS):
        out.append(f'<channel id="ch{i}.demo"><display-name>{name}</display-name></channel>')
    for i in range(len(CHANNELS)):
        for s, e, title in programmes():
            fmt = '%Y%m%d%H%M%S +0000'
            out.append(f'<programme start="{s.strftime(fmt)}" stop="{e.strftime(fmt)}" channel="CH{i}.demo">'
                       f'<title lang="de">{title} &amp; mehr</title><desc lang="de">Beschreibung zu {title}.</desc></programme>')
    out.append('</tv>')
    return ''.join(out).encode()


def api(q):
    action = q.get('action', [''])[0]
    if q.get('username', [''])[0] != 'demo' or q.get('password', [''])[0] != 'demo':
        return {'user_info': {'auth': 0}}
    if action == '':
        return {'user_info': {'auth': 1, 'status': 'Active', 'exp_date': str(int(time.time()) + 86400 * 90), 'max_connections': '4', 'active_cons': '0'},
                'server_info': {'timezone': 'Europe/Berlin'}}
    if action == 'get_live_categories':
        return [{'category_id': '1', 'category_name': 'Unterhaltung'}, {'category_id': '2', 'category_name': 'FSK 18'}]
    if action == 'get_live_streams':
        return [{'num': i + 1, 'name': n, 'stream_id': 100 + i, 'stream_icon': '', 'epg_channel_id': f'ch{i}.demo',
                 'category_id': '2' if i == 3 else '1', 'tv_archive': 1 if i == 0 else 0, 'tv_archive_duration': '2'} for i, n in enumerate(CHANNELS)]
    if action == 'get_vod_categories':
        return [{'category_id': '10', 'category_name': 'Neu'}]
    if action == 'get_vod_streams':
        return [{'stream_id': 200 + i, 'name': n, 'container_extension': ext, 'category_id': '10', 'rating': '7.1', 'added': '1760000000'} for i, (n, ext) in enumerate(MOVIES)]
    if action == 'get_vod_info':
        return {'info': {'plot': 'Ein Testfilm.', 'genre': 'Drama', 'releasedate': '2026-01-01', 'backdrop_path': [], 'youtube_trailer': 'abcdefghijk'}}
    if action == 'get_series_categories':
        return [{'category_id': '20', 'category_name': 'Krimi'}]
    if action == 'get_series':
        return [{'series_id': 300, 'name': 'Hafenwache', 'category_id': '20', 'plot': 'Eine Testserie.', 'last_modified': '1760000000'}]
    if action == 'get_series_info':
        return {'info': {'plot': 'Eine Testserie.'}, 'episodes': {'1': [{'id': 400 + e, 'episode_num': e, 'title': f'Folge {e}', 'container_extension': 'mp4', 'season': 1, 'info': []} for e in (1, 2)]}}
    if action in ('get_short_epg', 'get_simple_data_table'):
        now = datetime.now(timezone.utc)
        rows = [p for p in programmes() if action != 'get_short_epg' or p[1] > now][:8 if action == 'get_short_epg' else 200]
        return {'epg_listings': [{'title': b64(t), 'description': b64('Beschreibung'), 'start_timestamp': str(int(s.timestamp())), 'stop_timestamp': str(int(e.timestamp()))} for s, e, t in rows]}
    return []


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        print(self.address_string(), fmt % args, flush=True)

    def body(self, data, kind):
        self.send_response(200)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(data)

    def file(self, path, kind):
        if not os.path.exists(path):
            return self.send_error(404)
        size = os.path.getsize(path)
        first, last, code = 0, size - 1, 200
        m = re.match(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
        if m:
            first = int(m.group(1))
            last = int(m.group(2)) if m.group(2) else last
            code = 206
        self.send_response(code)
        self.send_header('Content-Type', kind)
        self.send_header('Accept-Ranges', 'bytes')
        if code == 206:
            self.send_header('Content-Range', f'bytes {first}-{last}/{size}')
        self.send_header('Content-Length', str(last - first + 1))
        self.end_headers()
        if self.command == 'HEAD':
            return
        with open(path, 'rb') as f:
            f.seek(first)
            left = last - first + 1
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    def live(self):
        self.send_response(200)
        self.send_header('Content-Type', 'video/mp2t')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        p = subprocess.Popen(['ffmpeg', '-loglevel', 'error', '-re', '-stream_loop', '-1', '-i', f'{WORK}/live.ts', '-c', 'copy', '-f', 'mpegts', 'pipe:1'],
                             stdout=subprocess.PIPE, stdin=subprocess.DEVNULL)
        try:
            while chunk := p.stdout.read(4096):
                self.wfile.write(chunk)
        finally:
            p.kill()

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        u = urlparse(self.path)
        try:
            if u.path == '/player_api.php':
                self.body(json.dumps(api(parse_qs(u.query))).encode(), 'application/json')
            elif u.path == '/xmltv.php':
                self.body(gzip.compress(xmltv()), 'application/octet-stream')
            elif u.path == '/hls.m3u8' or re.match(r'/live/demo/demo/\d+\.m3u8$', u.path):
                base = f'http://{self.headers["Host"]}/hls/'
                text = open(f'{WORK}/hls/live.m3u8').read()
                self.body(re.sub(r'(?m)^(live\d+\.ts)$', lambda m: base + m.group(1), text).encode(), 'application/vnd.apple.mpegurl')
            elif m := re.match(r'/hls/(live\d+\.ts)$', u.path):
                self.file(f'{WORK}/hls/{m.group(1)}', 'video/mp2t')
            elif re.match(r'/live/demo/demo/\d+\.ts$', u.path):
                self.live()
            elif re.match(r'/timeshift/demo/demo/\d+/\d{4}-\d\d-\d\d:\d\d-\d\d/\d+\.ts$', u.path):
                self.file(f'{WORK}/live.ts', 'video/mp2t')
            elif m := re.match(r'/movie/demo/demo/(\d+)\.(mp4|mkv)$', u.path):
                i = int(m.group(1)) - 200
                self.file(f'{WORK}/movie{i}.{m.group(2)}', 'video/mp4' if m.group(2) == 'mp4' else 'video/x-matroska')
            elif re.match(r'/series/demo/demo/\d+\.mp4$', u.path):
                self.file(f'{WORK}/movie0.mp4', 'video/mp4')
            else:
                self.send_error(404)
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == '__main__':
    prepare()
    print('bereit', flush=True)
    ThreadingHTTPServer(('', PORT), Handler).serve_forever()
