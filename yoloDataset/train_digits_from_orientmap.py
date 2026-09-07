"""Fine-tune YOLOv8 digits model using pretrained orientmapv8n-9 backbone."""

import shutil
from pathlib import Path

PRETRAINED = Path(r"E:\git\orientvibeNg\runs\detect\orientmapv8n-9\weights\best.pt")
DATA_YAML = Path(r"E:\git\orientvibeNg\yoloDataset\datasetOrientNum\data.yaml")
MODEL_NAME = "digitsv8n_from_orientmap"
ASSETS_DIR = Path(r"E:\git\orientvibeNg\app\src\main\assets")


def fix_data_yaml():
    """Fix paths in data.yaml so they work from the dataset directory."""
    txt = DATA_YAML.read_text()
    fixed = txt.replace("datasetOrientNum/train/images", "train/images")
    fixed = fixed.replace("datasetOrientNum/valid/images", "valid/images")
    if fixed != txt:
        DATA_YAML.write_text(fixed)
        print(f"Fixed data.yaml paths -> {DATA_YAML}")


def main():
    import torch  # noqa: E402

    from ultralytics import YOLO

    fix_data_yaml()

    # Load pretrained model (orientmapv8n-9) for transfer learning
    # This gives us a good backbone to fine-tune on digit classification
    model = YOLO(str(PRETRAINED))

    results = model.train(
        data=str(DATA_YAML),
        epochs=300,
        imgsz=320,
        batch=16,
        pretrained=str(PRETRAINED),  # use orientmapv8n-9 weights as backbone init
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
        dynamic=False,
        imgsz=320,
        simplify=True,
        opset=11,
    )

    print(f"\nExported: {onnx_path}")

    # Copy to assets — replaces digitsv8n.onnx
    dest = ASSETS_DIR / f"{MODEL_NAME}.onnx"
    shutil.copy2(str(onnx_path), str(dest))
    print(f"Copied to assets: {dest} ({dest.stat().st_size / 1024 / 1024:.1f} MB)")


if __name__ == "__main__":
    main()
