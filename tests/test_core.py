import asyncio

import numpy as np

from app import color
from app.engine import Engine, Settings


def solid(rgb, h=9, w=16):
    return np.full((h, w, 3), rgb, dtype=np.uint8)


def test_average_of_halves():
    img = solid((0, 0, 0))
    img[:, 8:] = (200, 100, 0)
    assert color.average_color(img) == (100.0, 50.0, 0.0)


def test_dominant_ignores_black_and_prefers_saturated():
    img = solid((0, 0, 0))
    img[:2, :4] = (250, 10, 10)          # small red patch
    img[4:, :] = (120, 120, 120)         # big grey area
    r, g, b = color.dominant_color(img)
    assert r > 200 and g < 40


def test_dominant_all_black():
    assert color.dominant_color(solid((0, 0, 0))) == (0.0, 0.0, 0.0)


def test_smoothing():
    assert color.smooth(None, (10, 10, 10), 0.5) == (10, 10, 10)
    assert color.smooth((0, 0, 0), (100, 100, 100), 0.75) == (25, 25, 25)


def test_saturation_boost_keeps_grey():
    assert color.boost_saturation((128, 128, 128), 2.0) == (128, 128, 128)


class FakeBulb:
    sent = []

    def __init__(self, cfg): self.error = None
    async def set_rgb(self, r, g, b, dim): FakeBulb.sent.append((r, g, b, dim))
    def close(self): pass


class FakeCap:
    frame = solid((255, 0, 0))
    def monitors(self): return [{"index": 1}, {"index": 2}]
    def grab(self, i): return FakeCap.frame
    def close(self): pass


def test_engine_sends_and_switches_colour():
    async def run():
        FakeBulb.sent.clear()
        s = Settings(smoothing=0.0, rate=12.0, saturation=1.0)
        s.upsert_bulb({"type": "wiz", "ip": "1.2.3.4"})
        e = Engine(s, FakeBulb, FakeCap)
        await e.start()
        await asyncio.sleep(0.3)
        FakeCap.frame = solid((0, 0, 255))
        await asyncio.sleep(0.3)
        await e.stop()
        n = len(FakeBulb.sent)
        await asyncio.sleep(0.3)
        assert len(FakeBulb.sent) == n  # nothing after stop
        e.close()

    asyncio.run(run())
    assert FakeBulb.sent[0][:3] == (255, 0, 0)
    assert FakeBulb.sent[-1][:3] == (0, 0, 255)
    assert all(10 <= d[3] <= 100 for d in FakeBulb.sent)


def test_engine_requires_bulb():
    async def run():
        e = Engine(Settings(), FakeBulb, FakeCap)
        try:
            await e.start()
        except ValueError:
            return True
        finally:
            e.close()
    assert asyncio.run(run())


def test_brightest_and_saturated_pick_different_colours():
    img = solid((30, 30, 30))
    img[:3, :] = (240, 240, 235)         # bright near-white band (~33%)
    img[3:5, :4] = (200, 0, 20)          # small vivid red patch
    br = color.brightest_color(img)
    sat = color.most_saturated_color(img)
    assert min(br) > 200                  # near white
    assert sat[0] > 150 and sat[1] < 60   # vivid red wins on saturation


def test_extractors_handle_black_and_modes_validate():
    black = solid((0, 0, 0))
    for fn in color.EXTRACTORS.values():
        assert max(fn(black)) == 0
    s = Settings()
    s.update({"mode": "bogus"})
    assert s.mode == "average"
    s.update({"mode": "saturated"})
    assert s.mode == "saturated"


def test_bulb_config_hides_and_keeps_keys():
    s = Settings()
    b = s.upsert_bulb({"type": "tuya", "ip": "1.1.1.1", "dev_id": "abc", "key": "SECRET", "name": "Wipro"})
    pub = s.public_bulbs()[0]
    assert "key" not in pub and pub["has_key"] is True
    s.upsert_bulb({"id": b["id"], "type": "tuya", "ip": "1.1.1.9", "dev_id": "abc", "key": ""})  # blank keeps old key
    assert s.bulbs[0]["key"] == "SECRET" and s.bulbs[0]["ip"] == "1.1.1.9"
    s.update({"bulbs": []})  # generic config updates must not touch bulbs
    assert len(s.bulbs) == 1


def test_tuya_requires_id_and_key():
    s = Settings()
    for bad in ({"type": "tuya", "ip": "1.1.1.1"}, {"type": "tuya", "ip": "1.1.1.1", "dev_id": "x"}, {"type": "wiz", "ip": ""}):
        try:
            s.upsert_bulb(bad)
            assert False, bad
        except ValueError:
            pass


def test_engine_drives_multiple_bulbs_and_surfaces_errors():
    class Flaky(FakeBulb):
        def __init__(self, cfg):
            super().__init__(cfg)
            self.error = "Wipro: bad key" if cfg["type"] == "tuya" else None

    async def run():
        FakeBulb.sent.clear()
        s = Settings(smoothing=0.0, rate=12.0)
        s.upsert_bulb({"type": "wiz", "ip": "1.1.1.1"})
        s.upsert_bulb({"type": "tuya", "ip": "1.1.1.2", "dev_id": "d", "key": "k"})
        e = Engine(s, Flaky, FakeCap)
        await e.start(); await asyncio.sleep(0.3); await e.stop(); e.close()
        return e.status["error"]

    assert "bad key" in asyncio.run(run())
    assert len(FakeBulb.sent) >= 2  # both bulbs received the colour


