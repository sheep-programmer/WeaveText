#!/usr/bin/env python3
"""Exercise real Android NSD, host/phone transfers, clipboard URIs, persistence and Downloads.
Run after building/installing both debug APKs: tools/link-device-check.py --serial emulator-5564
Only a dedicated WeaveText test emulator/device should be used; this writes its test clipboard/history.
"""
import argparse, base64, json, os, pathlib, socket, struct, subprocess, tempfile, threading, time
root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--host", default="10.0.2.2", help="Host address reachable by the dedicated Android test device")
parser.add_argument("--direct", action="store_true", help="Exercise QUIC, STUN and the emulator's UDP NAT; no TCP transfer")
args = parser.parse_args()
adb = pathlib.Path(os.environ.get("ANDROID_HOME", str(pathlib.Path.home() / "Library/Android/sdk"))) / "platform-tools/adb"
with tempfile.TemporaryDirectory(prefix="weave-link-device-") as folder:
    directory = pathlib.Path(folder)
    log = (directory / "peer.log").open("w")
    peer = subprocess.Popen([str(root / "core/target/debug/examples/link-probe"), folder] + (["--direct"] if args.direct else []), stdout=log, stderr=log)
    stun = None
    process = None
    try:
        deadline = time.monotonic() + 15
        while not (directory / "pair.json").exists():
            if peer.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError("Host test peer did not start")
            time.sleep(.05)
        pair = json.loads((directory / "pair.json").read_text())
        command = [str(adb), "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", "com.weavetext.ime.link.LinkDeviceTest"]
        if args.direct:
            # Test STUN only reports the UDP mapping created by the emulator; it never forwards data.
            stun = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            stun.bind(("0.0.0.0", 0)); stun.settimeout(.2)
            def stun_loop():
                while True:
                    try: packet, addr = stun.recvfrom(2048)
                    except socket.timeout: continue
                    except OSError: break
                    if len(packet) != 20 or packet[:2] != b"\x00\x01" or packet[4:8] != b"\x21\x12\xa4\x42": continue
                    ip = bytes(a ^ b for a,b in zip(socket.inet_aton(addr[0]), packet[4:8]))
                    response = b"\x01\x01\x00\x0c" + packet[4:20] + b"\x00\x20\x00\x08\x00\x01" + struct.pack("!H", addr[1] ^ 0x2112) + ip
                    try: stun.sendto(response, addr)
                    except OSError: break
            threading.Thread(target=stun_loop, daemon=True).start()
            ticket = pair["ticket"]
            encoded = ticket.removeprefix("weavelink://direct/")
            data = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
            port = int(data["addrs"][0].rsplit(":", 1)[1])
            data["addrs"] = [f"{args.host}:{port}"]
            ticket = "weavelink://direct/" + base64.urlsafe_b64encode(json.dumps(data).encode()).decode().rstrip("=")
            command += ["-e", "hostTicket", ticket, "-e", "stunAddress", f"{args.host}:{stun.getsockname()[1]}"]
        else:
            command += ["-e", "hostAddress", f"{args.host}:{pair['port']}", "-e", "hostCode", pair["code"]]
        command += ["com.weavetext.ime.test/androidx.test.runner.AndroidJUnitRunner"]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        watchdog = threading.Timer(150, process.kill); watchdog.start()
        lines = []
        try:
            for line in process.stdout:
                lines.append(line)
                if line.startswith("INSTRUMENTATION_STATUS: directTicket="):
                    (directory / "phone-ticket").write_text(line.split("=", 1)[1].strip())
            process.wait()
        finally: watchdog.cancel()
        output = "".join(lines)
        print(output)
        if process.returncode or "FAILURES" in output or "OK (2 tests)" not in output:
            log.flush(); print((directory / "peer.log").read_text()); raise RuntimeError("Android device checks failed")
        deadline = time.monotonic() + 10
        while not (directory / "inbox/phone-return.bin").exists() and time.monotonic() < deadline:
            time.sleep(.05)
        received = (directory / "inbox/phone-return.bin").read_bytes()
        assert received == bytes(i % 253 for i in range(90_000)), "Return transfer byte mismatch"
        profile=directory / "state/personal-inbox/phone-personal.weaveprofile"
        deadline=time.monotonic()+10
        while not profile.exists() and time.monotonic()<deadline:time.sleep(.05)
        records=json.loads(profile.read_text())["personal"]["records"]
        assert records["snippet:phone"]["value"]=="来自手机"
        assert records["pin:pinyin:shi"]["value"]=="嗜"
        print("PASS: private personal profile roundtrip, merged preferences and phone shortcut")
        print("PASS: system NSD + Mac/Android image/file bytes + clipboard/history + Downloads + return transfer")
        if args.direct:
            assert '"transport":"direct-udp"' in (directory / "peer.log").read_text()
            print("PASS: STUN-discovered emulator UDP mapping + direct QUIC transfer, without TCP or a data relay")
    finally:
        if process is not None and process.poll() is None: process.kill(); process.wait()
        if stun is not None: stun.close()
        peer.terminate()
        try: peer.wait(timeout=5)
        except subprocess.TimeoutExpired: peer.kill(); peer.wait()
        log.close()
