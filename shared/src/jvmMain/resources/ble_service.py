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
import time

from bleak import BleakClient, BleakScanner

SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"

client = None
handle_to_uuid = {}
connected_address = None

# Single persistent event loop on a background thread
_loop = None
_loop_thread = None
_loop_ready = threading.Event()


def log(msg):
    ts = time.strftime("%H:%M:%S")
    ms = int(time.time() * 1000) % 1000
    print(f"[ble_service] {ts}.{ms:03d} {msg}", file=sys.stderr, flush=True)


def respond(obj):
    try:
        line = json.dumps(obj, ensure_ascii=False)
        log(f"RESPOND: {line[:200]}")
        sys.stdout.write(line + "\n")
        sys.stdout.flush()
    except BrokenPipeError:
        log("stdout pipe broken")
        sys.exit(0)
    except Exception as e:
        log(f"respond error: {e}")


def on_disconnect(ble_client):
    global connected_address
    addr = connected_address or "unknown"
    log(f"DISCONNECT_CB: device={addr} client_is_connected={ble_client.is_connected if ble_client else 'N/A'}")
    print(f"[BLE_EVENT] disconnected {addr}", file=sys.stderr, flush=True)


def _run_loop(loop):
    """Background thread: runs the event loop forever."""
    asyncio.set_event_loop(loop)
    loop.run_forever()


def _start_loop():
    """Start the persistent event loop if not already running."""
    global _loop, _loop_thread
    if _loop is not None:
        return
    _loop = asyncio.new_event_loop()
    _loop_thread = threading.Thread(target=_run_loop, args=(_loop,), daemon=True)
    _loop_thread.start()
    _loop_ready.set()
    log("Event loop started")


def run_async(coro):
    """Submit a coroutine to the persistent event loop and wait for result."""
    _start_loop()
    future = asyncio.run_coroutine_threadsafe(coro, _loop)
    return future.result(timeout=60)


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
    max_retries = 3
    log(f"CONNECT >>> address={address}, max_retries={max_retries}")

    if client:
        try:
            if client.is_connected:
                log("CONNECT: disconnecting previous client")
                await client.disconnect()
        except Exception as e:
            log(f"CONNECT: prev disconnect error: {e}")
        client = None
        handle_to_uuid = {}
        connected_address = None

    for attempt in range(max_retries):
        log(f"CONNECT: attempt {attempt + 1}/{max_retries}")
        client = BleakClient(address, timeout=15.0, disconnected_callback=on_disconnect)

        t0 = time.time()
        try:
            await client.connect()
            elapsed = time.time() - t0
            log(f"CONNECT: connect() OK in {elapsed:.2f}s, is_connected={client.is_connected}")
        except Exception as e:
            elapsed = time.time() - t0
            log(f"CONNECT: connect() FAILED after {elapsed:.2f}s: {type(e).__name__}: {e}")
            # connect() 内部会自动断开，清理后重试
            client = None
            if attempt < max_retries - 1:
                wait = 3 * (attempt + 1)
                log(f"CONNECT: waiting {wait}s before retry...")
                await asyncio.sleep(wait)
            continue

        # 连接成功，尝试发现服务
        log("CONNECT: discovering services...")
        t1 = time.time()
        try:
            services = await client.get_services()
            elapsed = time.time() - t1
            log(f"CONNECT: get_services() OK in {elapsed:.2f}s")
        except Exception as e:
            elapsed = time.time() - t1
            log(f"CONNECT: get_services() FAILED after {elapsed:.2f}s: {type(e).__name__}: {e}")
            try:
                await client.disconnect()
            except Exception:
                pass
            client = None
            if attempt < max_retries - 1:
                wait = 3 * (attempt + 1)
                log(f"CONNECT: waiting {wait}s before retry...")
                await asyncio.sleep(wait)
            continue

        # 服务发现成功，查找目标服务
        chars_map = {}
        found_service = False
        svc_count = 0
        for service in services:
            svc_count += 1
            log(f"  service: {service.uuid}")
            if service.uuid.lower() == SERVICE_UUID:
                found_service = True
                for char in service.characteristics:
                    handle_to_uuid[char.handle] = char.uuid
                    chars_map[char.uuid] = char.handle
                    log(f"    char: uuid={char.uuid} handle={char.handle} props={char.properties}")

        if not found_service:
            log(f"CONNECT: service {SERVICE_UUID} NOT FOUND (scanned {svc_count} services)")
            try:
                await client.disconnect()
            except Exception:
                pass
            client = None
            handle_to_uuid = {}
            if attempt < max_retries - 1:
                wait = 3 * (attempt + 1)
                log(f"CONNECT: waiting {wait}s before retry...")
                await asyncio.sleep(wait)
            continue

        # 成功！
        connected_address = address
        total_elapsed = time.time() - t0
        log(f"CONNECT: success, {len(chars_map)} chars, {svc_count} services, {total_elapsed:.2f}s")
        respond({"ok": True, "chars": chars_map})
        return

    # 所有重试都失败
    log(f"CONNECT: all {max_retries} attempts failed")
    respond({"ok": False, "error": f"Connect failed after {max_retries} attempts"})
    client = None


async def cmd_write(params):
    global client
    if not client or not client.is_connected:
        log("WRITE: not connected")
        respond({"ok": False, "error": "Not connected"})
        return

    handle = params["handle"]
    data = base64.b64decode(params["data"])
    uuid = handle_to_uuid.get(handle)
    if not uuid:
        log(f"WRITE: unknown handle={handle}")
        respond({"ok": False, "error": f"Unknown handle: {handle}"})
        return

    try:
        await client.write_gatt_char(uuid, data, response=True)
        log(f"WRITE: ok handle={handle} uuid={uuid} len={len(data)}")
        respond({"ok": True})
    except Exception as e:
        log(f"WRITE: FAILED handle={handle} uuid={uuid} error={type(e).__name__}: {e}")
        respond({"ok": False, "error": f"Write failed: {type(e).__name__}: {e}"})


async def cmd_disconnect_ble(params):
    global client, handle_to_uuid, connected_address
    log(f"disconnect_ble: connected={client.is_connected if client else 'N/A'}")
    if client and client.is_connected:
        try:
            await client.disconnect()
        except Exception as e:
            log(f"disconnect_ble error: {e}")
    client = None
    handle_to_uuid = {}
    connected_address = None
    respond({"ok": True})


def main():
    log("ble_service.py started, waiting for commands...")
    while True:
        try:
            line = sys.stdin.readline()
        except Exception as e:
            log(f"stdin error: {e}")
            break
        if not line:
            log("stdin EOF")
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

        try:
            if cmd_name == "scan":
                run_async(cmd_scan(cmd))
            elif cmd_name == "connect":
                run_async(cmd_connect(cmd))
            elif cmd_name == "write":
                run_async(cmd_write(cmd))
            elif cmd_name == "disconnect_ble":
                run_async(cmd_disconnect_ble(cmd))
            elif cmd_name == "exit":
                run_async(cmd_disconnect_ble(cmd))
                break
            else:
                respond({"ok": False, "error": f"Unknown: {cmd_name}"})
        except Exception as e:
            log(f"error: {e}\n{traceback.format_exc()}")
            respond({"ok": False, "error": str(e)})

    log("ble_service.py exiting")
    if client and client.is_connected:
        try:
            run_async(client.disconnect())
        except Exception:
            pass
    if _loop:
        _loop.call_soon_threadsafe(_loop.stop)


if __name__ == "__main__":
    main()
