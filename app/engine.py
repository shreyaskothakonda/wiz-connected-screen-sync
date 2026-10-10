"""Sync loop: capture -> (crop bars) -> per-zone colour -> smooth/predict -> bulbs."""
import asyncio
import json
import os
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from pathlib import Path

from . import color

CONFIG_PATH = Path(os.environ.get("WIZSYNC_CONFIG") or Path(__file__).resolve().parent.parent / "config.json")

WARM_WHITE = (255, 214, 170, 80)  # r, g, b, dimming used by the "warm white" stop action

PRESETS = {
    "movie": {"mode": "average", "smoothing": 0.45, "saturation": 1.4, "brightness_mode": "follow",
              "min_brightness": 15, "lead": 80, "rate": 20.0, "ignore_black_bars": True},
    "gaming": {"mode": "dominant", "smoothing": 0.05, "saturation": 1.8, "brightness_mode": "follow",
               "min_brightness": 20, "lead": 120, "rate": 30.0, "ignore_black_bars": False},
    "ambient": {"mode": "average", "smoothing": 0.85, "saturation": 1.2, "brightness_mode": "fixed",
                "brightness": 60, "lead": 0, "rate": 10.0, "ignore_black_bars": True},
    "party": {"mode": "saturated", "smoothing": 0.0, "saturation": 2.5, "brightness_mode": "fixed",
              "brightness": 100, "lead": 120, "rate": 30.0, "ignore_black_bars": False},
}


@dataclass
class Settings:
    persist = True  # class attribute (not a field): demo mode turns saving off

    bulbs: list = field(default_factory=list)  # [{id,type:wiz|tuya,name,ip,enabled,zone,dev_id?,key?,version?}]
    monitor: int = 1
    mode: str = "average"          # average | dominant | brightest | saturated
    smoothing: float = 0.1         # 0..0.95, per 10 fps; scaled so lag is the same at any rate
    saturation: float = 1.3        # 1 = unchanged
    brightness_mode: str = "follow"  # follow | fixed
    brightness: int = 100          # fixed level, 10..100
    min_brightness: int = 10       # floor when following, 10..100
    lead: int = 100                # ms of latency compensation (predicts colour ahead), 0..300
    rate: float = 30.0             # updates per second, 1..30
    ignore_black_bars: bool = True  # trim letterbox bars before extracting colour
    stop_action: str = "leave"     # leave | warm | off  (what the bulbs do when sync stops)

    def update(self, data: dict):
        for k, v in data.items():
            if k in self.__dataclass_fields__ and k != "bulbs":  # bulbs only change through upsert/remove
                setattr(self, k, type(getattr(self, k))(v))
        self.smoothing = max(0.0, min(0.95, self.smoothing))
        self.rate = max(1.0, min(30.0, self.rate))
        self.lead = max(0, min(300, self.lead))
        self.brightness = max(10, min(100, self.brightness))
        self.min_brightness = max(10, min(100, self.min_brightness))
        if self.mode not in color.EXTRACTORS:
            self.mode = "average"
        if self.brightness_mode not in ("follow", "fixed"):
            self.brightness_mode = "follow"
        if self.stop_action not in ("leave", "warm", "off"):
            self.stop_action = "leave"

    def apply_preset(self, name: str):
        if name not in PRESETS:
            raise ValueError(f"Unknown preset: {name}")
        self.update(PRESETS[name])

    # -- bulbs (kept out of update() so the UI can never overwrite stored local keys)
    def enabled_bulbs(self) -> list[dict]:
        return [b for b in self.bulbs if b.get("enabled", True)]

    def public_bulbs(self) -> list[dict]:
        return [{**{k: v for k, v in b.items() if k != "key"}, "has_key": bool(b.get("key"))} for b in self.bulbs]

    def upsert_bulb(self, data: dict) -> dict:
        t = data.get("type", "wiz")
        if t not in ("wiz", "tuya"):
            raise ValueError("Unknown bulb type")
        ip = str(data.get("ip", "")).strip()
        if not ip:
            raise ValueError("Bulb IP is required")
        old = next((b for b in self.bulbs if b["id"] == data.get("id")), None) if data.get("id") else None
        zone = data.get("zone") or (old or {}).get("zone") or "full"
        if zone not in color.ZONES:
            raise ValueError("Unknown screen zone")
        bulb = {"id": old["id"] if old else uuid.uuid4().hex[:8], "type": t, "ip": ip, "zone": zone,
                "name": str(data.get("name", "")).strip(), "enabled": bool(data.get("enabled", True))}
        if t == "tuya":
            dev_id = str(data.get("dev_id", "")).strip()
            key = str(data.get("key", "")).strip() or (old or {}).get("key", "")
            if not dev_id or not key:
                raise ValueError("Tuya/Wipro bulbs need a device ID and local key")
            bulb.update(dev_id=dev_id, key=key, version=str(data.get("version") or "3.5"))
        if old:
            self.bulbs[self.bulbs.index(old)] = bulb
        else:
            self.bulbs.append(bulb)
        return bulb

    def remove_bulb(self, bulb_id: str):
        self.bulbs = [b for b in self.bulbs if b["id"] != bulb_id]

    def set_enabled(self, bulb_id: str, enabled: bool):
        for b in self.bulbs:
            if b["id"] == bulb_id:
                b["enabled"] = bool(enabled)

    def set_zone(self, bulb_id: str, zone: str):
        if zone not in color.ZONES:
            raise ValueError("Unknown screen zone")
        for b in self.bulbs:
            if b["id"] == bulb_id:
                b["zone"] = zone

    def save(self):
        if not self.persist:
            return
        CONFIG_PATH.write_text(json.dumps(asdict(self), indent=2))
        try:
            os.chmod(CONFIG_PATH, 0o600)  # holds Tuya local keys
        except OSError:
            pass

    @classmethod
    def load(cls):
        s = cls()
        try:
            data = json.loads(CONFIG_PATH.read_text())
        except (OSError, ValueError):
            return s
        s.update(data)
        s.bulbs = [b for b in data.get("bulbs", []) if isinstance(b, dict) and b.get("id")]
        for b in s.bulbs:
            b.setdefault("zone", "full")
        if not s.bulbs and data.get("bulb_ip"):  # migrate the old single-bulb config
            s.upsert_bulb({"type": "wiz", "ip": data["bulb_ip"], "name": "WiZ bulb"})
        return s