def test_tuya_bulb_sends_scaled_colour_and_reports_errors():
    from app.bulb import TuyaBulb

    class Dev:
        def __init__(self, fail=False): self.calls, self.fail = [], fail
        def set_colour(self, r, g, b, nowait=False):
            self.calls.append((round(r), round(g), round(b), nowait))
            return {"Error": "Check device key or version", "Err": "914"} if self.fail else {"dps": {}}
        def close(self): pass

    t = TuyaBulb({"dev_id": "d", "ip": "x", "key": "k", "version": "3.5"})
    t._dev = Dev()
    t.send_now(("rgb", 200, 100, 0, 50), verify=True)
    assert t._dev.calls == [(100, 50, 0, False)]       # brightness folded into the colour's V
    t._dev = Dev(fail=True)
    try:
        t.send_now(("rgb", 1, 2, 3, 100), verify=True)
        assert False
    except RuntimeError as e:
        assert "914" in str(e)
    t.close()


def test_black_bars_cropped_and_dark_frames_untouched():
    img = solid((200, 120, 40), h=54, w=96)
    img[:7] = 0
    img[-7:] = 0
    cropped = color.crop_black_bars(img)
    assert cropped.shape[0] == 40 and cropped.mean(axis=(0, 1))[0] > 190
    dark = solid((3, 3, 3), h=54, w=96)
    assert color.crop_black_bars(dark).shape == dark.shape        # nothing bright: leave it
    sliver = solid((0, 0, 0), h=54, w=96)
    sliver[26:28] = 255
    assert color.crop_black_bars(sliver).shape == sliver.shape    # tiny bright strip: don't zoom into it


def test_zone_crop_halves():
    img = solid((0, 0, 0), h=10, w=20)
    img[:, :10] = (255, 0, 0)
    img[:, 10:] = (0, 0, 255)
    assert color.average_color(color.zone_crop(img, "left")) == (255.0, 0.0, 0.0)
    assert color.average_color(color.zone_crop(img, "right")) == (0.0, 0.0, 255.0)
    assert color.zone_crop(img, "center").shape == (6, 10, 3)
    assert color.zone_crop(img, "full").shape == img.shape


def test_presets_apply_and_validate():
    s = Settings()
    s.apply_preset("party")
    assert s.mode == "saturated" and s.brightness_mode == "fixed" and s.rate == 30.0
    try:
        s.apply_preset("nope")
        assert False
    except ValueError:
        pass
    for name, p in __import__("app.engine", fromlist=["PRESETS"]).PRESETS.items():
        t = Settings(); t.apply_preset(name)
        assert all(getattr(t, k) == v for k, v in p.items()), name  # presets survive clamping unchanged


def test_zones_drive_different_bulbs_with_different_colours():
    class Rec(FakeBulb):
        def __init__(self, cfg):
            super().__init__(cfg); self.cfg = cfg; self.got = []
        async def set_rgb(self, r, g, b, dim): self.got.append((r, g, b))

    made = []
    def factory(cfg):
        b = Rec(cfg); made.append(b); return b

    class Split(FakeCap):
        def grab(self, i):
            img = solid((0, 0, 0), h=54, w=96)
            img[:, :48] = (255, 0, 0)
            img[:, 48:] = (0, 0, 255)
            return img

    async def run():
        s = Settings(smoothing=0.0, rate=12.0, saturation=1.0, lead=0, ignore_black_bars=False)
        s.upsert_bulb({"type": "wiz", "ip": "1.1.1.1", "zone": "left"})
        s.upsert_bulb({"type": "wiz", "ip": "1.1.1.2", "zone": "right"})
        e = Engine(s, factory, Split)
        await e.start(); await asyncio.sleep(0.3); await e.stop(); e.close()

    asyncio.run(run())
    by_ip = {b.cfg["ip"]: b.got for b in made}
    assert by_ip["1.1.1.1"][0] == (255, 0, 0) and by_ip["1.1.1.2"][0] == (0, 0, 255)


def test_stop_action_sends_warm_white_or_off():
    class Rec(FakeBulb):
        log = []
        def __init__(self, cfg): super().__init__(cfg)
        async def set_rgb(self, r, g, b, dim): Rec.log.append(("rgb", r, g, b, dim))
        async def turn_off(self): Rec.log.append(("off",))

    async def run(action):
        Rec.log.clear()
        s = Settings(rate=12.0, stop_action=action)
        s.upsert_bulb({"type": "wiz", "ip": "1.1.1.1"})
        e = Engine(s, Rec, FakeCap)
        await e.start(); await asyncio.sleep(0.2); n = len(Rec.log); await e.stop(); e.close()
        return Rec.log[n:]

    assert asyncio.run(run("leave")) == []
    assert asyncio.run(run("off")) == [("off",)]
    assert asyncio.run(run("warm")) == [("rgb", 255, 214, 170, 80)]
