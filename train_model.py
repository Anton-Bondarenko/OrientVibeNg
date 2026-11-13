import argparse
from ultralytics import YOLO
import torch
import pathlib
import shutil

parser = argparse.ArgumentParser(description="Train YOLOv8 model")
parser.add_argument("--data", type=str, default="yoloDataset/datasetOrientControls/dataset.yaml")
parser.add_argument("--epochs", type=int, default=400)
parser.add_argument("--imgsz", type=int, default=640)
parser.add_argument("--batch", type=int, default=16)
parser.add_argument("--device", type=str, default=None)
parser.add_argument("--name", type=str, default="orientmapv8n")
args = parser.parse_args()

device = args.device or ('0' if torch.cuda.is_available() else 'cpu')
print(f"Using device: {device}")

model = YOLO("yolov8n.pt")

trainer = model.train(
    data=args.data,
    epochs=args.epochs,
    imgsz=args.imgsz,
    batch=args.batch,
    device=device,
    workers=0,
    name=args.name,
    patience=100,
    close_mosaic=10,
    amp=True
)

# ── Export best model to ONNX ──
print("\nExporting best model to ONNX...")
best_pt = pathlib.Path(trainer.save_dir) / "weights" / "best.pt"

model.export(
    format="onnx",
    dynamic=False,
    imgsz=args.imgsz,
    simplify=True,
    opset=11,
)

onnx_path = best_pt.with_suffix(".onnx")
print(f"Exported: {onnx_path} ({onnx_path.stat().st_size / 1024 / 1024:.1f} MB)")

# Copy to assets
assets_dir = pathlib.Path(__file__).parent / "app" / "src" / "main" / "assets"
dest = assets_dir / f"{args.name}.onnx"
shutil.copy2(str(onnx_path), str(dest))
print(f"Copied to assets: {dest} ({dest.stat().st_size / 1024 / 1024:.1f} MB)")