class _Pipeline:
    """Colour state for one screen zone: extraction -> saturation -> smoothing -> lead prediction -> bulb values."""

    def __init__(self):
        self.smoothed = None
        self.vel = (0.0, 0.0, 0.0)
        self.prev_c = None
        self.prev_t = None
        self.last_sent = None

    def step(self, img, s: Settings):
        """Returns (rgb, dimming, shown_rgb) when the bulbs should be updated, else None."""
        raw = color.boost_saturation(color.EXTRACTORS[s.mode](img), s.saturation)
        self.smoothed = color.smooth(self.smoothed, raw, s.smoothing ** (10 / s.rate))
        now = time.monotonic()
        if self.prev_c is not None and now > self.prev_t:
            inst = tuple((a - b) / (now - self.prev_t) for a, b in zip(self.smoothed, self.prev_c))
            self.vel = color.smooth(self.vel, inst, 0.6)  # damp noise in the velocity estimate
        self.prev_c, self.prev_t = self.smoothed, now
        target = tuple(max(0.0, min(255.0, c + v * s.lead / 1000)) for c, v in zip(self.smoothed, self.vel))
        level = color.brightness_of(target)
        if level < 0.02:  # near-black frame: keep the hue, let brightness fall
            rgb = self.last_sent[0] if self.last_sent else (255, 255, 255)
        else:
            rgb = tuple(c / level for c in target)  # normalise; brightness goes via dimming
        dim = s.brightness if s.brightness_mode == "fixed" else s.min_brightness + (100 - s.min_brightness) * level
        out = (tuple(round(c) for c in rgb), round(dim))
        if self.last_sent is not None and color.distance(out[0], self.last_sent[0]) <= 2 and abs(out[1] - self.last_sent[1]) <= 1:
            return None
        self.last_sent = out
        return out[0], out[1], [round(c * out[1] / 100) for c in out[0]]


