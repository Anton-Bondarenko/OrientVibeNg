import argparse
from ultralytics import YOLO
import torch
import pathlib

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

model.train(
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
