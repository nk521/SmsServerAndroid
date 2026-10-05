# SMS Server

Android app that runs a small HTTP server on the phone. A computer can send API
requests to send SMS through the device SIM.

## Endpoints

| Method | Path     | Description                          |
| ------ | -------- | ------------------------------------ |
| GET    | /status  | running state, IP, port, counters    |
| POST   | /send    | `{"to": "+15551234567", "message": "hi"}` |
| GET    | /log     | recent sends                         |
| GET    | /        | endpoint list                        |

Optional API token: set it in the app; requests then need
`Authorization: Bearer <token>` or `X-API-Key: <token>`.

## Build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Run

Open the app, allow the SMS permission, press **Start server**, then from a
computer on the same WiFi:

```bash
curl http://<device-ip>:8080/status
curl -X POST http://<device-ip>:8080/send \
  -H 'Content-Type: application/json' \
  -d '{"to": "+919876543210", "message": "<text>"}'
```

Device IP and port are shown on screen. Default port 8080, changeable in the
app. The server runs as a foreground service and keeps running in the
background.

## Python client

```python
import requests


class SmsServerClient:
    """HTTP client for the SMS Server app on the phone."""

    def __init__(self, host: str, port: int = 8080, token: str | None = None) -> None:
        self._base = f"http://{host}:{port}"
        self._headers = {"Content-Type": "application/json"}
        if token is not None:
            self._headers["Authorization"] = f"Bearer {token}"

    def status(self) -> dict:
        r = requests.get(f"{self._base}/status", headers=self._headers, timeout=10)
        r.raise_for_status()
        return r.json()

    def log(self) -> dict:
        r = requests.get(f"{self._base}/log", headers=self._headers, timeout=10)
        r.raise_for_status()
        return r.json()

    def send(self, to: str, message: str) -> dict:
        r = requests.post(
            f"{self._base}/send",
            headers=self._headers,
            json={"to": to, "message": message},
            timeout=10,
        )
        if r.status_code >= 400:
            detail = r.json().get("error", r.text)
            raise RuntimeError(f"{r.status_code}: {detail}")
        return r.json()


if __name__ == "__main__":
    client = SmsServerClient("192.168.1.111", port=9090)  # device IP : port
    print(client.status())
    print(client.send("+15551234567", "hello from python"))
```

Save as `client.py` and point it at the address shown on the app screen.

## Notes

- Needs a phone with a SIM; messages go through the carrier.
- Plain HTTP on the LAN. Do not expose it to the internet.

## License

GPL-3.0. See [LICENSE](LICENSE).
