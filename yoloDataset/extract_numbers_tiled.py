"""
Slice map photos into 640x640 tiles, run YOLO detection on each, extract class 1 (number) ROIs.

Usage:
    python extract_numbers_tiled.py [--input-dir PATH] [--model PATH] [--conf 0.5] [--iou 0.45]
"""

import argparse
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

SLICE_SIZE = 640
OVERLAP_RATIO = 0.2
CONF_THRESH = 0.5
IOU_THRESH = 0.45


def load_model(model_path):
    sess = ort.InferenceSession(model_path, providers=['CPUExecutionProvider'])
    input_name = sess.get_inputs()[0].name
    num_classes = sess.get_outputs()[0].shape[1] - 4
    return sess, input_name, num_classes


def slice_image(img, size, overlap_ratio):
    h, w = img.shape[:2]
    step = int(size * (1 - overlap_ratio))
    num_x = max(1, int(np.ceil((w - size) / step) + 1))
    num_y = max(1, int(np.ceil((h - size) / step) + 1))

    slices = []
    for y in range(num_y):
        for x in range(num_x):
            x1 = min(x * step, w - size) if w > size else 0
            y1 = min(y * step, h - size) if h > size else 0
            tile = img[y1:y1 + size, x1:x1 + size]
            th, tw = tile.shape[:2]
            if th < size or tw < size:
                padded = np.zeros((size, size, 3), dtype=np.uint8)
                padded[:th, :tw] = tile
                tile = padded
            slices.append((tile, x1, y1))
    return slices


def detect_tile(tile, session, input_name, num_classes):
    blob = tile.astype(np.float32) / 255.0
    blob = np.transpose(blob, (2, 0, 1))[np.newaxis, ...]
    outputs = session.run(None, {input_name: blob})[0]

    out = outputs[0]  # [C, N]
    bbox = np.stack([out[0, :], out[1, :], out[2, :], out[3, :]])
    class_probs = out[4:4 + num_classes, :]

    confs = np.max(class_probs, axis=0)
    class_ids = np.argmax(class_probs, axis=0)

    cx = np.clip(bbox[0, :], 0, SLICE_SIZE).copy()
    cy = np.clip(bbox[1, :], 0, SLICE_SIZE).copy()
    bw = np.clip(bbox[2, :], 0, SLICE_SIZE).copy()
    bh = np.clip(bbox[3, :], 0, SLICE_SIZE).copy()

    return list(zip(cx.tolist(), cy.tolist(), bw.tolist(), bh.tolist(), class_ids.tolist(), confs.tolist()))


def nms(detections, iou_threshold):
    if not detections:
        return []
    boxes = np.array([[d[0] - d[2] / 2, d[1] - d[3] / 2, d[0] + d[2] / 2, d[1] + d[3] / 2]
                      for d in detections])
    confs = np.array([d[5] for d in detections])
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
        iou = inter / np.maximum(area_curr + area_others - inter, 1e-6)
        keep = np.where(iou < iou_threshold)[0]
        indices = indices[keep + 1]
    return selected


