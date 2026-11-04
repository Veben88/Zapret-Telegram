import sys
sys.path.insert(0, __import__('os').path.dirname(__file__))
from compare import gt_lines, our_lines
NAMES = ['C','C#','D','Eb','E','F','F#','G','Ab','A','Bb','B']
def nm(m): return f'{NAMES[m%12]}{m//12-1}'
def align(a, b):
    n, m = len(a), len(b)
    D = [[0]*(m+1) for _ in range(n+1)]
    for i in range(n+1): D[i][0] = i
    for j in range(m+1): D[0][j] = j
    for i in range(1, n+1):
        for j in range(1, m+1):
            D[i][j] = min(D[i-1][j]+1, D[i][j-1]+1, D[i-1][j-1]+(a[i-1]!=b[j-1]))
    i, j, ops = n, m, []
    while i > 0 or j > 0:
        if i > 0 and j > 0 and D[i][j] == D[i-1][j-1] + (a[i-1]!=b[j-1]):
            if a[i-1] != b[j-1]: ops.append((i-1, 'заменено', a[i-1], b[j-1]))
            i -= 1; j -= 1
        elif i > 0 and D[i][j] == D[i-1][j] + 1: ops.append((i-1, 'пропущено', a[i-1], None)); i -= 1
        else: ops.append((i, 'лишнее', None, b[j-1])); j -= 1
    return ops[::-1]
xml, tok, st, pid, li = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], int(sys.argv[5])
shift = int(sys.argv[6]) if len(sys.argv) > 6 else 0          # сдвиг в полутонах (ключ «соль с 8» — -12)
g = gt_lines(xml)[pid][li]; o = our_lines(tok, st+1, {st: shift})[st][li]
for i, op, a, b in align(g, o):
    f = lambda x: '-' if x is None else f'{nm(x[0])}/{x[1]}'
    print(f'нота {i:3d}: {op:9s} эталон {f(a):10s} у нас {f(b)}')
