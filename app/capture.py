"""Multi-monitor screen capture via mss."""
import io
import time

import mss
import numpy as np
from PIL import Image

SAMPLE_SIZE = (96, 54)


class Capturer:
    """mss handles are thread-bound on some platforms, so create one per use thread."""

    def __init__(self):
        self._sct = mss.mss()

    def monitors(self) -> list[dict]:
        # index 0 is the virtual "all monitors" screen; real ones start at 1
        return [
            {"index": i, "width": m["width"], "height": m["height"], "left": m["left"], "top": m["top"]}
            for i, m in enumerate(self._sct.monitors) if i > 0
        ]

    def grab(self, index: int) -> np.ndarray:
        mons = self._sct.monitors
        if not 1 <= index < len(mons):
            raise ValueError(f"Monitor {index} not found")
        shot = self._sct.grab(mons[index])
        img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        return np.asarray(img.resize(SAMPLE_SIZE, Image.BILINEAR))

    def preview_jpeg(self, index: int, width: int = 480) -> bytes:
        mons = self._sct.monitors
        if not 1 <= index < len(mons):
            raise ValueError(f"Monitor {index} not found")
        shot = self._sct.grab(mons[index])
        img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        img.thumbnail((width, width))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=70)
        return buf.getvalue()

    def close(self):
        self._sct.close()


class DemoCapturer:
    """Synthetic 'screens' for trying the UI without hardware (WIZSYNC_DEMO=1) and for README screenshots."""

    SIZES = {1: (2560, 1440), 2: (1920, 1080)}

    def monitors(self) -> list[dict]:
        return [{"index": i, "width": w, "height": h, "left": 0, "top": 0} for i, (w, h) in self.SIZES.items()]

    @staticmethod
    def _scene(index: int, w: int, h: int, t: float) -> np.ndarray:
        y = np.linspace(0, 1, h, dtype=np.float32)[:, None]
        x = np.linspace(0, 1, w, dtype=np.float32)[None, :]
        shift = 0.5 + 0.5 * np.sin(t * 0.4)
        if index == 1:   # sunset: violet sky fading to orange, sun, dark hills
            top = np.array([70, 40, 140])
            mid = np.array([200, 50, 130]) + (np.array([255, 120 + 60 * shift, 50]) - np.array([200, 50, 130])) * x[..., None]
            img = top + (mid - top) * np.clip(y * 1.4, 0, 1)[..., None]
            sun = np.exp(-(((x - 0.62) ** 2) * 60 + ((y - 0.55) ** 2) * 90))[..., None]
            img = img + sun * np.array([255, 200, 120])
            hills = (y > 0.72 + 0.05 * np.sin(x * 9 + t * 0.2))[..., None]
            img = np.where(hills, np.array([18, 14, 30]), img)
        else:            # teal forest, letterboxed like a film
            g = np.array([10, 90 + 50 * shift, 110]) + (np.array([200, 230, 120]) - np.array([10, 90, 110])) * (x ** 2)[..., None]
            img = g * np.ones((h, 1, 1), dtype=np.float32) * (0.4 + 0.6 * (1 - y))[..., None]
            bar = int(h * 0.12)
            img[:bar] = 0
            img[h - bar:] = 0
        return np.clip(img, 0, 255).astype(np.uint8)

    def grab(self, index: int) -> np.ndarray:
        return self._scene(index, *SAMPLE_SIZE, time.time())

    def preview_jpeg(self, index: int, width: int = 480) -> bytes:
        arr = self._scene(index, width, int(width * 9 / 16), time.time())
        buf = io.BytesIO()
        Image.fromarray(arr).save(buf, "JPEG", quality=80)
        return buf.getvalue()

    def close(self):
        pass
