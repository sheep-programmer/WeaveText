#!/usr/bin/env python3
"""Inject speech into an emulator microphone and verify text in an ordinary editor.

Uses generated emulator_controller_pb2 modules and grpcio in a test-only environment.
Open SpeechEditorActivity with the public APK's voice panel in hold mode first.
The coordinates must match the dedicated emulator. No text is supplied to the IME.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import threading
import wave
import xml.etree.ElementTree as ET

import grpc
import emulator_controller_pb2 as pb
import emulator_controller_pb2_grpc as rpc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("wav", type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--discovery", type=Path, required=True, help="This emulator's pid_*.ini file")
    parser.add_argument("--mic", type=int, nargs=2, required=True, metavar=("X", "Y"))
    parser.add_argument("--delete", type=int, nargs=2, required=True, metavar=("X", "Y"))
    parser.add_argument("--expect", required=True, help="Words spoken in the recording")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--mode", choices=("hold", "tap"), default="hold")
    parser.add_argument("--choose", type=int, nargs=2, metavar=("X", "Y"),
                        help="Tap this result row after stopping a multi-model recording")
    parser.add_argument("--choose-wait-seconds", type=float, default=60,
                        help="Wait for the selected row to finish (default covers the offline timeout)")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--pause-seconds", type=float, default=8)
    parser.add_argument("--wave-snapshots", action="store_true", help="Capture speaking and pause frames")
    args = parser.parse_args()
    if args.repeat < 1 or args.pause_seconds < 0 or args.choose_wait_seconds < 0:
        parser.error("Invalid repetition, pause or result wait")
    if not args.serial.startswith("emulator-"):
        parser.error("This microphone injection test requires a dedicated emulator")

    adb = [args.adb, "-s", args.serial]

    def shell(*words):
        return subprocess.run(adb + ["shell", *map(str, words)], check=True, capture_output=True, text=True).stdout

    def editor_text():
        shell("uiautomator", "dump", "/sdcard/weave-voice-e2e.xml")
        xml = shell("cat", "/sdcard/weave-voice-e2e.xml")
        texts = [node.attrib["text"] for node in ET.fromstring(xml).iter("node")
                 if node.attrib.get("content-desc") == "voice_smoke_editor"]
        if len(texts) != 1:
            raise AssertionError("The test editor is not visible")
        return texts[0]

    def capturing():
        process = subprocess.run(adb + ["shell", "pidof", "com.weavetext.ime"], capture_output=True, text=True)
        pids = process.stdout.split()
        if not pids: return False
        pid = pids[0]
        capture = shell("dumpsys", "media.audio_flinger")
        return bool(re.search(r"^\s*yes\s+\d+\s+" + pid + r"(?:/|\s)", capture, re.M))

    values = dict(line.split("=", 1) for line in args.discovery.read_text().splitlines() if "=" in line)
    metadata = [("authorization", "Bearer " + values["grpc.token"])]
    endpoint = "127.0.0.1:" + values["grpc.port"]
    with wave.open(str(args.wav)) as audio:
        if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 16000):
            raise AssertionError("The test sample must be mono 16 kHz PCM16 WAV")
        pcm = audio.readframes(audio.getnframes())
    fmt = pb.AudioFormat(samplingRate=16000, channels=pb.AudioFormat.Mono,
                         format=pb.AudioFormat.AUD_FMT_S16, mode=pb.AudioFormat.MODE_UNSPECIFIED)

    def packets():
        data = bytes(9600) + (bytes(int(32000 * args.pause_seconds)).join([pcm] * args.repeat)) + bytes(32000)
        # MODE_UNSPECIFIED is queue-paced by the emulator. Wall-clock pacing as well
        # causes under-runs when a busy host cannot schedule Python every 20 ms.
        for offset in range(0, len(data), 1280):
            yield pb.AudioPacket(format=fmt, audio=data[offset:offset + 1280])

    current_ime = shell("settings", "get", "secure", "default_input_method").strip()
    if current_ime != "com.weavetext.ime/.ime.WeaveImeService":
        raise AssertionError("Select the installed WeaveText IME first: " + current_ime)
    if editor_text():
        raise AssertionError("Start with an empty editor to prove text came from speech")
    if capturing():
        raise AssertionError("Stop the existing recording first to prove the start button works")
    wave_frames = []
    wave_errors = []
    wave_thread = None
    with grpc.insecure_channel(endpoint) as channel:
        stub = rpc.EmulatorControllerStub(channel)
        # Use injected audio only, without recording the host's microphone.
        stub.setMicrophoneState(pb.MicrophoneState(realAudioEnabled=False), metadata=metadata, timeout=5)
        if args.mode == "hold": shell("input", "motionevent", "DOWN", *args.mic)
        else: shell("input", "tap", *args.mic)
        try:
            ready_deadline = time.monotonic() + 85
            while True:
                if capturing(): break
                if time.monotonic() > ready_deadline: raise AssertionError("The actual IME's AudioRecord did not start")
                time.sleep(0.3)
            print("Microphone ready; injecting", args.repeat, "speech segments with", args.pause_seconds, "second pauses", flush=True)
            if args.wave_snapshots:
                def capture_waves():
                    started = time.monotonic()
                    try:
                        for offset, label in [(3.0, "speaking"), (len(pcm) / 32000 + 3.3, "pause")]:
                            time.sleep(max(0, started + offset - time.monotonic()))
                            path = args.report.with_name(args.report.stem + "-wave-" + label + ".png")
                            with path.open("wb") as output:
                                subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=output, check=True)
                            wave_frames.append(str(path))
                    except Exception as error: wave_errors.append(str(error))
                wave_thread = threading.Thread(target=capture_waves)
                wave_thread.start()
            duration = len(pcm) / 32000 * args.repeat + args.pause_seconds * (args.repeat - 1) + 1.3
            stub.injectAudio(packets(), metadata=metadata, timeout=max(90, duration + 30))
        finally:
            if args.mode == "hold": shell("input", "motionevent", "UP", *args.mic)
            else: shell("input", "tap", *args.mic)
    if wave_thread:
        wave_thread.join()
        if wave_errors: raise AssertionError(wave_errors)
    stopped_deadline = time.monotonic() + 5
    while capturing():
        if time.monotonic() > stopped_deadline: raise AssertionError("The stop button did not stop the microphone")
        time.sleep(0.1)
    empty_before_choice = None
    if args.choose:
        # The recognizers must not commit before the user selects one of their rows.
        time.sleep(args.choose_wait_seconds)
        empty_before_choice = editor_text()
        if empty_before_choice:
            raise AssertionError("Multi-model results committed without a choice: " + repr(empty_before_choice))
        with args.report.with_name(args.report.stem + "-results.png").open("wb") as out:
            subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=out, check=True)
        shell("input", "tap", *args.choose)
    deadline = time.monotonic() + 60
    while True:
        text = editor_text()
        if text.count(args.expect) >= args.repeat:
            break
        if time.monotonic() >= deadline:
            raise AssertionError("Speech did not reach the editor: " + repr(text))
        time.sleep(0.2)
    print("Recognized and committed:", text, flush=True)
    with args.report.with_suffix(".png").open("wb") as out:
        subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=out, check=True)
    for _ in text:
        shell("input", "tap", *args.delete)
    after = editor_text()
    if after:
        raise AssertionError("Recognized text could not be deleted completely: " + repr(after))
    report = {"input": "emulated microphone PCM", "wav": str(args.wav), "text": text,
              "mode": args.mode, "repetitions": args.repeat, "pause_seconds": args.pause_seconds,
              "chosen_row": args.choose, "editor_before_choice": empty_before_choice,
              "choose_wait_seconds": args.choose_wait_seconds if args.choose else None,
              "wave_frames": wave_frames,
              "after_keyboard_deletion": after, "package": "com.weavetext.ime"}
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print("PASS: microphone -> public IME -> editor text -> delete to empty", flush=True)


if __name__ == "__main__":
    main()
