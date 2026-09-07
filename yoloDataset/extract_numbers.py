"""
Extract number ROIs from oriented map photos using YOLOv8 ONNX detector.

Usage:
    python extract_numbers.py [--input-dir PATH] [--model PATH] [--conf 0.5] [--iou 0.45]

Defaults:
    --input-dir   E:\git\orientvibeNg\yoloDataset\photoMap
    --model       E:\git\orientvibeNg\app\src\main\assets\orientmapv8n.onnx
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort


# ── helpers ────────────────────────────────────────────────────────────────

def load_model(model_path: str):
    sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name
    input_shape = sess.get_inputs()[0].shape  # [1,3,H,W]
    num_classes = (sess.get_outputs()[0].shape[1]) - 4  # bbox(4) + classes
    return sess, input_name, input_shape, num_classes


def letterbox(bitmap, target_size):
    """Letterbox image to target_size x target_size. Return (letterboxed, scale, off_x, off_y)."""
    h, w = bitmap.shape[:2]
    scale = min(target_size / w, target_size / h)
    new_w = int(w * scale)
    new_h = int(h * scale)
    resized = cv2.resize(bitmap, (new_w, new_h))

    letterbox_img = np.zeros((target_size, target_size, 3), dtype=np.uint8)
    off_x = (target_size - new_w) // 2
    off_y = (target_size - new_h) // 2
    letterbox_img[off_y:off_y + new_h, off_x:off_x + new_w] = resized

    return letterbox_img, scale, off_x, off_y


def decode_outputs(output, num_classes, img_h, img_w, target_size):
    """Decode YOLOv8 raw output to bounding boxes in original image coords."""
    preds = output[0]  # (1, N, C) -> (N, C)

    if num_classes == 0:
        # Single-class export: [N, 5] — last channel = confidence
        confs = preds[:, 4]
        boxes_xywh = preds[:, :4]
        class_ids = np.zeros(len(preds), dtype=int)
    else:
        cls_probs = preds[:, 4:]
        confs = cls_probs.max(axis=1)
        class_ids = cls_probs.argmax(axis=1)
        boxes_xywh = preds[:, :4]

    scale = target_size / max(img_w, img_h)
    new_w = int(img_w * (target_size / max(img_w, img_h)))
    new_h = int(img_h * (target_size / max(img_w, img_h)))
    off_x = (target_size - new_w) // 2
    off_y = (target_size - new_h) // 2

    orig_cx = (boxes_xywh[:, 0] - off_x) / scale
    orig_cy = (boxes_xywh[:, 1] - off_y) / scale
    orig_w = boxes_xywh[:, 2] / scale
    orig_h = boxes_xywh[:, 3] / scale

    x1 = np.clip(orig_cx - orig_w / 2, 0, img_w).astype(int)
    y1 = np.clip(orig_cy - orig_h / 2, 0, img_h).astype(int)
    x2 = np.clip(orig_cx + orig_w / 2, 0, img_w).astype(int)
    y2 = np.clip(orig_cy + orig_h / 2, 0, img_h).astype(int)

    return list(zip(x1.tolist(), y1.tolist(), x2.tolist(), y2.tolist(), confs.tolist(), class_ids.tolist()))


def nms(detections, iou_threshold=0.45):
    """Simple Python NMS."""
    if not detections:
        return []

    boxes = np.array([[d[0], d[1], d[2], d[3]] for d in detections])
    confs = np.array([d[4] for d in detections])
    indices = confs.argsort()[::-1]

    selected = []
    while len(indices) > 0:
        current = indices[0]
        selected.append(detections[current])

        if len(indices) == 1:
            break

        xx1 = np.maximum(boxes[current, 0], boxes[indices[1:], 0])
        yy1 = np.maximum(boxes[current, 1], boxes[indices[1:], 1])
        xx2 = np.minimum(boxes[current, 2], boxes[indices[1:], 2])
        yy2 = np.minimum(boxes[current, 3], boxes[indices[1:], 3])

        w = np.maximum(0, xx2 - xx1)
        h = np.maximum(0, yy2 - yy1)
        inter = w * h

        area_curr = (boxes[current, 2] - boxes[current, 0]) * (boxes[current, 3] - boxes[current, 1])
        area_others = (boxes[indices[1:], 2] - boxes[indices[1:], 0]) * (boxes[indices[1:], 3] - boxes[indices[1:], 1])

        iou = inter / (area_curr + area_others - inter)

        keep = np.where(iou < iou_threshold)[0]
        indices = indices[keep + 1]

    return selected


def detect(img, session, input_name, num_classes, target_size):
    h, w = img.shape[:2]
    letterbox_img, scale, off_x, off_y = letterbox(img, target_size)
    blob = letterbox_img.astype(np.float32) / 255.0
    blob = np.transpose(blob, (2, 0, 1))[np.newaxis, ...]
    output = session.run(None, {input_name: blob})[0]
    return decode_outputs(output, num_classes, h, w, target_size)


# ── main pipeline ─────────────────────────────────────────────────────────

def extract_numbers(input_dir, output_dir, model_path, conf_threshold=0.5, iou_threshold=0.45):
    input_dir = Path(input_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    session, input_name, input_shape, num_classes = load_model(model_path)
    target_size = input_shape[2] if input_shape[2] is not None else 640
    print(f"Model: {model_path}")
    print(f"  Input shape: {input_shape}")
    print(f"  Num classes (model): {num_classes + 1} (bbox+{num_classes})")
    print(f"  Target input size: {target_size}x{target_size}")

    img_exts = {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".tiff"}
    images = sorted(f for f in input_dir.iterdir() if f.suffix.lower() in img_exts)
    print(f"\nFound {len(images)} image(s) in {input_dir}\n")

    total_rois = 0
    start = time.perf_counter()

    for i, img_path in enumerate(images, 1):
        t0 = time.perf_counter()
        img = cv2.imread(str(img_path))
        if img is None:
            print(f"  [skip] {img_path.name} — unreadable")
            continue

        detections = detect(img, session, input_name, num_classes, target_size)
        detections = [(x1,y1,x2,y2,c,cid) for (x1,y1,x2,y2,c,cid) in detections if c >= conf_threshold]
        detections = nms(detections, iou_threshold)

        # Filter: user wants class 1 (numbers). If single-class model, show all with a note.
        filtered = [(x1,y1,x2,y2,c,cid) for (x1,y1,x2,y2,c,cid) in detections if cid == 1]
        filter_note = "classId==1"

        if not filtered and num_classes == 0:
            filtered = list(detections)
            filter_note = "classId==0 (single-class model — showing all)"

        if not filtered:
            print(f"  [{i}/{len(images)}] {img_path.name} — no ROIs found")
            continue

        stem = img_path.stem
        count = 0
        for j, (x1,y1,x2,y2,conf,cid) in enumerate(filtered):
            roi_name = f"{stem}_roi{j:03d}_{cid}_{conf:.2f}.png"
            roi_img = img[y1:y2, x1:x2]
            cv2.imwrite(str(output_dir / roi_name), roi_img)
            count += 1

        total_rois += count
        dt = time.perf_counter() - t0
        print(f"  [{i}/{len(images)}] {img_path.name} — {count} ROI(s) ({filter_note}), {dt:.2f}s")

    elapsed = time.perf_counter() - start
    print(f"\nDone: {total_rois} total ROIs saved to {output_dir} in {elapsed:.1f}s")
    return total_rois


def main():
    parser = argparse.ArgumentParser(description="Extract YOLO-detected number ROIs from map photos.")
    parser.add_argument("--input-dir", default=None, help="Directory with input images")
    parser.add_argument("--model", default=None, help="Path to orientmapv8n.onnx")
    parser.add_argument("--conf", type=float, default=0.5, help="Confidence threshold (default: 0.5)")
    parser.add_argument("--iou", type=float, default=0.45, help="NMS IoU threshold (default: 0.45)")
    args = parser.parse_args()

    input_dir = Path(args.input_dir) if args.input_dir else Path(r"E:\git\orientvibeNg\yoloDataset\photoMap")
    model_path = args.model if args.model else r"E:\git\orientvibeNg\app\src\main\assets\orientmapv8n.onnx"

    if not input_dir.is_dir():
        print(f"Error: input dir not found: {input_dir}", file=sys.stderr)
        sys.exit(1)
    if not Path(model_path).is_file():
        print(f"Error: model not found: {model_path}", file=sys.stderr)
        sys.exit(1)

    output_dir = input_dir / "number"
    extract_numbers(input_dir, output_dir, model_path, args.conf, args.iou)


if __name__ == "__main__":
    main()