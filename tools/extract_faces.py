#!/usr/bin/env python3
"""
Desktopowy odpowiednik potoku z aplikacji — do strojenia grupowania bez telefonu.

Robi to samo co VideoProcessor.kt, z jedną różnicą: zamiast ML Kit (dostępny tylko na Androidzie)
używa detektora YuNet (OpenCV Zoo, MIT). Embeddingi liczy TEN SAM model SFace z TYM SAMYM
wyrównaniem (szablon 5 punktów, podobieństwo metodą najmniejszych kwadratów), więc wyniki
grupowania przenoszą się na aplikację.

Wynik (katalog --out):
  faces.json      – lista twarzy: id, frameIndex, timestampMs, bbox, embedding (128 liczb)
  crops/f_<id>.jpg – wycinki z marginesem 40% (do obejrzenia grup)

Użycie:
  python3 -m venv tools/.venv && tools/.venv/bin/pip install onnxruntime opencv-python-headless numpy
  tools/.venv/bin/python tools/extract_faces.py --video film.mov --sface app/src/main/assets/models/sface.onnx \
      --yunet face_detection_yunet_2023mar.onnx --out tools/out/film --start 0 --end 600
"""
import argparse
import json
import os

import cv2
import numpy as np
import onnxruntime as ort

TEMPLATE = np.array([
    [38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041],
], dtype=np.float64)


def similarity_transform(src, dst):
    """Ta sama formuła co FaceAlignment.similarityTransform w Kotlinie."""
    sm, dm = src.mean(0), dst.mean(0)
    s, d = src - sm, dst - dm
    den = (s ** 2).sum()
    a = (s[:, 0] * d[:, 0] + s[:, 1] * d[:, 1]).sum() / den
    b = (s[:, 0] * d[:, 1] - s[:, 1] * d[:, 0]).sum() / den
    tx = dm[0] - (a * sm[0] - b * sm[1])
    ty = dm[1] - (b * sm[0] + a * sm[1])
    return np.array([[a, -b, tx], [b, a, ty]], dtype=np.float64)


def landmark_pairs(lm):
    """Oczy i kąciki ust sortowane po x — jak FaceAlignment.pairs."""
    eyes = sorted([lm[0], lm[1]], key=lambda p: p[0])
    mouth = sorted([lm[3], lm[4]], key=lambda p: p[0])
    return np.array([eyes[0], eyes[1], lm[2], mouth[0], mouth[1]], dtype=np.float64)


