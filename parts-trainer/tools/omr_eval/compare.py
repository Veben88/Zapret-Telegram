import re, sys, xml.etree.ElementTree as ET
from fractions import Fraction as F
STEP = {'C':0,'D':2,'E':4,'F':5,'G':7,'A':9,'B':11}

def gt_lines(path):
    root = ET.parse(path).getroot()
    out = {}
    for part in root.findall('part'):
        pid = part.get('id'); div = 1; t = F(0); notes = []; last_onset = F(0)
        for m in part.findall('measure'):
            for el in m:
                if el.tag == 'attributes':
                    d = el.find('divisions')
                    if d is not None: div = int(d.text)
                elif el.tag == 'backup': t -= F(int(el.find('duration').text), div)
                elif el.tag == 'forward': t += F(int(el.find('duration').text), div)
                elif el.tag == 'note':
                    if el.find('grace') is not None: continue
                    dur = F(int(el.find('duration').text), div) if el.find('duration') is not None else F(0)
                    chord = el.find('chord') is not None
                    onset = last_onset if chord else t
                    p = el.find('pitch')
                    tie_stop = any(x.get('type') == 'stop' for x in el.findall('tie'))
                    if p is not None:
                        midi = (int(p.find('octave').text) + 1) * 12 + STEP[p.find('step').text] + int(float(p.find('alter').text) if p.find('alter') is not None else 0)
                        if tie_stop:
                            # продолжение лиги: удлиняем последнюю ноту той же высоты
                            for k in range(len(notes) - 1, -1, -1):
                                if notes[k][1] == midi: notes[k] = (notes[k][0], midi, notes[k][2] + dur); break
                        else:
                            notes.append((onset, midi, dur))
                    if not chord: last_onset = t; t += dur
        out[pid] = lines(notes)
    return out

def lines(notes):
    by = {}
    for o, m, d in notes: by.setdefault(o, []).append((m, d))
    top = []; bot = []
    for o in sorted(by):
        ns = by[o]
        top.append(max(ns)); bot.append(min(ns))
    return top, bot

KERN = re.compile(r'(note|rest)_(\d+)(G?)(\.*)$')
def tdur(r):
    m = KERN.match(r)
    if not m or m.group(3) == 'G': return None
    k = int(m.group(2)); base = F(8) if k == 0 else F(4, k); d = base; add = base / 2
    for _ in m.group(4): d += add; add /= 2
    return d
def tmidi(p, lift):
    m = re.match(r'([A-G])(\d)$', p or '')
    if not m: return None
    return (int(m.group(2)) + 1) * 12 + STEP[m.group(1)] + {'#':1,'##':2,'b':-1,'bb':-2}.get(lift, 0)

def our_lines(tokfile, nstaff, transpose=None):
    staffs = {}
    for line in open(tokfile, encoding='utf-8'):
        if '\t' not in line: continue
        head, toks = line.rstrip('\n').split('\t')
        pg, sy, st, clef = head.split()
        staffs.setdefault(int(st), []).append(((int(pg), int(sy)), toks.split(' ') if toks else []))
    out = {}
    for st in range(nstaff):
        notes = []; t = F(0)
        for _, toks in sorted(staffs.get(st, [])):
            groups = []; in_chord = False
            for tk in toks:
                r, p, l, pos, sl = (tk.split('|') + ['', '', '', '', ''])[:5]
                if r == 'chord': in_chord = True; continue
                if in_chord and groups: groups[-1].append((r, p, l, pos, sl)); in_chord = False
                else: groups.append([(r, p, l, pos, sl)])
            for g in groups:
                ev = [(x, tdur(x[0])) for x in g if x[0].startswith(('note_', 'rest_'))]
                ev = [(x, d) for x, d in ev if d is not None]
                if not ev: continue
                for x, d in ev:
                    if x[0].startswith('note_'):
                        mm = tmidi(x[1], x[2])
                        if mm is None: continue
                        mm += (transpose or {}).get(st, 0)
                        # лига (как в приложении): slurStop на этой ноте и slurStart на прошлой той же высоты, которая кончается здесь
                        prev = next((k for k in range(len(notes) - 1, -1, -1) if notes[k][1] == mm and notes[k][0] + notes[k][2] == t and notes[k][3]), None) if 'slurStop' in x[4] else None
                        if prev is not None: notes[prev] = (notes[prev][0], mm, notes[prev][2] + d, 'slurStart' in x[4])
                        else: notes.append((t, mm, d, 'slurStart' in x[4]))
                t += min(d for _, d in ev)
        out[st] = lines([(o, m, d) for o, m, d, _ in notes])
    return out

def ed(a, b):
    n, m = len(a), len(b); prev = list(range(m + 1))
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        for j in range(1, m + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[m]

if __name__ == '__main__':
    xml, tok, mapping = sys.argv[1], sys.argv[2], sys.argv[3]   # mapping like "0:P1,1:P2,2:P3"
    gt = gt_lines(xml)
    pairs = [(int(a), b) for a, b in (x.split(':') for x in mapping.split(','))]
    ours = our_lines(tok, max(a for a, _ in pairs) + 1)
    tot = [0, 0, 0]
    for st, pid in pairs:
        for li, name in ((0, 'верх'), (1, 'низ')):
            g = gt[pid][li]; o = ours[st][li]
            ep = ed([m for m, _ in g], [m for m, _ in o]); ef = ed(g, o)
            tot[0] += len(g); tot[1] += ep; tot[2] += ef
            print(f'{pid} строка {st} {name}: эталон {len(g)} нот, у нас {len(o)}; высоты верно {100*(1-ep/max(1,len(g))):.1f}%, высоты+длительности {100*(1-ef/max(1,len(g))):.1f}%')
    print(f'ИТОГО: нот {tot[0]}, высоты {100*(1-tot[1]/tot[0]):.1f}%, высоты+длительности {100*(1-tot[2]/tot[0]):.1f}%')