def extract_numbers(input_dir, output_dir, model_path, conf_thresh=CONF_THRESH, iou_thresh=IOU_THRESH):
    input_dir = Path(input_dir)
    output_dir = Path(output_dir)
    num_output_dir = output_dir / 'number'
    sliced_dir = output_dir / 'sliced'
    num_output_dir.mkdir(parents=True, exist_ok=True)
    sliced_dir.mkdir(parents=True, exist_ok=True)

    session, input_name, num_classes = load_model(model_path)
    print(f'Model: {model_path}')
    print(f'  Output channels: {session.get_outputs()[0].shape[1]} (bbox+{num_classes})')

    img_exts = {'.jpg', '.jpeg', '.png', '.bmp', '.webp', '.tiff'}
    images = sorted(f for f in input_dir.iterdir() if f.suffix.lower() in img_exts)
    print(f'\nFound {len(images)} image(s)\n')

    total_rois = 0
    t_start = time.perf_counter()

    for i, img_path in enumerate(images, 1):
        t_img = time.perf_counter()
        img = cv2.imread(str(img_path))
        if img is None:
            print(f'  [{i}/{len(images)}] {img_path.name} - unreadable')
            continue

        slices = slice_image(img, SLICE_SIZE, OVERLAP_RATIO)
        stem = img_path.stem

        # Save sliced tiles to output/sliced
        for j, (tile, sx, sy) in enumerate(slices):
            cv2.imwrite(str(sliced_dir / f'{stem}_tile{j:04d}_{sx}_{sy}.png'), tile)

        print(f'  [{i}/{len(images)}] {img_path.name} ({img.shape[1]}x{img.shape[0]}) - {len(slices)} tiles')

        # Detect on each tile, map back to global image coords
        all_dets = []
        for j, (tile, sx, sy) in enumerate(slices):
            dets = detect_tile(tile, session, input_name, num_classes)
            for cx, cy, bw, bh, cid, conf in dets:
                global_cx = sx + cx
                global_cy = sy + cy
                all_dets.append((global_cx, global_cy, bw, bh, cid, conf))

        if not all_dets:
            print(f'    -> 0 detections')
            continue

        dets_conf = [(cx, cy, w, h, cid, conf) for (cx, cy, w, h, cid, conf) in all_dets if conf >= conf_thresh]
        print(f'    -> {len(dets_conf)} above conf={conf_thresh}')

        dets_nms = nms(dets_conf, iou_thresh)
        print(f'    -> {len(dets_nms)} after NMS')

        if not dets_nms:
            continue

        num_dets = [(cx, cy, w, h, cid, conf) for (cx, cy, w, h, cid, conf) in dets_nms if cid == 1]
        filter_note = 'classId==1'

        if not num_dets and num_classes <= 1:
            num_dets = list(dets_nms)
            filter_note = 'all (single-class model)'
            print(f'    -> showing all as class 1 substitute')

        if not num_dets:
            continue

        count = 0
        for j, (cx, cy, bw, bh, cid, conf) in enumerate(num_dets):
            x1p = int(max(0, cx - bw / 2))
            y1p = int(max(0, cy - bh / 2))
            x2p = int(min(img.shape[1], cx + bw / 2))
            y2p = int(min(img.shape[0], cy + bh / 2))
            roi_name = f'{stem}_roi{j:03d}_{cid}_{conf:.2f}.png'
            roi_img = img[y1p:y2p, x1p:x2p]
            cv2.imwrite(str(num_output_dir / roi_name), roi_img)
            count += 1

        total_rois += count
        dt = time.perf_counter() - t_img
        print(f'    -> {count} ROI(s) ({filter_note}), {dt:.2f}s\n')

    elapsed = time.perf_counter() - t_start
    print(f'Done: {total_rois} ROIs in {elapsed:.1f}s')
    print(f'Tiles saved to: {sliced_dir}')
    print(f'Number ROIs saved to: {num_output_dir}')


def main():
    parser = argparse.ArgumentParser(description='Extract YOLO-detected number ROIs with tiling.')
    parser.add_argument('--input-dir', default=None)
    parser.add_argument('--model', default=None)
    parser.add_argument('--conf', type=float, default=CONF_THRESH)
    parser.add_argument('--iou', type=float, default=IOU_THRESH)
    args = parser.parse_args()

    input_dir = Path(args.input_dir) if args.input_dir else Path(r'E:\git\orientvibeNg\yoloDataset\photoMap')
    model_path = args.model if args.model else r'E:\git\orientvibeNg\app\src\main\assets\orientmapv8n.onnx'

    if not input_dir.is_dir():
        print(f'Error: {input_dir}', file=sys.stderr)
        sys.exit(1)
    if not Path(model_path).is_file():
        print(f'Error: {model_path}', file=sys.stderr)
        sys.exit(1)

    # output_root = input_dir  — sliced и number сохраняются прямо под photoMap/
    extract_numbers(str(input_dir), str(input_dir), str(model_path), args.conf, args.iou)


if __name__ == '__main__':
    main()
