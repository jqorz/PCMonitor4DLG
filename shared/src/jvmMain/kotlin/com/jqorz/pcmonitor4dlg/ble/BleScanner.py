import sys
import asyncio
from bleak import BleakScanner

async def scan(timeout_seconds=10):
    devices = await BleakScanner.discover(timeout=timeout_seconds)
    for d in devices:
        name = d.name if d.name else ""
        addr = d.address if d.address else ""
        print(f"{name}|{addr}")

if __name__ == "__main__":
    timeout = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    asyncio.run(scan(timeout))
