"""Minimal WiZ local-UDP client (port 38899). Fire-and-forget for speed."""
import asyncio
import json
import socket
import threading
import time

PORT = 38899


class _Collector(asyncio.DatagramProtocol):
    def __init__(self):
        self.found = {}

    def datagram_received(self, data, addr):
        try:
            msg = json.loads(data)
        except ValueError:
            return
        res = msg.get("result") or {}
        self.found[addr[0]] = {"ip": addr[0], "mac": res.get("mac"), "state": res.get("state")}


async def discover(timeout: float = 2.0, broadcast: str = "255.255.255.255") -> list[dict]:
    loop = asyncio.get_running_loop()
    proto = _Collector()
    transport, _ = await loop.create_datagram_endpoint(
        lambda: proto, local_addr=("0.0.0.0", 0), family=socket.AF_INET, allow_broadcast=True
    )
    try:
        payload = json.dumps({"method": "getPilot", "params": {}}).encode()
        for _ in range(2):
            transport.sendto(payload, (broadcast, PORT))
            await asyncio.sleep(timeout / 2)
    finally:
        transport.close()
    return list(proto.found.values())


class Bulb:
    def __init__(self, ip: str):
        self.ip = ip
        self._transport = None

    async def _ensure(self):
        if self._transport is None or self._transport.is_closing():
            loop = asyncio.get_running_loop()
            self._transport, _ = await loop.create_datagram_endpoint(
                asyncio.DatagramProtocol, remote_addr=(self.ip, PORT)
            )

    async def _send(self, method: str, params: dict):
        await self._ensure()
        self._transport.sendto(json.dumps({"id": 1, "method": method, "params": params}).encode())

    async def set_rgb(self, r: int, g: int, b: int, dimming: int):
        """dimming is WiZ's 10-100 range."""
        await self._send("setPilot", {
            "state": True, "r": int(r), "g": int(g), "b": int(b),
            "dimming": max(10, min(100, int(dimming))),
        })

    async def turn_on(self):
        await self._send("setState", {"state": True})

    async def turn_off(self):
        await self._send("setState", {"state": False})

    def close(self):
        if self._transport:
            self._transport.close()
            self._transport = None


# ---------------------------------------------------------------- Tuya (Wipro)

class TuyaBulb:
    """Wipro/Tuya bulb over the LAN via tinytuya.

    tinytuya is blocking, so a worker thread owns the socket and always sends the *latest*
    pending command (stale frames are dropped, so a slow bulb never stalls the sync loop).
    Commands are fire-and-forget except the first one and every VERIFY_EVERY-th, whose reply
    is checked so a wrong key/IP/version shows up as an error instead of silence.
    """
    VERIFY_EVERY = 60

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.error: str | None = None
        self._dev = None
        self._pending = None
        self._cv = threading.Condition()
        self._closed = False
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    # -- device plumbing
    def _device(self):
        if self._dev is None:
            import tinytuya
            d = tinytuya.BulbDevice(
                self.cfg["dev_id"], address=self.cfg["ip"], local_key=self.cfg.get("key", ""),
                version=float(self.cfg.get("version") or 3.5),
            )
            d.set_socketPersistent(True)
            d.set_socketTimeout(2)
            self._dev = d
        return self._dev

    @staticmethod
    def _check(res):
        if isinstance(res, dict) and (res.get("Error") or res.get("Err")):
            raise RuntimeError(f"{res.get('Error') or 'Tuya error'} ({res.get('Err', '?')}); check IP, device ID, local key and version")

    def send_now(self, cmd: tuple, verify: bool = True):
        """Blocking send; returns nothing, raises on a reported error."""
        d = self._device()
        kind = cmd[0]
        if kind == "rgb":
            _, r, g, b, dim = cmd
            k = max(10, min(100, dim)) / 100  # brightness is the V of the HSV colour on Tuya bulbs
            res = d.set_colour(r * k, g * k, b * k, nowait=not verify)
        elif kind == "on":
            res = d.turn_on(nowait=not verify)
        else:
            res = d.turn_off(nowait=not verify)
        if verify:
            self._check(res)

    def _run(self):
        n = 0
        while True:
            with self._cv:
                while self._pending is None and not self._closed:
                    self._cv.wait()
                if self._closed:
                    return
                cmd, self._pending = self._pending, None
            try:
                self.send_now(cmd, verify=(n % self.VERIFY_EVERY == 0))
                self.error = None
                n += 1
            except Exception as e:
                self.error = f"{self.cfg.get('name') or self.cfg.get('ip')}: {e}"
                self._dev = None  # rebuild the connection next time
                n = 0
                time.sleep(0.5)

    def _post(self, cmd):
        with self._cv:
            self._pending = cmd
            self._cv.notify()

    # -- same interface as Bulb
    async def set_rgb(self, r, g, b, dimming):
        self._post(("rgb", r, g, b, int(dimming)))

    async def turn_on(self):
        self._post(("on",))

    async def turn_off(self):
        self._post(("off",))

    def close(self):
        with self._cv:
            self._closed = True
            self._cv.notify()
        d, self._dev = self._dev, None
        if d:
            try:
                d.close()
            except Exception:
                pass


class DemoBulb:
    """No-network stand-in used when WIZSYNC_DEMO=1."""

    def __init__(self, cfg: dict):
        self.cfg, self.error = cfg, None

    async def set_rgb(self, r, g, b, dimming):
        pass

    async def turn_on(self):
        pass

    async def turn_off(self):
        pass

    def close(self):
        pass


def make_bulb(cfg: dict):
    if cfg.get("type") == "tuya":
        return TuyaBulb(cfg)
    return Bulb(cfg["ip"])


def _scan_tuya(seconds: int) -> list[dict]:
    import tinytuya
    found = tinytuya.deviceScan(False, seconds)
    return [
        {"type": "tuya", "ip": ip, "dev_id": d.get("gwId") or d.get("id"), "version": str(d.get("version") or "3.5"),
         "product_key": d.get("productKey")}
        for ip, d in found.items()
    ]


async def discover_all(tuya_seconds: int = 8) -> list[dict]:
    """WiZ (UDP broadcast) and Tuya/Wipro (tinytuya scan) in parallel."""
    loop = asyncio.get_running_loop()
    wiz, tuya = await asyncio.gather(
        discover(), loop.run_in_executor(None, _scan_tuya, tuya_seconds), return_exceptions=True
    )
    out = []
    if not isinstance(wiz, Exception):
        out += [{"type": "wiz", **b} for b in wiz]
    if not isinstance(tuya, Exception):
        out += tuya
    return out
