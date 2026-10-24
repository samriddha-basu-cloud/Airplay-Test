"""Python side of AirCast.

Kotlin calls the plain functions at the bottom of this file (scan, play, stop, ...).
All pyatv work happens on one dedicated asyncio loop that runs in a background thread.
"""

import asyncio
import collections
import concurrent.futures
import json
import logging
import threading
import traceback

import pyatv
from pyatv.const import Protocol

# --------------------------------------------------------------------------- logging

_LOG = collections.deque(maxlen=400)


class _RingHandler(logging.Handler):
    def emit(self, record):
        try:
            _LOG.append("%s %s: %s" % (record.levelname[0], record.name.split(".")[-1], record.getMessage()))
        except Exception:
            pass


_handler = _RingHandler()
_handler.setLevel(logging.DEBUG)
_root = logging.getLogger()
_root.addHandler(_handler)
_root.setLevel(logging.INFO)
logging.getLogger("pyatv").setLevel(logging.DEBUG)

_log = logging.getLogger("aircast")

# --------------------------------------------------------------------------- loop

_loop = asyncio.new_event_loop()


def _run_loop():
    asyncio.set_event_loop(_loop)
    _loop.run_forever()


threading.Thread(target=_run_loop, name="aircast-loop", daemon=True).start()

_configs = {}  # identifier -> pyatv config from the most recent scan
_play_task = None
_current = None  # the asyncio task doing the streaming
_play_lock = threading.Lock()


def _submit(coro):
    return asyncio.run_coroutine_threadsafe(coro, _loop)


# --------------------------------------------------------------------------- scanning


async def _scan(timeout, host):
    kwargs = {"timeout": int(timeout), "protocol": {Protocol.RAOP, Protocol.AirPlay}}
    if host:
        kwargs["hosts"] = [host]
    confs = await pyatv.scan(_loop, **kwargs)

    found = []
    for conf in confs:
        if conf.get_service(Protocol.RAOP) is None:
            continue  # cannot stream audio to this device
        ident = conf.identifier
        _configs[ident] = conf
        try:
            model = conf.device_info.model.name
        except Exception:
            model = ""
        found.append(
            {
                "name": conf.name,
                "address": str(conf.address),
                "identifier": ident,
                "model": model,
            }
        )
    return found


def scan(timeout=5, host=""):
    """Return JSON: {"ok": bool, "devices": [...], "error": str}."""
    try:
        devices = _submit(_scan(timeout, host or None)).result(int(timeout) + 20)
        _log.info("scan found %d device(s)", len(devices))
        return json.dumps({"ok": True, "devices": devices, "error": ""})
    except Exception as ex:  # noqa: BLE001
        _log.error("scan failed: %s\n%s", ex, traceback.format_exc())
        return json.dumps({"ok": False, "devices": [], "error": "%s: %s" % (type(ex).__name__, ex)})


# --------------------------------------------------------------------------- playback


async def _play(identifier, address, path):
    global _current
    _current = asyncio.current_task()
    try:
        await _play_inner(identifier, address, path)
    finally:
        _current = None


async def _play_inner(identifier, address, path):
    conf = _configs.get(identifier)
    if conf is None:
        _log.info("device %s not cached, scanning %s", identifier, address)
        confs = await pyatv.scan(_loop, timeout=5, hosts=[address], protocol={Protocol.RAOP, Protocol.AirPlay})
        for c in confs:
            if c.identifier == identifier or str(c.address) == address:
                conf = c
                break
    if conf is None:
        raise RuntimeError("Device not found on the network")

    atv = await pyatv.connect(conf, _loop, protocol=Protocol.RAOP)
    try:
        _log.info("streaming %s to %s", path, conf.name)
        await atv.stream.stream_file(path)
        _log.info("finished streaming")
    finally:
        try:
            atv.close()
        except Exception:  # noqa: BLE001
            pass


def play(identifier, address, path):
    """Blocks until the track ends, is stopped, or fails.

    Returns JSON: {"ok": bool, "stopped": bool, "error": str}.
    """
    global _play_task
    with _play_lock:
        if _play_task is not None and not _play_task.done():
            return json.dumps({"ok": False, "stopped": False, "error": "Already playing"})
        _play_task = _submit(_play(identifier, address, path))
        task = _play_task
    try:
        task.result()
        return json.dumps({"ok": True, "stopped": False, "error": ""})
    except (asyncio.CancelledError, concurrent.futures.CancelledError):
        return json.dumps({"ok": True, "stopped": True, "error": ""})
    except Exception as ex:  # noqa: BLE001
        _log.error("play failed: %s\n%s", ex, traceback.format_exc())
        return json.dumps({"ok": False, "stopped": False, "error": "%s: %s" % (type(ex).__name__, ex)})


def stop():
    """Cancel the streaming task; play() returns once the device has been torn down."""
    task = _play_task
    if task is not None and not task.done():
        _loop.call_soon_threadsafe(lambda: _current.cancel() if _current is not None else None)
        return True
    return False


def is_playing():
    return _play_task is not None and not _play_task.done()


def get_log():
    return "\n".join(_LOG)

