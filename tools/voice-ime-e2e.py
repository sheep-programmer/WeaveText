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
    args = parser.parse_args()
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
        data = bytes(9600) + pcm + bytes(32000)
        start = time.monotonic()
        for offset in range(0, len(data), 640):
            delay = start + offset / 32000 - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            yield pb.AudioPacket(format=fmt, audio=data[offset:offset + 640])

    if editor_text():
        raise AssertionError("Start with an empty editor to prove text came from speech")
    with grpc.insecure_channel(endpoint) as channel:
        stub = rpc.EmulatorControllerStub(channel)
        # Use injected audio only, without recording the host's microphone.
        stub.setMicrophoneState(pb.MicrophoneState(realAudioEnabled=False), metadata=metadata, timeout=5)
        shell("input", "motionevent", "DOWN", *args.mic)
        try:
            time.sleep(1.2)
            pid = shell("pidof", "com.weavetext.ime").strip().split()[0]
            capture = shell("dumpsys", "media.audio_flinger")
            if not re.search(r"^\s*yes\s+\d+\s+" + pid + r"(?:/|\s)", capture, re.M):
                raise AssertionError("The actual IME's AudioRecord did not start")
            stub.injectAudio(packets(), metadata=metadata, timeout=25)
        finally:
            shell("input", "motionevent", "UP", *args.mic)
    deadline = time.monotonic() + 15
    while True:
        text = editor_text()
        if args.expect in text:
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
              "after_keyboard_deletion": after, "package": "com.weavetext.ime"}
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print("PASS: microphone -> public IME -> editor text -> delete to empty", flush=True)


if __name__ == "__main__":
    main()