class Engine:
    def __init__(self, settings: Settings, bulb_factory, capturer_factory):
        self.settings = settings
        self._bulb_factory = bulb_factory
        self._capturer_factory = capturer_factory
        self._executor = ThreadPoolExecutor(max_workers=1)  # capture state lives in one thread
        self._capturer = None
        self._task: asyncio.Task | None = None
        self._listeners: set[asyncio.Queue] = set()
        self.status = {"running": False, "color": [0, 0, 0], "zones": {}, "fps": 0.0, "error": None}

    # -- pub/sub for the websocket
    def subscribe(self) -> asyncio.Queue:
        q = asyncio.Queue(maxsize=1)
        self._listeners.add(q)
        return q

    def unsubscribe(self, q):
        self._listeners.discard(q)

    def _publish(self):
        for q in self._listeners:
            if q.full():
                q.get_nowait()
            q.put_nowait(dict(self.status))

    # -- capture helpers (run on the dedicated thread)
    def _cap(self):
        if self._capturer is None:
            self._capturer = self._capturer_factory()
        return self._capturer

    async def _run_cap(self, fn, *args):
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(self._executor, lambda: fn(self._cap(), *args))

    async def monitors(self):
        return await self._run_cap(lambda c: c.monitors())

    async def preview(self, index: int):
        return await self._run_cap(lambda c, i: c.preview_jpeg(i), index)

    # -- control
    async def start(self):
        if self._task and not self._task.done():
            return
        if not self.settings.enabled_bulbs():
            raise ValueError("Add and enable at least one bulb first")
        self._task = asyncio.create_task(self._loop())

    async def stop(self):
        was_running = self._task is not None
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        self.status.update(running=False, fps=0.0)
        self._publish()
        if was_running:
            await self._apply_stop_action()

    async def _apply_stop_action(self):
        action = self.settings.stop_action
        if action == "leave":
            return
        bulbs = [self._bulb_factory(b) for b in self.settings.enabled_bulbs()]
        try:
            for b in bulbs:
                try:
                    await (b.turn_off() if action == "off" else b.set_rgb(*WARM_WHITE))
                except Exception as e:
                    self.status.update(error=str(e))
            await asyncio.sleep(0.4)  # let datagrams / the Tuya worker thread send before closing
        finally:
            for b in bulbs:
                b.close()

    async def _loop(self):
        s = self.settings
        bulbs = [(b.get("zone", "full"), self._bulb_factory(b)) for b in s.enabled_bulbs()]
        pipes = {zone: _Pipeline() for zone, _ in bulbs}
        first_zone = next(iter(pipes))
        self.status.update(running=True, error=None, zones={})
        frames, fps_t = 0, time.monotonic()
        try:
            while True:
                tick = time.monotonic()
                try:
                    img = await self._run_cap(lambda c, i: c.grab(i), s.monitor)
                    if s.ignore_black_bars:
                        img = color.crop_black_bars(img)
                    for zone, pipe in pipes.items():
                        out = pipe.step(color.zone_crop(img, zone), s)
                        if out is None:
                            continue
                        rgb, dim, shown = out
                        results = await asyncio.gather(
                            *(b.set_rgb(*rgb, dim) for z, b in bulbs if z == zone), return_exceptions=True)
                        errs = [str(r) for r in results if isinstance(r, Exception)]
                        if errs:
                            raise RuntimeError("; ".join(errs))
                        self.status["zones"][zone] = shown
                        if zone == first_zone:
                            self.status["color"] = shown
                    self.status.update(error=None)
                except Exception as e:  # keep the loop alive; surface the problem in the UI
                    self.status.update(error=str(e))
                bulb_errs = [b.error for _, b in bulbs if getattr(b, "error", None)]
                if bulb_errs:  # reported asynchronously by worker-thread bulbs (Tuya)
                    self.status.update(error="; ".join(bulb_errs))
                frames += 1
                now = time.monotonic()
                if now - fps_t >= 1:
                    self.status["fps"] = round(frames / (now - fps_t), 1)
                    frames, fps_t = 0, now
                self._publish()
                await asyncio.sleep(max(0.0, 1 / s.rate - (time.monotonic() - tick)))
        finally:
            for _, b in bulbs:
                b.close()

    def close(self):
        if self._capturer:
            self._executor.submit(self._capturer.close)
        self._executor.shutdown(wait=False)
