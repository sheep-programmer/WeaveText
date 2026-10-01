#!/usr/bin/env python3
"""Recognize real sample WAVs using an installed minified APK.

Build/install :voice-smoke:assembleRelease with the target app's signing key first.
Usage: python3 tools/voice-smoke.py SERIAL MODEL_ROOT [--native --runtime DIR]
MODEL_ROOT contains the model directories from the pinned sherpa model archives.
"""
import argparse
from pathlib import Path
import re
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("models", type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--native", action="store_true", help="Test the lite APK's downloaded C runtime")
    parser.add_argument("--runtime", type=Path)
    parser.add_argument("--repeat", type=int, default=1, help="Reopen each model this many times")
    parser.add_argument("--only", action="append", choices=["stream-small", "stream-large", "final-small", "sense-voice", "paraformer", "punctuation", "dolphin-base", "dolphin-small", "telespeech", "vad"])
    args = parser.parse_args()
    if args.repeat < 1:
        parser.error("--repeat must be positive")
    adb = [args.adb, "-s", args.serial]

    def command(*words):
        result = subprocess.run(adb + list(words), text=True, capture_output=True)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return result.stdout

    def run(**options):
        words = ["shell", "am", "instrument", "-w", "-r"]
        for name, value in options.items():
            words.extend(["-e", name, shlex.quote(str(value))])
        words.append("com.weavetext.ime.voicesmoke/.VoiceSmoke")
        output = command(*words)
        print(output.strip(), flush=True)
        if "INSTRUMENTATION_CODE: -1" not in output or "FAIL:" in output:
            raise SystemExit("Release voice smoke test failed")
        return output

    prepared = run(case="prepare")
    match = re.search(r"PASS prepare: (\S+)", prepared)
    if match is None:
        raise SystemExit("Instrumentation did not return its test directory")
    root = match.group(1)
    common = {}
    if args.native:
        if args.runtime is None or not args.runtime.is_dir():
            raise SystemExit("--native requires --runtime with libonnxruntime.so and libsherpa-onnx-c-api.so")
        remote = root + "/runtime"
        command("shell", "mkdir", "-p", remote)
        for lib in ("libonnxruntime.so", "libsherpa-onnx-c-api.so"):
            command("push", str(args.runtime / lib), remote + "/" + lib)
        common = {"backend": "native", "runtime": remote}

    cases = [
        ("online-file", "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01", "zipformer2-ctc", "0.wav"),
        ("online-file", "sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30", "zipformer2-ctc", "0.wav"),
        ("offline", "sherpa-onnx-zipformer-ctc-small-zh-int8-2025-07-16", "zipformer-ctc", "0.wav"),
        ("offline", "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09", "sense-voice", "zh.wav"),
        ("offline", "sherpa-onnx-paraformer-zh-int8-2025-10-07", "paraformer", "0.wav"),
        ("punctuation", "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8", "ct-transformer", None),
        ("offline", "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02", "dolphin", "0.wav"),
        ("offline", "sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02", "dolphin", "0.wav"),
        ("offline", "sherpa-onnx-telespeech-ctc-int8-zh-2024-06-04", "telespeech-ctc", "3-sichuan.wav"),
        ("vad", "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01", "silero-vad", "0.wav"),
    ]
    names = ["stream-small", "stream-large", "final-small", "sense-voice", "paraformer", "punctuation", "dolphin-base", "dolphin-small", "telespeech", "vad"]
    passed = 0
    for index, (kind, name, arch, wav) in enumerate(cases):
        if args.only and names[index] not in args.only:
            continue
        local = args.models / name
        if not local.is_dir():
            raise SystemExit("Missing test model directory: " + str(local))
        remote = root + "/case-" + str(index)
        command("shell", "mkdir", "-p", remote)
        if kind == "vad":
            command("push", str(args.models.parent / "cache" / "silero_vad_v5.onnx"), remote + "/silero_vad_v5.onnx")
        elif kind != "online-bundled":
            command("push", str(local / "model.int8.onnx"), remote + "/model.int8.onnx")
            if kind != "punctuation":
                command("push", str(local / "tokens.txt"), remote + "/tokens.txt")
        options = {"case": kind, "model": remote, "arch": arch, **common}
        if wav:
            sample = local / "test_wavs" / wav
            if index in (6, 7, 8):
                sample = args.models / cases[0][1] / "test_wavs" / "0.wav"
            if not sample.is_file():
                # Some Paraformer archives number their samples from 1.
                sample = local / "test_wavs" / "1.wav"
            command("push", str(sample), remote + "/sample.wav")
            options["wav"] = remote + "/sample.wav"
        print("Testing " + name, flush=True)
        # Nonempty gibberish is insufficient: the sample's words must survive decoding.
        options["expect"] = "对我做了介绍" if index < 3 else (["九点", "唱首歌", "你好，"][index - 3] if index < 6 else ("研究" if index < 9 else "2 speech segments"))
        for iteration in range(args.repeat):
            print(f"  Recognition {iteration + 1}/{args.repeat}", flush=True)
            run(**options)
            passed += 1
        command("shell", "rm", "-rf", remote)
    print(f"PASS: {passed} release speech cases", flush=True)


if __name__ == "__main__":
    main()
