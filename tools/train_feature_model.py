#!/usr/bin/env python3
"""Train a lightweight feature-window model from ski IMU training packages.

Inputs can be Android-exported *_training.zip files, extracted package
directories containing features.jsonl + labels.json, or standalone labels.json
files passed next to one package.

The model is intentionally dependency-free: each label window becomes one
training row, numeric phone-rule features are aggregated over the window, and a
nearest-centroid classifier is trained for action, quality, errorType, and
stageCode.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple


TARGETS = ("action", "quality", "errorType", "stageCode")
IGNORE_FEATURE_KEYS = {
    "kind",
    "hostTs",
    "sessionElapsedMs",
    "algorithmVersion",
}


@dataclass
class LabelBundle:
    path: str
    payload: Dict[str, Any]
    labels: List[Dict[str, Any]]


@dataclass
class PackageData:
    path: str
    name: str
    metadata: Dict[str, Any]
    features: List[Dict[str, Any]]
    labels: List[Dict[str, Any]]
    label_source: str


def read_json(text: str, source: str) -> Dict[str, Any]:
    try:
        value = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"{source}: invalid JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{source}: expected a JSON object")
    return value


def read_jsonl(text: str, source: str) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for line_no, line in enumerate(text.splitlines(), 1):
        line = line.strip().lstrip("\ufeff")
        if not line:
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{source}:{line_no}: invalid JSONL row: {exc}") from exc
        if isinstance(value, dict):
            rows.append(value)
    return rows


def labels_from_payload(payload: Dict[str, Any], source: str) -> List[Dict[str, Any]]:
    raw = payload.get("labels", [])
    if raw is None:
        raw = []
    if not isinstance(raw, list):
        raise ValueError(f"{source}: labels must be an array")
    labels: List[Dict[str, Any]] = []
    for item in raw:
        if isinstance(item, dict):
            labels.append(item)
    return labels


def read_package_zip(path: Path) -> PackageData:
    with zipfile.ZipFile(path) as zf:
        names = set(zf.namelist())

        def read_text(name: str) -> str:
            if name not in names:
                raise ValueError(f"{path}: missing {name}")
            return zf.read(name).decode("utf-8-sig")

        metadata = read_json(read_text("metadata.json"), f"{path}:metadata.json")
        features = read_jsonl(read_text("features.jsonl"), f"{path}:features.jsonl")
        if "labels.json" in names:
            labels_payload = read_json(read_text("labels.json"), f"{path}:labels.json")
            labels = labels_from_payload(labels_payload, f"{path}:labels.json")
        else:
            labels = []

    return PackageData(
        path=str(path),
        name=path.name,
        metadata=metadata,
        features=features,
        labels=labels,
        label_source="labels.json in package",
    )


def read_package_dir(path: Path) -> PackageData:
    features_path = path / "features.jsonl"
    metadata_path = path / "metadata.json"
    labels_path = path / "labels.json"
    if not features_path.exists():
        raise ValueError(f"{path}: missing features.jsonl")

    metadata = read_json(metadata_path.read_text(encoding="utf-8-sig"), str(metadata_path)) if metadata_path.exists() else {}
    features = read_jsonl(features_path.read_text(encoding="utf-8-sig"), str(features_path))
    if labels_path.exists():
        labels_payload = read_json(labels_path.read_text(encoding="utf-8-sig"), str(labels_path))
        labels = labels_from_payload(labels_payload, str(labels_path))
    else:
        labels = []

    return PackageData(
        path=str(path),
        name=path.name,
        metadata=metadata,
        features=features,
        labels=labels,
        label_source=str(labels_path) if labels_path.exists() else "",
    )


def read_label_file(path: Path) -> LabelBundle:
    payload = read_json(path.read_text(encoding="utf-8-sig"), str(path))
    return LabelBundle(path=str(path), payload=payload, labels=labels_from_payload(payload, str(path)))


def discover_inputs(paths: Sequence[Path]) -> Tuple[List[PackageData], List[LabelBundle]]:
    packages: List[PackageData] = []
    labels: List[LabelBundle] = []

    def consider(path: Path) -> None:
        if path.is_file() and path.suffix.lower() == ".zip":
            packages.append(read_package_zip(path))
        elif path.is_file() and path.suffix.lower() == ".json":
            labels.append(read_label_file(path))
        elif path.is_dir() and (path / "features.jsonl").exists():
            packages.append(read_package_dir(path))

    for path in paths:
        if path.is_file() or (path.is_dir() and (path / "features.jsonl").exists()):
            consider(path)
            continue
        if not path.is_dir():
            raise FileNotFoundError(path)
        for child in sorted(path.rglob("*")):
            if child.is_file() and child.suffix.lower() in {".zip", ".json"}:
                consider(child)
            elif child.is_dir() and (child / "features.jsonl").exists():
                consider(child)

    return packages, labels


def normalized_names(*values: Optional[str]) -> set[str]:
    names: set[str] = set()
    for value in values:
        if not value:
            continue
        raw = Path(str(value)).name.lower()
        names.add(raw)
        names.add(Path(raw).stem)
        if raw.endswith("_labels.json"):
            names.add(raw[: -len("_labels.json")])
        if raw.endswith("_training.zip"):
            names.add(raw[: -len("_training.zip")])
    return {name for name in names if name}


def label_match_score(package: PackageData, bundle: LabelBundle) -> int:
    metadata = package.metadata
    package_names = normalized_names(
        package.name,
        package.path,
        metadata.get("sourceLogFile"),
        metadata.get("sessionName"),
    )
    label_names = normalized_names(
        bundle.path,
        bundle.payload.get("sourceFileName"),
        bundle.payload.get("sessionName"),
    )
    if package_names & label_names:
        return 100
    source_file = str(bundle.payload.get("sourceFileName", "")).lower()
    if source_file and any(name in source_file for name in package_names):
        return 80
    label_path = Path(bundle.path).name.lower()
    if any(name and name in label_path for name in package_names):
        return 60
    return 0


def attach_external_labels(packages: List[PackageData], bundles: List[LabelBundle]) -> None:
    if not bundles:
        return
    if len(packages) == 1 and len(bundles) == 1 and bundles[0].labels:
        packages[0].labels = bundles[0].labels
        packages[0].label_source = bundles[0].path
        return

    for package in packages:
        best_bundle: Optional[LabelBundle] = None
        best_score = 0
        for bundle in bundles:
            if not bundle.labels:
                continue
            score = label_match_score(package, bundle)
            if score > best_score:
                best_score = score
                best_bundle = bundle
        if best_bundle and best_score > 0 and best_bundle.labels:
            package.labels = best_bundle.labels
            package.label_source = best_bundle.path


def number(value: Any) -> Optional[float]:
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    if isinstance(value, (int, float)) and math.isfinite(float(value)):
        return float(value)
    return None


def flatten_numeric(value: Any, prefix: str = "") -> Dict[str, float]:
    flat: Dict[str, float] = {}
    if isinstance(value, dict):
        for key, item in value.items():
            if key in IGNORE_FEATURE_KEYS:
                continue
            next_prefix = f"{prefix}.{key}" if prefix else str(key)
            flat.update(flatten_numeric(item, next_prefix))
    else:
        numeric = number(value)
        if numeric is not None and prefix:
            flat[prefix] = numeric
    return flat


def feature_time_ms(row: Dict[str, Any], started_at: float) -> Optional[float]:
    for key in ("sessionElapsedMs", "elapsedMs"):
        value = number(row.get(key))
        if value is not None:
            return value
    host_ts = number(row.get("hostTs"))
    if host_ts is not None and started_at:
        return host_ts - started_at
    seconds = number(row.get("t"))
    if seconds is not None:
        return seconds * 1000.0
    return None


def label_window(label: Dict[str, Any]) -> Optional[Tuple[float, float]]:
    start = number(label.get("startMs", label.get("start_ms", label.get("start"))))
    end = number(label.get("endMs", label.get("end_ms", label.get("end"))))
    if start is None or end is None:
        return None
    return (min(start, end), max(start, end))


def aggregate_window(rows: List[Dict[str, Any]], start_ms: float, end_ms: float) -> Dict[str, float]:
    collected: Dict[str, List[float]] = {}
    for row in rows:
        flat = flatten_numeric(row)
        for key, value in flat.items():
            collected.setdefault(key, []).append(value)

    features: Dict[str, float] = {
        "window.duration_ms": end_ms - start_ms,
        "window.frame_count": float(len(rows)),
    }
    for key, values in collected.items():
        if not values:
            continue
        features[f"{key}.mean"] = statistics.fmean(values)
        features[f"{key}.min"] = min(values)
        features[f"{key}.max"] = max(values)
        features[f"{key}.last"] = values[-1]
        features[f"{key}.delta"] = values[-1] - values[0]
        features[f"{key}.std"] = statistics.pstdev(values) if len(values) > 1 else 0.0
    return features


def build_samples(package: PackageData, min_frames: int) -> List[Dict[str, Any]]:
    metadata = package.metadata
    started_at = float(metadata.get("startedAt") or metadata.get("firstHostTs") or 0)
    timed_rows: List[Tuple[float, Dict[str, Any]]] = []
    for row in package.features:
        ts = feature_time_ms(row, started_at)
        if ts is not None and math.isfinite(ts):
            timed_rows.append((ts, row))
    timed_rows.sort(key=lambda item: item[0])

    samples: List[Dict[str, Any]] = []
    for index, label in enumerate(package.labels):
        window = label_window(label)
        if not window:
            continue
        start_ms, end_ms = window
        rows = [row for ts, row in timed_rows if start_ms <= ts <= end_ms]
        if len(rows) < min_frames:
            continue
        features = aggregate_window(rows, start_ms, end_ms)
        error_type = str(label.get("errorType") or label.get("error_type") or "").strip() or "none"
        stage_code = str(
            label.get("stageCode")
            or label.get("stage_code")
            or label.get("phaseCode")
            or label.get("action")
            or "other"
        )
        stage_name = str(label.get("stageName") or label.get("stage_name") or stage_code)
        samples.append(
            {
                "sample_id": f"{Path(package.name).stem}:{index + 1}",
                "package": package.name,
                "package_path": package.path,
                "label_source": package.label_source,
                "sessionName": metadata.get("sessionName", ""),
                "sourceLogFile": metadata.get("sourceLogFile", ""),
                "label_id": label.get("id", f"label_{index + 1:03d}"),
                "startMs": start_ms,
                "endMs": end_ms,
                "action": str(label.get("action") or "other"),
                "quality": str(label.get("quality") or "normal"),
                "errorType": error_type,
                "stageCode": stage_code,
                "stageName": stage_name,
                "comment": str(label.get("comment") or ""),
                "features": features,
            }
        )
    return samples


def feature_matrix(samples: List[Dict[str, Any]]) -> Tuple[List[str], Dict[str, float], Dict[str, float], List[List[float]]]:
    feature_names = sorted({key for sample in samples for key in sample["features"].keys()})
    means: Dict[str, float] = {}
    stds: Dict[str, float] = {}
    for key in feature_names:
        values = [sample["features"][key] for sample in samples if key in sample["features"]]
        mean = statistics.fmean(values) if values else 0.0
        std = statistics.pstdev(values) if len(values) > 1 else 1.0
        if std == 0:
            std = 1.0
        means[key] = mean
        stds[key] = std

    matrix: List[List[float]] = []
    for sample in samples:
        vector = []
        for key in feature_names:
            raw = sample["features"].get(key, means[key])
            vector.append((raw - means[key]) / stds[key])
        matrix.append(vector)
    return feature_names, means, stds, matrix


def train_centroids(samples: List[Dict[str, Any]], targets: Sequence[str]) -> Dict[str, Any]:
    feature_names, means, stds, matrix = feature_matrix(samples)
    classifiers: Dict[str, Any] = {}
    for target in targets:
        grouped: Dict[str, List[List[float]]] = {}
        for sample, vector in zip(samples, matrix):
            grouped.setdefault(str(sample[target]), []).append(vector)
        class_data: Dict[str, Any] = {}
        for label, vectors in sorted(grouped.items()):
            centroid = [
                statistics.fmean(vector[i] for vector in vectors)
                for i in range(len(feature_names))
            ]
            class_data[label] = {
                "count": len(vectors),
                "centroid": centroid,
            }
        classifiers[target] = {
            "type": "nearest_centroid",
            "classes": class_data,
        }

    return {
        "schema": "ski_imu_feature_model_v0",
        "trainedAt": datetime.now(timezone.utc).isoformat(),
        "featureNames": feature_names,
        "normalization": {
            "mean": means,
            "std": stds,
        },
        "classifiers": classifiers,
    }


def vector_for_sample(sample: Dict[str, Any], model: Dict[str, Any]) -> List[float]:
    means = model["normalization"]["mean"]
    stds = model["normalization"]["std"]
    return [
        (sample["features"].get(key, means[key]) - means[key]) / (stds[key] or 1.0)
        for key in model["featureNames"]
    ]


def predict(model: Dict[str, Any], sample: Dict[str, Any], target: str) -> str:
    vector = vector_for_sample(sample, model)
    best_label = ""
    best_distance = float("inf")
    for label, data in model["classifiers"][target]["classes"].items():
        centroid = data["centroid"]
        distance = sum((a - b) ** 2 for a, b in zip(vector, centroid))
        if distance < best_distance:
            best_distance = distance
            best_label = label
    return best_label


def evaluate(samples: List[Dict[str, Any]], targets: Sequence[str]) -> Dict[str, Any]:
    sessions = sorted({str(sample.get("package") or sample.get("sessionName") or "") for sample in samples})
    folds: List[Tuple[List[Dict[str, Any]], List[Dict[str, Any]], str]] = []
    if len(sessions) >= 2:
        for session in sessions:
            train = [sample for sample in samples if str(sample.get("package") or sample.get("sessionName") or "") != session]
            test = [sample for sample in samples if str(sample.get("package") or sample.get("sessionName") or "") == session]
            if train and test:
                folds.append((train, test, session))
    if not folds:
        folds = [(samples, samples, "train")]

    result: Dict[str, Any] = {"mode": "leave_one_session_out" if len(folds) > 1 else "train_set"}
    for target in targets:
        correct = 0
        total = 0
        confusion: Dict[str, Dict[str, int]] = {}
        for train, test, _fold_name in folds:
            model = train_centroids(train, [target])
            for sample in test:
                actual = str(sample[target])
                predicted = predict(model, sample, target)
                total += 1
                correct += int(predicted == actual)
                confusion.setdefault(actual, {})
                confusion[actual][predicted] = confusion[actual].get(predicted, 0) + 1
        result[target] = {
            "accuracy": correct / total if total else 0.0,
            "correct": correct,
            "total": total,
            "confusion": confusion,
        }
    return result


def write_samples_csv(path: Path, samples: List[Dict[str, Any]], feature_names: Sequence[str]) -> None:
    fields = [
        "sample_id",
        "package",
        "label_source",
        "label_id",
        "startMs",
        "endMs",
        "action",
        "quality",
        "errorType",
        "stageCode",
        "stageName",
        "comment",
    ] + list(feature_names)
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for sample in samples:
            row = {field: sample.get(field, "") for field in fields}
            for key in feature_names:
                row[key] = sample["features"].get(key, "")
            writer.writerow(row)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train a lightweight ski IMU feature model.")
    parser.add_argument("inputs", nargs="+", help="Training ZIPs, extracted package dirs, labels.json files, or folders to scan.")
    parser.add_argument("--output", default="models/feature_model", help="Output directory.")
    parser.add_argument("--min-frames", type=int, default=1, help="Minimum feature frames inside a label window.")
    parser.add_argument("--targets", nargs="+", default=list(TARGETS), choices=list(TARGETS), help="Label targets to train.")
    return parser.parse_args(argv)


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    input_paths = [Path(item) for item in args.inputs]
    packages, label_bundles = discover_inputs(input_paths)
    attach_external_labels(packages, label_bundles)

    samples: List[Dict[str, Any]] = []
    skipped_packages: List[Dict[str, Any]] = []
    for package in packages:
        package_samples = build_samples(package, max(1, args.min_frames))
        if package_samples:
            samples.extend(package_samples)
        else:
            skipped_packages.append(
                {
                    "package": package.name,
                    "path": package.path,
                    "labels": len(package.labels),
                    "features": len(package.features),
                    "labelSource": package.label_source,
                }
            )

    if not packages:
        print("No training packages found.", file=sys.stderr)
        return 2
    if not samples:
        print("No training samples found. Import edited labels.json or pass it next to the training ZIP.", file=sys.stderr)
        return 2

    model = train_centroids(samples, args.targets)
    metrics = evaluate(samples, args.targets)
    model["targets"] = list(args.targets)
    model["trainingSummary"] = {
        "packageCount": len(packages),
        "sampleCount": len(samples),
        "skippedPackages": skipped_packages,
    }
    model["metrics"] = metrics

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)
    model_path = output_dir / "feature_model.json"
    metrics_path = output_dir / "metrics.json"
    samples_path = output_dir / "training_samples.csv"

    model_path.write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding="utf-8")
    metrics_path.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    write_samples_csv(samples_path, samples, model["featureNames"])

    print(f"Packages: {len(packages)}")
    print(f"Training samples: {len(samples)}")
    print(f"Features: {len(model['featureNames'])}")
    for target in args.targets:
        target_metrics = metrics[target]
        print(f"{target}: accuracy={target_metrics['accuracy']:.3f} ({target_metrics['correct']}/{target_metrics['total']})")
    print(f"Model: {model_path}")
    print(f"Metrics: {metrics_path}")
    print(f"Samples: {samples_path}")
    if skipped_packages:
        print(f"Skipped packages: {len(skipped_packages)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
