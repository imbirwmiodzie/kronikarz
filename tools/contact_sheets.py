#!/usr/bin/env python3
"""
Arkusze kontaktowe grup twarzy — do ręcznej oceny grupowania (duplikaty / błędne połączenia).

  tools/.venv/bin/python tools/contact_sheets.py --faces out/x/faces.json --clusters out/x/clusters.json \
      --crops out/x/crops --out out/x/sheets [--per-sheet 12] [--max-faces 30]

Każdy wiersz arkusza = jedna grupa (C<nr>, liczba twarzy), twarze w kolejności czasu, równomiernie
próbkowane. Odrzucone grupy (szum) trafiają na osobny arkusz.
"""
import argparse
import json
import os

import cv2
import numpy as np

CELL = 64


def row_image(ids, crops, title, max_faces):
    if len(ids) > max_faces:
        idx = np.linspace(0, len(ids) - 1, max_faces).round().astype(int)
        ids = [ids[i] for i in idx]
    label = np.full((CELL, 150, 3), 255, np.uint8)
    for i, line in enumerate(title.split("\n")):
        cv2.putText(label, line, (4, 22 + 22 * i), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 0), 1, cv2.LINE_AA)
    cells = [label]
    for fid in ids:
        img = cv2.imread(os.path.join(crops, f"f_{fid}.jpg"))
        img = np.zeros((CELL, CELL, 3), np.uint8) if img is None else cv2.resize(img, (CELL, CELL), interpolation=cv2.INTER_AREA)
        cells.append(img)
    while len(cells) < max_faces + 1:
        cells.append(np.full((CELL, CELL, 3), 255, np.uint8))
    return np.hstack(cells)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--faces", required=True)
    ap.add_argument("--clusters", required=True)
    ap.add_argument("--crops", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--per-sheet", type=int, default=12)
    ap.add_argument("--max-faces", type=int, default=24)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    faces = {f["id"]: f for f in json.load(open(args.faces))["faces"]}
    cl = json.load(open(args.clusters))
    groups = [("C%d" % (i + 1), ids) for i, ids in enumerate(cl["clusters"])]
    groups += [("R%d" % (i + 1), ids) for i, ids in enumerate(cl.get("rejected", []))]
    for s in range(0, len(groups), args.per_sheet):
        rows = []
        for name, ids in groups[s:s + args.per_sheet]:
            ids = sorted(ids, key=lambda i: faces[i]["timestampMs"])
            t0 = faces[ids[0]]["timestampMs"] / 1000
            rows.append(row_image(ids, args.crops, f"{name} n={len(ids)}\nod {int(t0 // 60)}:{int(t0 % 60):02d}", args.max_faces))
            rows.append(np.full((4, rows[-1].shape[1], 3), 180, np.uint8))
        cv2.imwrite(os.path.join(args.out, f"sheet_{s // args.per_sheet + 1:02d}.jpg"), np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 85])
    print(f"grup: {len(cl['clusters'])}, odrzuconych: {len(cl.get('rejected', []))}, arkuszy: {(len(groups) + args.per_sheet - 1) // args.per_sheet}")


if __name__ == "__main__":
    main()
