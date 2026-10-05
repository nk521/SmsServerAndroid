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
  -d '{"to": "+15551234567", "message": "hello from pc"}'
```

Device IP and port are shown on screen. Default port 8080, changeable in the
app. The server runs as a foreground service and keeps running in the
background.

## Notes

- Needs a phone with a SIM; messages go through the carrier.
- Plain HTTP on the LAN. Do not expose it to the internet.