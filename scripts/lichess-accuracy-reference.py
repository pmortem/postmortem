"""A direct port of lila's AccuracyPercent.gameAccuracy, AccuracyCP and the cp part of Advice, run on the [%eval] values of a PGN.

Usage: lichess-accuracy-reference.py <annotated.pgn>   (no mates: the regexp only reads cp evals, White moves first)
It is the independent reference the expected numbers in LichessAccuracyTest come from."""
import math, re, sys

def win_percent(cp):
    cp = max(-1000, min(1000, cp))
    return 50 + 50 * (2 / (1 + math.exp(-0.00368208 * cp)) - 1)

def acc(before, after):
    if after >= before:
        return 100.0
    raw = 103.1668100711649 * math.exp(-0.04354415386753951 * (before - after)) - 3.166924740191411
    return max(0.0, min(100.0, raw + 1))

def std(xs):
    m = sum(xs) / len(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / len(xs))

def game_accuracy(cps):  # cps: [initial] + after each ply, white-pov centipawns; white moves first
    wps = [win_percent(c) for c in cps]
    size = max(2, min(8, (len(cps) - 1) // 10))
    windows = [wps[:size]] * (min(size, len(wps)) - 2) + ([wps] if len(wps) < size else [wps[i:i + size] for i in range(len(wps) - size + 1)])
    weights = [max(0.5, min(12.0, std(w))) for w in windows]
    out = {True: [], False: []}
    for i, (p, n) in enumerate(zip(wps, wps[1:])):
        white = i % 2 == 0
        a = acc(p, n) if white else acc(n, p)
        out[white].append((a, weights[i]))
    res = {}
    for white, xs in out.items():
        weighted = sum(a * w for a, w in xs) / sum(w for _, w in xs)
        harmonic = len(xs) / sum(1 / a for a, _ in xs)
        res[white] = (weighted + harmonic) / 2
    return res

def acpl(cps, white):
    cl = lambda c: max(-1000, min(1000, c))
    losses = []
    for i in range(0 if white else 1, len(cps) - 1, 2):
        d = (cl(cps[i + 1]) - cl(cps[i])) * (-1 if white else 1)
        losses.append(max(0, d))
    return round(sum(losses) / len(losses))

text = open(sys.argv[1]).read()
evals = [int(round(float(m) * 100)) for m in re.findall(r"\[%eval (-?\d+(?:\.\d+)?)\]", text)]
cps = [15] + evals
r = game_accuracy(cps)
print("plies", len(evals))
print("White accuracy %.4f ACPL %d" % (r[True], acpl(cps, True)))
print("Black accuracy %.4f ACPL %d" % (r[False], acpl(cps, False)))
