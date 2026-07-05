"""
Persistent BLE service subprocess for PCMonitor4DLG.
Communicates with Kotlin via stdin/stdout JSON lines.
Requires: pip install bleak
"""
import sys
import json
import asyncio
import base64
import threading
import traceback

from bleak import BleakClient, BleakScanner

# Target GATT service UUID
SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"

client = None  # BleakClient instance, persistent while connected
handle_to_uuid = {}  # GATT handle -> characteristic UUID
write_lock = threading.Lock()
connected_address = None


def log(msg):
    """Log to stderr (visible in Kotlin stderr drain thread)."""
    print(f"[ble_service] {msg}", file=sys.stderr, flush=True)


def respond(obj):
    """Write a JSON response line to stdout."""
    try:
        with write_lock:
            line = json.dumps(obj, ensure_ascii=False)
            log(f"RESPOND: {line[:200]}")
            sys.stdout.write(line + "\n")
            sys.stdout.flush()
    except BrokenPipeError:
        log("stdout pipe broken, exiting")
        sys.exit(0)
    except Exception as e:
        log(f"respond error: {e}")


def on_disconnect(ble_client):
    """Callback when BLE device disconnects unexpectedly."""
    global connected_address
    addr = connected_address or "unknown"
    log(f"!!! BLE DISCONNECTED unexpectedly: {addr} !!!")
    # Notify Kotlin side via a special message
    try:
        with write_lock:
            line = json.dumps({"event": "disconnected", "address": addr})
            sys.stdout.write(line + "\n")
            sys.stdout.flush()
    except Exception:
        pass


async def cmd_scan(params):
    timeout = params.get("timeout", 10)
    log(f"scan: timeout={timeout}")
    devices = await BleakScanner.discover(timeout=timeout)
    result = []
    for d in devices:
        name = d.name if d.name else ""
        addr = d.address if d.address else ""
        if addr:
            result.append({"name": name, "address": addr})
    log(f"scan: found {len(result)} devices")
    respond({"ok": True, "devices": result})


async def cmd_connect(params):
    global client, handle_to_uuid, connected_address
    address = params["address"]
    log(f"connect: address={address}")

    # Disconnect previous if any
    if client:
        try:
            if client.is_connected:
                log(f"connect: disconnecting previous client")
                await client.disconnect()
        except Exception as e:
            log(f"connect: prev disconnect error: {e}")
        client = None
        handle_to_uuid = {}
        connected_address = None

    client = BleakClient(address, timeout=15.0, disconnected_callback=on_disconnect)
    log(f"connect: calling client.connect()...")
    try:
        await client.connect()
    except Exception as e:
        log(f"connect failed: {e}")
        try:
            if client.is_connected:
                await client.disconnect()
        except Exception:
            pass
        respond({"ok": False, "error": f"Connect failed: {e}"})
        client = None
        return

    if not client.is_connected:
        log("connect: not connected after connect()")
        respond({"ok": False, "error": "Not connected after connect()"})
        client = None
        return

    connected_address = address
    log(f"connect: BLE connected! is_connected={client.is_connected}")

    # Discover services and characteristics
    log("connect: discovering services...")
    try:
        services = await client.get_services()
    except Exception as e:
        log(f"connect: service discovery failed: {e}")
        respond({"ok": False, "error": f"Service discovery failed: {e}"})
        try:
            await client.disconnect()
        except Exception:
            pass
        client = None
        connected_address = None
        return

    chars_map = {}  # uuid -> handle
    found_service = False

    for service in services:
        if service.uuid.lower() == SERVICE_UUID:
            found_service = True
            for char in service.characteristics:
                handle = char.handle
                uuid = char.uuid
                handle_to_uuid[handle] = uuid
                chars_map[uuid] = handle
                log(f"  char: {uuid} handle={handle}")

    if not found_service:
        log(f"connect: service {SERVICE_UUID} not found")
        respond({"ok": False, "error": f"Service {SERVICE_UUID} not found"})
        try:
            await client.disconnect()
        except Exception:
            pass
        client = None
        handle_to_uuid = {}
        connected_address = None
        return

    log(f"connect: success, {len(chars_map)} chars, is_connected={client.is_connected}")
    respond({"ok": True, "chars": chars_map})


async def cmd_write(params):
    global client, handle_to_uuid
    if not client:
        log("write: client is None")
        respond({"ok": False, "error": "Not connected (client=None)"})
        return
    if not client.is_connected:
        log(f"write: client.is_connected=False, device may have disconnected")
        respond({"ok": False, "error": "Not connected (disconnected)"})
        return

    handle = params["handle"]
    data_b64 = params["data"]
    data = base64.b64decode(data_b64)

    uuid = handle_to_uuid.get(handle)
    if not uuid:
        log(f"write: unknown handle {handle}, known={list(handle_to_uuid.keys())}")
        respond({"ok": False, "error": f"Unknown handle: {handle}"})
        return

    try:
        log(f"write: handle={handle} uuid={uuid} len={len(data)}")
        await client.write_gatt_char(uuid, data, response=False)
        log(f"write: OK")
        respond({"ok": True})
    except Exception as e:
        log(f"write FAILED: {type(e).__name__}: {e}")
        respond({"ok": False, "error": f"Write failed: {type(e).__name__}: {e}"})


async def cmd_disconnect_ble(params):
    """Disconnect BLE device but keep subprocess alive."""
    global client, handle_to_uuid, connected_address
    log(f"disconnect_ble: client={client}, is_connected={client.is_connected if client else 'N/A'}")
    if client and client.is_connected:
        try:
            await client.disconnect()
            log("disconnect_ble: disconnected OK")
        except Exception as e:
            log(f"disconnect_ble: error: {e}")
    client = None
    handle_to_uuid = {}
    connected_address = None
    respond({"ok": True})


def run_async(coro):
    """Run an async function in a new event loop (called from IO thread)."""
    loop = asyncio.new_event_loop()
    try:
        loop.run_until_complete(coro)
    finally:
        loop.close()


def main():
    """Read commands from stdin, dispatch to async handlers."""
    log("ble_service.py started, waiting for commands...")
    while True:
        try:
            line = sys.stdin.readline()
        except Exception as e:
            log(f"stdin read error: {e}")
            break
        if not line:
            log("stdin EOF, exiting")
            break

        line = line.strip()
        if not line:
            continue

        log(f"CMD: {line[:200]}")

        try:
            cmd = json.loads(line)
        except json.JSONDecodeError as e:
            respond({"ok": False, "error": f"Invalid JSON: {e}"})
            continue

        cmd_name = cmd.get("cmd", "")
        params = cmd

        try:
            if cmd_name == "scan":
                run_async(cmd_scan(params))
            elif cmd_name == "connect":
                run_async(cmd_connect(params))
            elif cmd_name == "write":
                run_async(cmd_write(params))
            elif cmd_name == "disconnect_ble":
                run_async(cmd_disconnect_ble(params))
            elif cmd_name == "exit":
                run_async(cmd_disconnect_ble(params))
                break  # exit means terminate subprocess
            else:
                respond({"ok": False, "error": f"Unknown command: {cmd_name}"})
        except Exception as e:
            log(f"command error: {e}\n{traceback.format_exc()}")
            respond({"ok": False, "error": f"{e}", "trace": traceback.format_exc()})

    # Cleanup on exit
    log("ble_service.py exiting")
    if client and client.is_connected:
        try:
            loop = asyncio.new_event_loop()
            loop.run_until_complete(client.disconnect())
            loop.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()