def vhs_degrade(frame, prev, rng):
    """Przybliżenie zgrania kasety VHS: szerokość 720 (PAL), luminancja ~330 linii, szum, przeplot."""
    h, w = frame.shape[:2]
    out_w, out_h = 720, int(round(h * 720 / w))
    def soften(img):
        img = cv2.resize(img, (out_w, out_h), interpolation=cv2.INTER_AREA)
        ycc = cv2.cvtColor(img, cv2.COLOR_BGR2YCrCb).astype(np.float32)
        y = cv2.resize(cv2.resize(ycc[..., 0], (330, out_h), interpolation=cv2.INTER_AREA), (out_w, out_h))
        c = cv2.resize(cv2.resize(ycc[..., 1:], (90, out_h // 2), interpolation=cv2.INTER_AREA), (out_w, out_h))
        ycc = np.dstack([y, c])
        ycc[..., 0] += rng.normal(0, 6, y.shape)
        return cv2.cvtColor(np.clip(ycc, 0, 255).astype(np.uint8), cv2.COLOR_YCrCb2BGR)
    cur = soften(frame)
    if prev is not None:
        cur[1::2] = soften(prev)[1::2]  # drugie pole z innego momentu = „grzebienie” przy ruchu
    return cur


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--sface", required=True)
    ap.add_argument("--yunet", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--interval-ms", type=int, default=500)
    ap.add_argument("--min-face", type=int, default=40)
    ap.add_argument("--start", type=float, default=0, help="początek (s)")
    ap.add_argument("--end", type=float, default=None, help="koniec (s)")
    ap.add_argument("--max-width", type=int, default=1280)
    ap.add_argument("--score", type=float, default=0.8, help="próg pewności YuNet")
    ap.add_argument("--vhs", action="store_true",
                    help="symulacja VHS: ~330 linii poziomo, szum, rozmyty kolor, przeplot z poprzedniej klatki")
    args = ap.parse_args()

    os.makedirs(os.path.join(args.out, "crops"), exist_ok=True)
    sess = ort.InferenceSession(args.sface, providers=["CPUExecutionProvider"])
    cap = cv2.VideoCapture(args.video)
    fps = cap.get(cv2.CAP_PROP_FPS)
    total_ms = cap.get(cv2.CAP_PROP_FRAME_COUNT) / fps * 1000
    end_ms = min(total_ms, args.end * 1000) if args.end else total_ms
    detector = None

    faces = []
    frame_index = 0
    t = args.start * 1000
    next_t = t
    cap.set(cv2.CAP_PROP_POS_MSEC, t)
    stats = dict(frames=0, detected=0, small=0, edge=0)
    rng = np.random.default_rng(0)
    prev = None
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        raw, prev_raw = frame, prev
        prev = raw
        pos = cap.get(cv2.CAP_PROP_POS_MSEC)
        if pos > end_ms:
            break
        if pos + 1e-3 < next_t:
            continue
        next_t += args.interval_ms
        if args.vhs:
            frame = vhs_degrade(raw, prev_raw, rng)
        if frame.shape[1] > args.max_width:
            s = args.max_width / frame.shape[1]
            frame = cv2.resize(frame, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
        h, w = frame.shape[:2]
        if detector is None:
            detector = cv2.FaceDetectorYN.create(args.yunet, "", (w, h), args.score, 0.3, 5000)
        _, dets = detector.detect(frame)
        stats["frames"] += 1
        for det in (dets if dets is not None else []):
            stats["detected"] += 1
            x, y, bw, bh = det[:4]
            if bh < args.min_face:
                stats["small"] += 1
                continue
            vis_w = max(0, min(x + bw, w) - max(x, 0))
            vis_h = max(0, min(y + bh, h) - max(y, 0))
            if vis_w * vis_h / (bw * bh) < 0.92:
                stats["edge"] += 1
                continue
            lm = det[4:14].reshape(5, 2)
            m = similarity_transform(landmark_pairs(lm), TEMPLATE)
            aligned = cv2.warpAffine(frame, m, (112, 112), flags=cv2.INTER_LINEAR)
            rgb = cv2.cvtColor(aligned, cv2.COLOR_BGR2RGB).astype(np.float32)
            blob = np.transpose(rgb, (2, 0, 1))[None]
            emb = sess.run(None, {"data": blob})[0][0]
            emb = emb / np.linalg.norm(emb)

            fid = len(faces)
            side = int(max(bw, bh) * 1.8)
            cx, cy = int(x + bw / 2), int(y + bh / 2 + bh * 0.05)
            x0, y0 = max(0, cx - side // 2), max(0, cy - side // 2)
            crop = frame[y0:min(h, cy + side // 2), x0:min(w, cx + side // 2)]
            cv2.imwrite(os.path.join(args.out, "crops", f"f_{fid}.jpg"), crop)
            faces.append(dict(
                id=fid, frameIndex=frame_index, timestampMs=int(pos),
                left=int(x), top=int(y), right=int(x + bw), bottom=int(y + bh),
                embedding=[round(float(v), 5) for v in emb],
            ))
        frame_index += 1
        if frame_index % 100 == 0:
            print(f"{pos / 1000:.0f}s  klatki={frame_index} twarze={len(faces)}", flush=True)

    with open(os.path.join(args.out, "faces.json"), "w") as f:
        json.dump(dict(video=os.path.basename(args.video), intervalMs=args.interval_ms, stats=stats, faces=faces), f)
    print("gotowe:", stats, "przyjęte:", len(faces))


if __name__ == "__main__":
    main()
