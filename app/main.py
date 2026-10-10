import asyncio
import os
from contextlib import asynccontextmanager
from dataclasses import asdict
from pathlib import Path

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles

from . import bulb
from .capture import Capturer, DemoCapturer
from .engine import PRESETS, Engine, Settings

STATIC = Path(__file__).parent / "static"
DEMO = os.environ.get("WIZSYNC_DEMO") == "1"  # fake screen + fake bulbs, nothing is saved or sent
if DEMO:
    Settings.persist = False
    settings = Settings(mode="average", smoothing=0.3, min_brightness=45, saturation=1.5, stop_action="warm")
    settings.bulbs = [
        {"id": "demo1", "type": "wiz", "name": "Living room lamp", "ip": "192.168.1.21", "enabled": True, "zone": "left"},
        {"id": "demo2", "type": "tuya", "name": "Desk bulb (Wipro)", "ip": "192.168.1.22", "enabled": True, "zone": "right",
         "dev_id": "demo", "key": "demo", "version": "3.5"},
    ]
    make_bulb, capturer_factory = bulb.DemoBulb, DemoCapturer
else:
    settings = Settings.load()
    make_bulb, capturer_factory = bulb.make_bulb, Capturer
engine = Engine(settings, make_bulb, capturer_factory)


@asynccontextmanager
async def lifespan(app):
    yield
    await engine.stop()
    engine.close()


app = FastAPI(lifespan=lifespan)


@app.get("/")
def index():
    return FileResponse(STATIC / "index.html")


def public_config():
    return {**{k: v for k, v in asdict(settings).items() if k != "bulbs"}, "bulbs": settings.public_bulbs(), "demo": DEMO}


@app.get("/api/config")
def get_config():
    return public_config()


@app.post("/api/config")
def set_config(data: dict):
    settings.update(data)
    settings.save()
    return public_config()


@app.get("/api/monitors")
async def monitors():
    return await engine.monitors()


@app.get("/api/monitors/{index}/preview")
async def preview(index: int):
    try:
        jpg = await engine.preview(index)
    except ValueError as e:
        raise HTTPException(404, str(e))
    except Exception as e:
        raise HTTPException(500, f"Capture failed: {e}")
    return Response(jpg, media_type="image/jpeg", headers={"Cache-Control": "no-store"})


@app.post("/api/bulbs/discover")
async def discover():
    if DEMO:
        return [{"type": "wiz", "ip": "192.168.1.23", "mac": "demo"}]
    return await bulb.discover_all()


@app.post("/api/bulbs/save")
def save_bulb(data: dict):
    try:
        settings.upsert_bulb(data)
    except ValueError as e:
        raise HTTPException(400, str(e))
    settings.save()
    return public_config()


@app.post("/api/bulbs/remove")
def remove_bulb(data: dict):
    settings.remove_bulb(data.get("id", ""))
    settings.save()
    return public_config()


@app.post("/api/bulbs/enable")
def enable_bulb(data: dict):
    settings.set_enabled(data.get("id", ""), data.get("enabled", True))
    settings.save()
    return public_config()


@app.post("/api/bulbs/zone")
def bulb_zone(data: dict):
    try:
        settings.set_zone(data.get("id", ""), data.get("zone", "full"))
    except ValueError as e:
        raise HTTPException(400, str(e))
    settings.save()
    return public_config()


@app.get("/api/presets")
def presets():
    return list(PRESETS)


@app.post("/api/presets/{name}")
def apply_preset(name: str):
    try:
        settings.apply_preset(name)
    except ValueError as e:
        raise HTTPException(404, str(e))
    settings.save()
    return public_config()


@app.post("/api/bulbs/test")
async def test_bulb(data: dict):
    """Send a colour/on/off to one bulb (id) or every enabled bulb; reports per-bulb results."""
    targets = [b for b in settings.bulbs if b["id"] == data.get("id")] if data.get("id") else settings.enabled_bulbs()
    if not targets:
        raise HTTPException(400, "No bulb selected")
    loop = asyncio.get_running_loop()
    results = []
    for cfg in targets:
        label = cfg.get("name") or cfg["ip"]
        b = make_bulb(cfg)
        try:
            if "r" in data:
                cmd = ("rgb", data["r"], data["g"], data["b"], data.get("dimming", 100))
            else:
                cmd = ("on",) if data.get("state") is not False else ("off",)
            if isinstance(b, bulb.TuyaBulb):  # blocking + verified so a bad key reports an error
                await loop.run_in_executor(None, b.send_now, cmd, True)
            elif cmd[0] == "rgb":
                await b.set_rgb(*cmd[1:])
            elif cmd[0] == "on":
                await b.turn_on()
            else:
                await b.turn_off()
            await asyncio.sleep(0.05)  # let UDP datagrams leave before closing
            results.append({"id": cfg["id"], "name": label, "ok": True})
        except Exception as e:
            results.append({"id": cfg["id"], "name": label, "ok": False, "error": str(e)})
        finally:
            b.close()
    return {"results": results}


@app.post("/api/engine/start")
async def start():
    try:
        await engine.start()
    except ValueError as e:
        raise HTTPException(400, str(e))
    return engine.status


@app.post("/api/engine/stop")
async def stop():
    await engine.stop()
    return engine.status


@app.get("/api/engine/status")
def status():
    return engine.status


@app.websocket("/ws")
async def ws(sock: WebSocket):
    await sock.accept()
    q = engine.subscribe()
    try:
        await sock.send_json(engine.status)
        while True:
            await sock.send_json(await q.get())
    except WebSocketDisconnect:
        pass
    finally:
        engine.unsubscribe(q)


app.mount("/static", StaticFiles(directory=STATIC), name="static")
