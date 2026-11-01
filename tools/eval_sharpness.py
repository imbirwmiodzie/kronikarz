#!/usr/bin/env python3
"""
Jak dobrze miara ostrości rozpoznaje ostrzejsze ujęcia na VHS?

Wymaga dwóch ekstrakcji tego samego filmu (tools/extract_faces.py): czystej (--out A) i z symulacją
VHS (--vhs --out B) oraz etykiet osób (labels.json w A). Wzorzec ostrości = wariancja Laplasjanu
tej samej twarzy w czystej wersji (stała wysokość 128 px). Mierzymy:
  - pary: jak często miara na VHS porządkuje dwa ujęcia tej samej osoby tak jak wzorzec
    (pary, w których wzorzec różni się o > 30%),
  - okna: czy w 3 sąsiednich próbkach (sytuacja „szukania najostrzejszej klatki”) miara wybiera
    najostrzejsze / nie wybiera najgorszego (okna z realną różnicą ostrości).

  tools/.venv/bin/python tools/eval_sharpness.py tools/out/film_hd tools/out/film_vhs
"""
import json
import random
import sys

import cv2
import numpy as np


def face_region(d, f):
    im = cv2.imread(f"{d}/crops/f_{f['id']}.jpg", cv2.IMREAD_GRAYSCALE)
    x, y, r, b = f['left'], f['top'], f['right'], f['bottom']
    bw, bh = r - x, b - y
    side = int(max(bw, bh) * 1.8)
    cx, cy = int(x + bw / 2), int(y + bh / 2 + bh * 0.05)
    x0, y0 = max(0, cx - side // 2), max(0, cy - side // 2)
    return im[max(0, y - y0):max(0, y - y0) + bh, max(0, x - x0):max(0, x - x0) + bw]


def lapvar(g):
    g = g.astype(np.float64)
    return (g[1:-1, :-2] + g[1:-1, 2:] + g[:-2, 1:-1] + g[2:, 1:-1] - 4 * g[1:-1, 1:-1]).var()


def old_metric(g):
    """Wersja aplikacji do listopada 2026: pole + zmniejszenie do maks. 128 px, bez rozmycia."""
    if g.shape[0] > 128:
        g = cv2.resize(g, (max(1, int(g.shape[1] * 128 / g.shape[0])), 128), interpolation=cv2.INTER_AREA)
    return lapvar(g[0::2]) if g.shape[0] >= 6 else lapvar(g)


def new_metric(g, H=64, sigma=0.7):
    """ImageMetrics.normalizedSharpness: pole -> stała wysokość H -> rozmycie σ -> Laplasjan."""
    f = g[0::2] if g.shape[0] >= 12 else g
    tw = max(4, round(g.shape[1] * H / g.shape[0]))
    f = cv2.resize(f.astype(np.float32), (tw, H), interpolation=cv2.INTER_AREA if f.shape[0] > H else cv2.INTER_CUBIC)
    return lapvar(cv2.GaussianBlur(f, (0, 0), sigma))


def main(hd_dir, vhs_dir):
    hd = json.load(open(f'{hd_dir}/faces.json'))['faces']
    vh = json.load(open(f'{vhs_dir}/faces.json'))['faces']
    labels = json.load(open(f'{hd_dir}/labels.json'))
    byf = {}
    for f in hd:
        byf.setdefault(f['frameIndex'], []).append(f)
    # skala VHS/HD z położenia twarzy (szerokość klatek nieznana w faces.json): 720/1280 dla extract_faces --vhs
    sc = 720 / 1280
    pairs = []
    for f in vh:
        cx, cy = (f['left'] + f['right']) / 2 / sc, (f['top'] + f['bottom']) / 2 / sc
        c = [(((h['left'] + h['right']) / 2 - cx) ** 2 + ((h['top'] + h['bottom']) / 2 - cy) ** 2, h) for h in byf.get(f['frameIndex'], [])]
        if not c:
            continue
        d, h = min(c, key=lambda t: t[0])
        if d ** 0.5 < 0.5 * (h['bottom'] - h['top']) and labels[str(h['id'])] != '?':
            gv, gh = face_region(vhs_dir, f), face_region(hd_dir, h)
            if gv.size and gh.size and gv.shape[0] > 8:
                pairs.append((labels[str(h['id'])], f['frameIndex'], gv, gh))
    T = np.array([lapvar(cv2.resize(p[3], (max(1, int(p[3].shape[1] * 128 / p[3].shape[0])), 128), interpolation=cv2.INTER_AREA)) for p in pairs])
    ids = [p[0] for p in pairs]
    fr = [p[1] for p in pairs]
    by = {}
    for i, k in enumerate(ids):
        by.setdefault(k, []).append(i)
    print(f"twarzy: {len(pairs)}")
    for name, fn in [("stara miara", old_metric), ("nowa miara", new_metric)]:
        M = np.array([fn(p[2]) for p in pairs])
        rnd = random.Random(0)
        ok = tot = 0
        for ix in by.values():
            for _ in range(3000):
                a, b = rnd.sample(ix, 2)
                if max(T[a], T[b]) >= 1.3 * min(T[a], T[b]):
                    tot += 1
                    ok += (M[a] > M[b]) == (T[a] > T[b])
        hit = nw = n = 0
        for ix in by.values():
            ix = sorted(ix, key=lambda i: fr[i])
            for j in ix:
                w = [i for i in ix if 0 <= fr[i] - fr[j] <= 2]
                if len(w) < 3 or max(T[i] for i in w) < 1.3 * min(T[i] for i in w):
                    continue
                n += 1
                pick = max(w, key=lambda i: M[i])
                hit += pick == max(w, key=lambda i: T[i])
                nw += pick != min(w, key=lambda i: T[i])
        print(f"{name}: pary {ok / tot * 100:.1f}% | okna (n={n}): najostrzejsza {hit / n * 100:.1f}%, nie najgorsza {nw / n * 100:.1f}%")
    print("losowo: pary 50%, najostrzejsza ~33%, nie najgorsza ~67%")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
