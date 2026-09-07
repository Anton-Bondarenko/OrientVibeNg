"""Train YOLOv8 digits model on datasetOrientNum dataset."""

import shutil
from pathlib import Path

DATASET_DIR = Path(r"E:\git\orientvibeNg\yoloDataset\datasetOrientNum")
DATA_YAML = DATASET_DIR / "data.yaml"
MODEL_NAME = "digitsv8n"
ASSETS_DIR = Path(r"E:\git\orientvibeNg\app\src\main\assets")


def fix_data_yaml():
    """Fix paths in data.yaml so they work from the dataset directory."""
    txt = DATA_YAML.read_text()
    # data.yaml has path: . and train: datasetOrientNum/train/images
    # but it's already inside datasetOrientNum/, so paths should be train/images
    fixed = txt.replace("datasetOrientNum/train/images", "train/images")
    fixed = fixed.replace("datasetOrientNum/valid/images", "valid/images")
    if fixed != txt:
        DATA_YAML.write_text(fixed)
        print(f"Fixed data.yaml paths -> {DATA_YAML}")


def main():
    import torch  # noqa: E402

    from ultralytics import YOLO

    fix_data_yaml()

    # Start from pretrained YOLOv8n (small, fast for single-digit recognition)
    model = YOLO("yolov8n.pt")
    results = model.train(
        data=str(DATA_YAML),
        epochs=300,
        imgsz=320,
        batch=16,
        name=f"{MODEL_NAME}_train",
        device="0" if torch.cuda.is_available() else "cpu",
        cache=False,
        patience=50,  # early stopping
    )

    best_pt = Path(results.model.save_dir) / "weights" / "best.pt"
    print(f"\nBest model: {best_pt}")

    # Export to ONNX
    onnx_path = best_pt.with_suffix(".onnx")
    model.export(
        format="onnx",
        dynamic=False,  # fixed shapes for mobile
        imgsz=320,
        simplify=True,  # apply ONNX Simplifier if available
        opset=11,
    )

    print(f"\nExported: {onnx_path}")

    # Copy to assets
    dest = ASSETS_DIR / f"{MODEL_NAME}.onnx"
    shutil.copy2(str(onnx_path), str(dest))
    print(f"Copied to assets: {dest} ({dest.stat().st_size / 1024 / 1024:.1f} MB)")


if __name__ == "__main__":
    main()
