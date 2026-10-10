"""Color extraction and smoothing. Images are HxWx3 uint8 RGB numpy arrays."""
import colorsys

import numpy as np


def average_color(img: np.ndarray) -> tuple[float, float, float]:
    r, g, b = img.reshape(-1, 3).mean(axis=0)
    return float(r), float(g), float(b)


def dominant_color(img: np.ndarray) -> tuple[float, float, float]:
    """Most populated colour bucket, weighted toward saturated pixels; ignores near-black."""
    px = img.reshape(-1, 3).astype(np.int32)
    px = px[px.max(axis=1) > 24]
    if len(px) == 0:
        return 0.0, 0.0, 0.0
    sat = (px.max(axis=1) - px.min(axis=1)) / np.maximum(px.max(axis=1), 1)
    weight = 0.05 + sat ** 2
    q = px // 32
    key = q[:, 0] * 64 + q[:, 1] * 8 + q[:, 2]
    scores = np.bincount(key, weights=weight, minlength=512)
    best = int(scores.argmax())
    members = px[key == best]
    r, g, b = members.mean(axis=0)
    return float(r), float(g), float(b)


def _top_mean(px: np.ndarray, score: np.ndarray, frac: float = 0.05):
    k = max(1, int(len(px) * frac))
    idx = np.argpartition(score, -k)[-k:]
    r, g, b = px[idx].mean(axis=0)
    return float(r), float(g), float(b)


def brightest_color(img: np.ndarray) -> tuple[float, float, float]:
    """Mean of the brightest 5% of pixels (by perceived luminance); robust to single hot pixels."""
    px = img.reshape(-1, 3).astype(np.float32)
    lum = px @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    return _top_mean(px, lum)


def most_saturated_color(img: np.ndarray) -> tuple[float, float, float]:
    """Mean of the 5% most saturated pixels, ignoring dark ones whose saturation is just noise."""
    px = img.reshape(-1, 3).astype(np.float32)
    mx, mn = px.max(axis=1), px.min(axis=1)
    px, mx, mn = px[mx > 40], mx[mx > 40], mn[mx > 40]
    if len(px) == 0:
        return 0.0, 0.0, 0.0
    sat = (mx - mn) / mx
    return _top_mean(px, sat * (0.5 + mx / 510))  # mild preference for brighter pixels


EXTRACTORS = {
    "average": average_color,
    "dominant": dominant_color,
    "brightest": brightest_color,
    "saturated": most_saturated_color,
}


def crop_black_bars(img: np.ndarray, threshold: float = 14.0, min_keep: float = 0.2) -> np.ndarray:
    """Trim letterbox/pillarbox bars (edge rows/columns whose mean brightness is ~black).

    Falls back to the full frame if almost everything is dark, so a genuinely black scene isn't zoomed into noise.
    """
    rows = np.where(img.mean(axis=(1, 2)) > threshold)[0]
    cols = np.where(img.mean(axis=(0, 2)) > threshold)[0]
    if len(rows) == 0 or len(cols) == 0:
        return img
    out = img[rows[0]:rows[-1] + 1, cols[0]:cols[-1] + 1]
    if out.shape[0] < img.shape[0] * min_keep or out.shape[1] < img.shape[1] * min_keep:
        return img
    return out


ZONES = ("full", "left", "right", "top", "bottom", "center")


def zone_crop(img: np.ndarray, zone: str) -> np.ndarray:
    h, w = img.shape[:2]
    if zone == "left":
        return img[:, : w // 2]
    if zone == "right":
        return img[:, w - w // 2:]
    if zone == "top":
        return img[: h // 2]
    if zone == "bottom":
        return img[h - h // 2:]
    if zone == "center":
        return img[h // 4: h - h // 4, w // 4: w - w // 4]
    return img


def boost_saturation(rgb, amount: float):
    """amount 1.0 = unchanged."""
    h, s, v = colorsys.rgb_to_hsv(*(c / 255 for c in rgb))
    s = min(1.0, s * amount)
    return tuple(c * 255 for c in colorsys.hsv_to_rgb(h, s, v))


def smooth(prev, new, factor: float):
    """factor 0 = no smoothing, closer to 1 = slower."""
    if prev is None:
        return new
    return tuple(p * factor + n * (1 - factor) for p, n in zip(prev, new))


def brightness_of(rgb) -> float:
    """0..1 peak channel."""
    return max(rgb) / 255


def distance(a, b) -> float:
    return max(abs(x - y) for x, y in zip(a, b))
