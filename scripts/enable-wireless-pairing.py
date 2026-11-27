#!/usr/bin/env python3
"""Validate the native Wireless Debugging implementation in the Clean Your TV fork.

The pairing UI and STARTTLS transport live in the maintained submodule fork. This
script deliberately does not rewrite them: doing so would reintroduce the old
plain-only session manager or the incorrect TLS-before-CNXN transport.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
upstream = root / "vendor" / "cleanyourtv-app"

checks = {
    "app/src/main/kotlin/tv/cleanyour/app/AppGraph.kt": (
        "val wirelessAdbTransport = WirelessAdbSocketFactory(app)",
        "tls = wirelessAdbTransport",
        "endpointForTv = { tv -> WirelessAdbPairing.endpointForTv(app, tv) }",
        "signerForEndpoint = { endpoint -> if (endpoint is Endpoint.Tls) WirelessAdbPairing.adbSigner(app) else signer }",
    ),
    "core/flows/src/main/kotlin/tv/cleanyour/core/flows/SetupMachine.kt": (
        "SetupIntent.PairingCompleted",
        "deps.endpointForTv(tv)",
        "deps.sessions.open(endpoint, known?.id)",
    ),
    "adb/session/src/main/kotlin/tv/cleanyour/adb/session/AdbSessionManager.kt": (
        "private val signerForEndpoint: (Endpoint) -> AdbSigner = { signer }",
        "socket, signerForEndpoint(endpoint), tls",
    ),
    "adb/session/src/main/kotlin/tv/cleanyour/adb/session/AdbTvSession.kt": (
        "link.signerForEndpoint(endpoint)",
    ),
    "core/flows/src/main/kotlin/tv/cleanyour/core/flows/Flows.kt": (
        "data object PairingCompleted : SetupIntent",
    ),
    "feature/connect/src/main/kotlin/tv/cleanyour/feature/connect/ConnectScreens.kt": (
        "onPairing: suspend (String, Int) -> Unit",
        "Код сопряжения (6 цифр)",
        "Порт подключения (TLS)",
    ),
    "app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt": (
        "WirelessAdbPairing.pair(context, pairingTv, code, port)",
        "SetupIntent.PairingCompleted",
    ),
}

missing = []
for relative, markers in checks.items():
    path = upstream / relative
    if not path.is_file():
        missing.append(f"{relative}: file is missing")
        continue
    source = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in source:
            missing.append(f"{relative}: missing {marker!r}")

transport = root / "app/src/main/java/ru/tvapk/installer/WirelessAdbSocketFactory.kt"
if not transport.is_file():
    missing.append("app/src/main/java/ru/tvapk/installer/WirelessAdbSocketFactory.kt: file is missing")
else:
    source = transport.read_text(encoding="utf-8")
    for marker in ("override suspend fun upgrade(", "TLSv1.3", "StartTlsAdbSocket"):
        # A_STLS is handled by adb:wire; the transport's upgrade method is called from that handshake.
        if marker not in source:
            missing.append(f"WirelessAdbSocketFactory.kt: missing {marker!r}")

if missing:
    raise SystemExit("Wireless Debugging integration is incomplete:\n- " + "\n- ".join(missing))

print("Wireless Debugging integration verified: pairing UI, saved endpoint lookup, and ADB STARTTLS.")
