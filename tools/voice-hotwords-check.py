#!/usr/bin/env python3
"""Check two bilingual recordings and changed hotwords against an installed release APK.
Use a dedicated test device; build/install :voice-smoke:assembleRelease with the same release key.
"""
import argparse,pathlib,re,shlex,subprocess
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('serial');p.add_argument('model',type=pathlib.Path)
p.add_argument('--adb',default='adb');p.add_argument('--runtime',type=pathlib.Path)
a=p.parse_args();adb=[a.adb,'-s',a.serial]
def run(*args):
    result=subprocess.run(adb+list(args),capture_output=True,text=True,check=True)
    return result.stdout
def instrument(**options):
    args=['shell','am','instrument','-w','-r']
    for key,value in options.items():args+=['-e',key,shlex.quote(str(value))]
    output=run(*args,'com.weavetext.ime.voicesmoke/.VoiceSmoke')
    print(output.strip(),flush=True)
    if 'FAIL:' in output or 'INSTRUMENTATION_CODE: -1' not in output:raise RuntimeError('Release hotword check failed')
    return output
root=re.search(r'PASS prepare: (\S+)',instrument(case='prepare')).group(1)+'/personal-vocabulary'
run('shell','mkdir','-p',root)
for name in ['encoder-epoch-99-avg-1.int8.onnx','decoder-epoch-99-avg-1.onnx','joiner-epoch-99-avg-1.int8.onnx','tokens.txt']:
    run('push',str(a.model/name),root+'/'+name)
run('push',str(a.model/'test_wavs/4.wav'),root+'/mixed.wav')
options={'case':'personal-hotwords','model':root,'wav':root+'/mixed.wav'}
if a.runtime:
    run('shell','mkdir','-p',root+'/runtime')
    for name in ['libsherpa-onnx-c-api.so','libonnxruntime.so']:run('push',str(a.runtime/name),root+'/runtime/'+name)
    options.update(backend='native',runtime=root+'/runtime')
instrument(**options)
print('PASS: two recordings preserve Chinese and English, updated vocabulary reuses one recognizer')
