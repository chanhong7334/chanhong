"""
Phone Webcam - PC Server
========================
Receives a JPEG frame stream from the "내 폰 웹캠" Android app over a plain
WebSocket (no TLS needed - this is a native app, not a browser, so there's
no getUserMedia secure-context restriction) and republishes it as a system
virtual camera device (via pyvirtualcam) that any app - VOOV Meeting, Zoom,
OBS, Discord, etc. - can pick as its "camera".

Windows setup required once, before running this:
  1. Install OBS Studio (https://obsproject.com/) - just install it.
  2. Open OBS -> click "Start Virtual Camera" -> click "Stop Virtual Camera".
     (This registers the "OBS Virtual Camera" DirectShow device in Windows.
      You do NOT need OBS running afterwards.)
  3. Close OBS.

Then:
  pip install -r requirements.txt
  python server.py

The script prints the PC's LAN IP. Open the "내 폰 웹캠" app on your phone
(same Wi-Fi), enter that IP (or let auto-discovery fill it in), tap 연결.
In VOOV Meeting's video settings, choose "OBS Virtual Camera" as the camera.
"""

import asyncio
import json
import socket
from pathlib import Path

import cv2
import numpy as np
from aiohttp import web, WSMsgType

try:
    import pyvirtualcam
    from pyvirtualcam import PixelFormat
    PYVIRTUALCAM_AVAILABLE = True
except Exception as e:  # pragma: no cover - depends on OS/backend
    PYVIRTUALCAM_AVAILABLE = False
    _PYVIRTUALCAM_IMPORT_ERROR = e

WIDTH, HEIGHT, FPS = 1280, 720, 30
WS_PORT = 8765
DISCOVERY_PORT = 47777
DISCOVERY_INTERVAL_SEC = 2.0

_cam = None  # lazily-created pyvirtualcam.Camera
_cam_failed = False
_cam_lock = asyncio.Lock()
_frame_count = 0
_clients_connected = 0


def get_local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def letterbox(frame: np.ndarray, target_w: int, target_h: int) -> np.ndarray:
    """Resize `frame` to fit inside target_w x target_h, padding with black
    bars so the aspect ratio (and therefore no stretching/distortion) is
    preserved."""
    h, w = frame.shape[:2]
    scale = min(target_w / w, target_h / h)
    nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
    resized = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_AREA)
    canvas = np.zeros((target_h, target_w, 3), dtype=np.uint8)
    x_off = (target_w - nw) // 2
    y_off = (target_h - nh) // 2
    canvas[y_off:y_off + nh, x_off:x_off + nw] = resized
    return canvas


async def get_virtual_camera():
    """Create the pyvirtualcam.Camera on first use (lazy, so the process
    doesn't crash at import time if no backend is installed yet). Returns
    None (instead of raising) if no virtual-camera backend is available,
    so callers can fall back gracefully."""
    global _cam, _cam_failed
    async with _cam_lock:
        if _cam is None and not _cam_failed:
            try:
                _cam = pyvirtualcam.Camera(width=WIDTH, height=HEIGHT, fps=FPS, fmt=PixelFormat.BGR)
                print(f"[+] 가상 카메라 시작됨: {_cam.device}")
            except Exception as e:
                _cam_failed = True
                print(f"[경고] 가상 카메라 생성 실패: {e}")
                print("       Windows: OBS Studio를 설치 후 '가상 카메라 시작'을 한 번 눌렀다 꺼보세요.")
        return _cam


async def index(request):
    body = (
        "<html><body style='font-family:sans-serif'>"
        "<h2>Phone Webcam Server</h2>"
        f"<p>WebSocket endpoint: ws://{get_local_ip()}:{WS_PORT}/ws</p>"
        f"<p>Virtual camera backend available: {PYVIRTUALCAM_AVAILABLE}</p>"
        f"<p>Frames received: {_frame_count}</p>"
        "</body></html>"
    )
    return web.Response(text=body, content_type="text/html")


async def ws_handler(request):
    global _frame_count, _clients_connected
    ws = web.WebSocketResponse(max_msg_size=10 * 1024 * 1024, heartbeat=20)
    await ws.prepare(request)
    _clients_connected += 1
    print(f"[+] 폰 연결됨 ({request.remote}) - 총 연결: {_clients_connected}")

    try:
        async for msg in ws:
            if msg.type == WSMsgType.BINARY:
                arr = np.frombuffer(msg.data, dtype=np.uint8)
                frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                if frame is None:
                    continue
                frame = letterbox(frame, WIDTH, HEIGHT)
                _frame_count += 1

                cam = await get_virtual_camera() if PYVIRTUALCAM_AVAILABLE else None
                if cam is not None:
                    try:
                        cam.send(frame)
                        cam.sleep_until_next_frame()
                    except Exception as e:
                        print(f"[경고] 프레임 전송 실패: {e}")
                else:
                    # Fallback so you can still verify the pipeline works
                    # even without a virtual-camera backend installed.
                    cv2.imwrite("preview_latest.jpg", frame)
            elif msg.type == WSMsgType.ERROR:
                print(f"[!] 웹소켓 오류: {ws.exception()}")
    finally:
        _clients_connected -= 1
        print(f"[-] 폰 연결 끊김 - 총 연결: {_clients_connected}")

    return ws


async def broadcast_discovery():
    """Periodically broadcasts {"app":"phone-webcam","port":WS_PORT} on the
    LAN so the phone app can auto-fill the PC's IP instead of the user
    having to type it in by hand."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    payload = json.dumps({"app": "phone-webcam", "port": WS_PORT}).encode("utf-8")
    while True:
        try:
            sock.sendto(payload, ("255.255.255.255", DISCOVERY_PORT))
        except Exception as e:
            print(f"[!] 검색 브로드캐스트 실패: {e}")
        await asyncio.sleep(DISCOVERY_INTERVAL_SEC)


async def on_startup(app):
    app["discovery_task"] = asyncio.create_task(broadcast_discovery())


async def on_cleanup(app):
    app["discovery_task"].cancel()
    global _cam
    if _cam is not None:
        _cam.close()


def main():
    app = web.Application()
    app.router.add_get("/", index)
    app.router.add_get("/ws", ws_handler)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)

    ip = get_local_ip()
    print("=" * 60)
    print("  Phone Webcam Server")
    print(f"  PC IP 주소: {ip}   (포트: {WS_PORT})")
    print("  -> 폰 앱에서 같은 Wi-Fi에 연결한 뒤 이 IP를 입력하거나,")
    print("     자동 검색이 채워줄 때까지 잠시 기다리세요.")
    if not PYVIRTUALCAM_AVAILABLE:
        print(f"  [경고] pyvirtualcam 백엔드를 찾을 수 없습니다 ({_PYVIRTUALCAM_IMPORT_ERROR}).")
        print("         OBS Studio를 설치하고 가상 카메라를 한 번 시작/중지 해보세요.")
        print("         지금은 preview_latest.jpg 파일로만 프레임을 저장합니다.")
    print("=" * 60)

    web.run_app(app, host="0.0.0.0", port=WS_PORT)


if __name__ == "__main__":
    main()
