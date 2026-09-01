import os
import sys
from pathlib import Path
import yaml
import shutil

BASE_DIR = Path("numberDataset/datasetOrient")
OUT_DIR  = Path("datasetOrientNum")


def load_yolo_labels(label_path):
    boxes = []
    if not label_path.exists():
        return boxes
    with open(label_path, "r") as f:
        for line in f:
            parts = line.strip().split()
            if len(parts) == 5:
                cls = int(parts[0])
                cx, cy, w, h = map(float, parts[1:])
                boxes.append((cls, cx, cy, w, h))
    return boxes


def convert_to_pixel(cls_id, cx_rel, cy_rel, w_rel, h_rel, img_w, img_h):
    if img_w <= 0 or img_h <= 0:
        return None
    cx = cx_rel * img_w
    cy = cy_rel * img_h
    bw = w_rel * img_w
    bh = h_rel * img_h
    x1 = max(0, int(cx - bw / 2))
    y1 = max(0, int(cy - bh / 2))
    x2 = min(img_w, int(cx + bw / 2))
    y2 = min(img_h, int(cy + bh / 2))
    return (cls_id, x1, y1, x2, y2)


def crop_roi(image_path, box_px, padding_ratio=0.3):
    import cv2 as cv
    img = cv.imread(str(image_path), -1)
    if img is None:
        return None

    cls_id, x1, y1, x2, y2 = box_px
    h, w = img.shape[:2]

    bw = x2 - x1
    bh = y2 - y1
    pad_x = int(max(5, min(30, bw * padding_ratio)))
    pad_y = int(max(5, min(30, bh * padding_ratio)))

    crop_x1 = max(0, x1 - pad_x)
    crop_y1 = max(0, y1 - pad_y)
    crop_x2 = min(w, x2 + pad_x)
    crop_y2 = min(h, y2 + pad_y)

    roi = img[crop_y1:crop_y2, crop_x1:crop_x2]
    return (roi, cls_id)


def process_split(name, parent_path):
    images_dir  = parent_path / "images"
    labels_dir  = parent_path / "labels"
    out_img_dir = OUT_DIR / name / "images"
    os.makedirs(str(out_img_dir), exist_ok=True)

    if not images_dir.exists():
        print(f"[SKIP] No such dir: {images_dir}")
        return 0, False

    from glob import glob as pyglob
    img_files = sorted(pyglob(str(images_dir / "*.jpg")) + pyglob(str(images_dir / "*.jpeg")))
    if not img_files:
        img_files = sorted(pyglob(str(images_dir / "*.*")))
    print(f"  [{name}] {len(img_files)} images found")

    import cv2 as cv
    total_rois = 0
    no_cp_images = set()
    bad_images   = set()

    for fpath in img_files:
        base = Path(fpath).stem
        label_path = labels_dir / f"{base}.txt"
        boxes_all = load_yolo_labels(label_path)
        if not boxes_all or not label_path.exists():
            no_cp_images.add(base)
            continue

        img = cv.imread(str(fpath), -1)
        if img is None:
            bad_images.add(base)
            continue

        img_h, img_w = img.shape[:2]

        # class 0 — control points (круги КП с номерами)
        cp_boxes = [(c, xcr, ycr, wr, hr) for c, xcr, ycr, wr, hr in boxes_all if c == 0]
        if not cp_boxes:
            no_cp_images.add(base)
            continue

        roi_count_this = 0
        for c, xcr, ycr, wr, hr in cp_boxes:
            box_px = convert_to_pixel(c, xcr, ycr, wr, hr, img_w, img_h)
            if box_px is None:
                continue
            res = crop_roi(fpath, box_px)
            if res is None:
                bad_images.add(base)
                continue
            roi, _ = res
            if roi.size == 0 or roi.shape[0] < 10 or roi.shape[1] < 10:
                continue

            roi_fn = f"{base}_cp{roi_count_this}.png"
            cv.imwrite(str(out_img_dir / roi_fn), roi)
            roi_count_this += 1

        if roi_count_this > 0:
            # write label for the cropped ROIs (class=0, center, full image)
            out_lab_dir   = OUT_DIR / name / "labels"
            os.makedirs(str(out_lab_dir), exist_ok=True)
            new_name = f"{name}.{base}"
            with open(str(out_lab_dir / f"{new_name}.txt"), "w") as lf:
                for i in range(roi_count_this):
                    lf.write(f"0 0.500000 0.500000 1.000000 1.000000\n")

        total_rois += roi_count_this

    print(f"  [{name}] ROIs: {total_rois} | no-CP: {len(no_cp_images)} | bad-img: {len(bad_images)}")
    return total_rois, True


def main():
    if OUT_DIR.exists():
        shutil.rmtree(str(OUT_DIR))

    os.makedirs(str(OUT_DIR / "train" / "images"), exist_ok=True)
    os.makedirs(str(OUT_DIR / "valid" / "images"), exist_ok=True)
    # labels dirs will be created on-the-fly

    yaml_data = {
        "train": "../train/images",
        "val":   "../valid/images",
        "nc": 10,
        "names": [str(i) for i in range(10)]
    }
    with open(str(OUT_DIR / "data.yaml"), "w") as f:
        yaml.dump(yaml_data, f, default_flow_style=False)

    print("=== Splitting datasetOrient ===\n")

    total = 0
    for name, path in [("train", BASE_DIR / "train"), ("valid", BASE_DIR / "val")]:
        n, ok = process_split(name, path)
        if ok:
            total += n

    roi_n   = len(list(OUT_DIR.glob("**/*.png")))
    lab_n   = sum(len(list((OUT_DIR / name / "labels").glob("*.txt"))) for name in ["train", "valid"])

    print(f"\n=== DONE ===\n  ROIs:       {total}")
    print(f"  PNG files:  {roi_n}")
    print(f"  Labels:     {lab_n}")
    print(f"  Output:     {OUT_DIR.resolve()}")


if __name__ == "__main__":
    main()
