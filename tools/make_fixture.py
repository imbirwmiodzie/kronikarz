#!/usr/bin/env python3
"""
Zamienia faces.json + labels.json na zwarty fixture do testów JVM
(app/src/test/resources/…): embeddingi skwantowane do int8 (x127) i zakodowane base64.
Błąd kwantyzacji podobieństwa kosinusowego < 0.01 — bez znaczenia dla grupowania.

  python3 tools/make_fixture.py faces.json labels.json out.json "opis źródła"
"""
import base64
import json
import sys

faces = json.load(open(sys.argv[1]))
labels = json.load(open(sys.argv[2]))
out = []
for f in faces["faces"]:
    q = bytes((max(-127, min(127, round(v * 127))) & 0xFF) for v in f["embedding"])
    out.append(dict(id=f["id"], f=f["frameIndex"], t=f["timestampMs"],
                    b=[f["left"], f["top"], f["right"], f["bottom"]],
                    l=labels.get(str(f["id"]), "?"), e=base64.b64encode(q).decode()))
json.dump(dict(source=sys.argv[4], faces=out), open(sys.argv[3], "w"), separators=(",", ":"), ensure_ascii=False)
print(len(out), "twarzy")
