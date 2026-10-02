#!/usr/bin/env python3
"""Exercise real Android NSD, host/phone transfers, clipboard URIs, persistence and Downloads.
Run after building/installing both debug APKs: tools/link-device-check.py --serial emulator-5564
Only a dedicated WeaveText test emulator/device should be used; this writes its test clipboard/history.
"""
import argparse, json, os, pathlib, subprocess, tempfile, time
root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--host", default="10.0.2.2", help="Host address reachable by the dedicated Android test device")
args = parser.parse_args()
adb = pathlib.Path(os.environ.get("ANDROID_HOME", str(pathlib.Path.home() / "Library/Android/sdk"))) / "platform-tools/adb"
with tempfile.TemporaryDirectory(prefix="weave-link-device-") as folder:
    directory = pathlib.Path(folder)
    log = (directory / "peer.log").open("w")
    peer = subprocess.Popen([str(root / "core/target/debug/examples/link-probe"), folder], stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 15
        while not (directory / "pair.json").exists():
            if peer.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError("Host test peer did not start")
            time.sleep(.05)
        pair = json.loads((directory / "pair.json").read_text())
        result = subprocess.run([str(adb), "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "com.weavetext.ime.link.LinkDeviceTest",
            "-e", "hostAddress", f"{args.host}:{pair['port']}", "-e", "hostCode", pair["code"],
            "com.weavetext.ime.test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, text=True, timeout=150)
        print(result.stdout)
        if result.returncode or "FAILURES" in result.stdout or "OK (2 tests)" not in result.stdout:
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
    finally:
        peer.terminate()
        try: peer.wait(timeout=5)
        except subprocess.TimeoutExpired: peer.kill(); peer.wait()
        log.close()
