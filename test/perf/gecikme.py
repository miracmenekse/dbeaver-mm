#!/usr/bin/env python3
"""TCP proxy that adds latency like an SSH tunnel to a remote DB, no root needed.
usage: gecikme.py [listen_port=15432] [target_port=5432] [one_way_ms=15]
Point the DBeaver connection at 127.0.0.1:<listen_port>. RTT = 2 * one_way_ms.
"""
import asyncio, sys

LISTEN, TARGET, DELAY = (int(a) for a in (sys.argv[1:] + ['15432', '5432', '15'][len(sys.argv) - 1:])[:3])


async def pipe(reader, writer):
    loop = asyncio.get_running_loop()
    # Each chunk is released DELAY ms after it arrived; order is kept (one queue per direction)
    queue = asyncio.Queue()

    async def send():
        while (item := await queue.get()) is not None:
            at, data = item
            await asyncio.sleep(max(0.0, at - loop.time()))
            writer.write(data)
            await writer.drain()
        writer.close()

    sender = asyncio.create_task(send())
    while data := await reader.read(65536):
        queue.put_nowait((loop.time() + DELAY / 1000, data))
    queue.put_nowait(None)
    await sender


async def handle(client_r, client_w):
    server_r, server_w = await asyncio.open_connection('127.0.0.1', TARGET)
    await asyncio.gather(pipe(client_r, server_w), pipe(server_r, client_w), return_exceptions=True)


async def main():
    server = await asyncio.start_server(handle, '127.0.0.1', LISTEN)
    print(f'127.0.0.1:{LISTEN} -> :{TARGET}, +{DELAY} ms each way (RTT +{2 * DELAY} ms)', flush=True)
    await server.serve_forever()


if __name__ == '__main__':
    asyncio.run(main())
