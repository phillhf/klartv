"""Prüft die Übersetzungen.

python tools/texte.py          fehlende und überflüssige Einträge in assets/lang
python tools/texte.py roh      Texte im Quelltext, die noch nicht durch t(...) laufen
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main')
CALL = re.compile(r'(?<![A-Za-z0-9_.])t\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)')
PART = re.compile(r'"((?:[^"\\]|\\.)*)"')
# Texte, die über Variablen in t() gelangen
EXTRA = ['Start', 'Live-TV', 'Programm', 'Filme', 'Serien', 'Favoriten', 'Mediathek', 'Suche', 'Einstellungen', 'Alle', 'Sonstige', 'Ohne Namen', 'Ohne Gruppe',
         'Nachtblau', 'Reines Schwarz', 'Violett', 'Smaragd', 'Abendrot', 'Graphit', 'unbekannt', 'verpasst', 'Angehalten', 'Es kam kein Bild an.',
         'Tonformat nicht unterstützt']


def sources():
    for p in sorted(glob.glob(os.path.join(ROOT, 'java', '**', '*.kt'), recursive=True)):
        yield p, open(p, encoding='utf-8').read()


def keys():
    found = list(EXTRA)
    for _, s in sources():
        for m in CALL.finditer(s):
            text = ''.join(PART.findall(m.group(1))).replace('\\"', '"').replace('\\n', '\n')
            if text not in found:
                found.append(text)
    return found


def main():
    if 'roh' in sys.argv:
        for p, s in sources():
            for i, line in enumerate(s.splitlines(), 1):
                code = line.split('//')[0] if '://' not in line else line
                if code.strip().startswith('*') or code.strip().startswith('/*'):
                    continue
                for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', code):
                    text = m.group(1)
                    before = code[:m.start()]
                    if re.search(r'(?<![A-Za-z0-9_.])t\(\s*$', before) or 'label = "' in code and 'animate' in code:
                        continue
                    if re.search(r'[äöüÄÖÜß…]|[A-Z][a-zäöü]+ [a-zäöü]+', text) and not text.startswith('http'):
                        print(f'{os.path.relpath(p, ROOT)}:{i}: {text}')
        return
    want = keys()
    if 'liste' in sys.argv:
        print(json.dumps(want, ensure_ascii=False, indent=0))
        return
    bad = False
    for p in sorted(glob.glob(os.path.join(ROOT, 'assets', 'lang', '*.json'))):
        have = json.load(open(p, encoding='utf-8'))
        missing = [k for k in want if k not in have]
        extra = [k for k in have if k not in want]
        marks = [k for k in want if k in have and set(re.findall(r'%\d', k)) != set(re.findall(r'%\d', have[k]))]
        bad = bad or bool(missing or marks)
        print(os.path.basename(p), f'{len(have)} Einträge, {len(missing)} fehlen, {len(extra)} überflüssig, {len(marks)} mit falschen Platzhaltern')
        for k in missing + marks:
            print('   ', k)
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
