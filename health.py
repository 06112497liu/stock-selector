#!/usr/bin/env python3
"""
AI Stock Selector - Service Health Check Script

Polls the Spring Boot Actuator health endpoint every N seconds and prints
a structured health status report covering all middleware used by the project.

Usage:
    python health.py                      # defaults: http://localhost:8080, every 10s
    python health.py --host 0.0.0.0 --port 8081 --interval 5
    python health.py --once               # single check, no loop
    python health.py --json               # machine-readable JSON output per check

Exit codes (--once mode):
    0 = all components UP
    1 = one or more components DOWN
    2 = failed to reach the endpoint
"""

import argparse
import json
import os
import signal
import sys
import time
from datetime import datetime
from typing import Any, Dict, Optional
from urllib.error import URLError, HTTPError
from urllib.request import urlopen, Request

HEALTH_ENDPOINT = "/actuator/health"
INFO_ENDPOINT = "/actuator/info"

STATUS_ICONS = {
    "UP": "✅",
    "DOWN": "❌",
    "OUT_OF_SERVICE": "⚠️",
    "UNKNOWN": "❓",
}

STATUS_COLORS = {
    "UP": "\033[92m",
    "DOWN": "\033[91m",
    "OUT_OF_SERVICE": "\033[93m",
    "UNKNOWN": "\033[94m",
    "RESET": "\033[0m",
}


def colorize(text: str, status: str) -> str:
    """Wrap text in ANSI color codes if stdout is a TTY."""
    if not sys.stdout.isatty():
        return text
    color = STATUS_COLORS.get(status, "")
    reset = STATUS_COLORS["RESET"]
    return f"{color}{text}{reset}"


def fetch_json(url: str, timeout: int = 10) -> Optional[Dict[str, Any]]:
    """GET JSON from a URL, returning None on failure."""
    req = Request(url, headers={"Accept": "application/json"})
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)
    except (HTTPError, URLError, json.JSONDecodeError, OSError) as exc:
        print(f"[fetch] {url} -> {exc}", file=sys.stderr)
        return None


def build_url(host: str, port: int, path: str) -> str:
    host = host.strip().rstrip("/")
    if host.startswith("http://") or host.startswith("https://"):
        return f"{host}:{port}{path}"
    return f"http://{host}:{port}{path}"


def print_section(title: str) -> None:
    sep = "=" * max(60, len(title) + 4)
    print(f"\n{sep}")
    print(f"  {title}")
    print(sep)


def flatten_details(details: Any, prefix: str = "") -> Dict[str, Any]:
    """Flatten nested component details into key->value pairs."""
    flat: Dict[str, Any] = {}
    if isinstance(details, dict):
        for k, v in details.items():
            key = f"{prefix}.{k}" if prefix else k
            if isinstance(v, dict):
                flat.update(flatten_details(v, key))
            else:
                flat[key] = v
    else:
        flat[prefix or "value"] = details
    return flat


def print_component(name: str, data: Dict[str, Any]) -> str:
    """Print a single component's status + details, return its status string."""
    status = data.get("status", "UNKNOWN")
    icon = STATUS_ICONS.get(status, "?")
    status_text = colorize(f"{icon} {status:<6}", status)
    print(f"  {status_text}  {name}")

    details = data.get("details")
    if details:
        flat = flatten_details(details)
        for k, v in sorted(flat.items()):
            if isinstance(v, float):
                v_str = f"{v:,.2f}"
            else:
                v_str = str(v)
            print(f"           · {k}: {v_str}")
    return status


def print_report(health: Dict[str, Any], info: Optional[Dict[str, Any]]) -> int:
    """Print human-readable health report. Returns 0 if all UP, 1 otherwise."""
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    overall = health.get("status", "UNKNOWN")
    icon = STATUS_ICONS.get(overall, "?")
    print_section(f"HEALTH CHECK  @  {ts}")

    overall_text = colorize(f"{icon} OVERALL STATUS: {overall}", overall)
    print(f"  {overall_text}")

    components = health.get("components", {})
    exit_code = 0 if overall == "UP" else 1

    if components:
        print("\n  Components:")
        print("  " + "-" * 56)
        for name in sorted(components.keys()):
            comp = components[name]
            if isinstance(comp, dict) and "status" in comp:
                st = print_component(name, comp)
                if st == "DOWN":
                    exit_code = 1

    if info:
        print("\n  Service Info:")
        print("  " + "-" * 56)
        flat = flatten_details(info)
        for k, v in sorted(flat.items()):
            print(f"    {k}: {v}")

    print()
    return exit_code


def run_once(base_url: str, as_json: bool, timeout: int) -> int:
    health_url = build_url("", 0, "")  # unused; build via base_url
    health_url = f"{base_url}{HEALTH_ENDPOINT}"
    info_url = f"{base_url}{INFO_ENDPOINT}"

    health = fetch_json(health_url, timeout=timeout)
    if health is None:
        if as_json:
            print(json.dumps({
                "timestamp": datetime.now().isoformat(),
                "status": "UNREACHABLE",
                "error": f"Failed to reach {health_url}",
            }))
        else:
            ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            print_section(f"HEALTH CHECK  @  {ts}")
            print(f"  {colorize('❌ UNREACHABLE', 'DOWN')}  Could not reach {health_url}")
            print()
        return 2

    info = fetch_json(info_url, timeout=timeout)

    if as_json:
        payload = {
            "timestamp": datetime.now().isoformat(),
            "status": health.get("status"),
            "health": health,
        }
        if info:
            payload["info"] = info
        print(json.dumps(payload, ensure_ascii=False))
        return 0 if health.get("status") == "UP" else 1

    return print_report(health, info)


def handle_sigint(signum: int, frame: Any) -> None:
    print("\n[health] stopped by user", file=sys.stderr)
    sys.exit(0)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Poll the AI Stock Selector service health endpoint.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--host", default=os.environ.get("HEALTH_HOST", "localhost"),
                        help="Service hostname or base URL")
    parser.add_argument("--port", type=int, default=int(os.environ.get("HEALTH_PORT", "8080")),
                        help="Service port")
    parser.add_argument("--interval", type=int, default=int(os.environ.get("HEALTH_INTERVAL", "10")),
                        help="Polling interval in seconds (ignored with --once)")
    parser.add_argument("--timeout", type=int, default=10,
                        help="HTTP timeout per request in seconds")
    parser.add_argument("--once", action="store_true",
                        help="Run a single check and exit")
    parser.add_argument("--json", action="store_true",
                        help="Emit JSON lines instead of the human report")
    args = parser.parse_args()

    signal.signal(signal.SIGINT, handle_sigint)
    signal.signal(signal.SIGTERM, handle_sigint)

    base_url = build_url(args.host, args.port, "")

    if args.once:
        return run_once(base_url, args.json, args.timeout)

    print(f"[health] monitoring {base_url}{HEALTH_ENDPOINT} every {args.interval}s (Ctrl-C to stop)")
    while True:
        run_once(base_url, args.json, args.timeout)
        time.sleep(args.interval)


if __name__ == "__main__":
    sys.exit(main())
