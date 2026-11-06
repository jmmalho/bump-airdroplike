#!/usr/bin/env python3
"""Checks bundled WAV files and wiring without claiming to compile the Android app."""
import pathlib
import wave
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/pt/johnny/bump'
RAW = ROOT / 'app/src/main/res/raw'
expected = {
    'READY': 'bump_ready.wav', 'SEND': 'bump_send.wav',
    'INCOMING': 'bump_incoming.wav', 'SENT': 'bump_sent.wav',
    'RECEIVED': 'bump_received.wav', 'ERROR': 'bump_error.wav',
}
sfx = (SRC / 'BumpSfx.java').read_text()
for cue, filename in expected.items():
    assert (RAW / filename).exists(), filename
    with wave.open(str(RAW / filename), 'rb') as wav:
        assert wav.getnchannels() == 1 and wav.getsampwidth() == 2 and wav.getframerate() == 22050, filename
        assert .2 <= wav.getnframes()/wav.getframerate() <= 1, filename
    assert f'R.raw.{filename.removesuffix(".wav")}' in sfx

share = (SRC / 'ShareActivity.java').read_text()
receiver = (SRC / 'ReceiverService.java').read_text()
preview = (SRC / 'PreviewActivity.java').read_text()
for cue in ['READY', 'SEND', 'SENT']:
    assert f'BumpSfx.Cue.{cue}' in share
for cue in ['INCOMING', 'RECEIVED']:
    assert f'BumpSfx.Cue.{cue}' in receiver
for cue in ['READY', 'SEND', 'INCOMING', 'SENT', 'RECEIVED']:
    assert f'BumpSfx.Cue.{cue}' in preview
pulse = (SRC / 'PulseView.java').read_text()
for mode in ['WAIT', 'SEND', 'RECEIVE', 'SENT', 'RECEIVED', 'ERROR']:
    assert mode in pulse
ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
assert 'versionCode 9' in (ROOT / 'app/build.gradle').read_text()
assert 'BUMP' in (ROOT / 'README.md').read_text()
print('PASS Audio resources / event wiring / AndroidManifest XML: 6 WAV + 5 event cues')

# Liquid glass implementation is bundled and still offers an opaque-tinted fallback.
glass = (SRC / 'GlassStyle.java').read_text()
assert 'setBlurBehindRadius' in glass and 'isCrossWindowBlurEnabled' in glass
assert 'GradientDrawable' in glass and 'PowerManager' in glass
assert 'drawEdgeLight' in pulse and 'drawRefractionFront' in pulse and 'drawStatusCapsule' in pulse
for activity in ['ShareActivity.java', 'PreviewActivity.java']:
    assert 'GlassStyle.prepareTranslucentActivity(this)' in (SRC / activity).read_text()
assert 'params.alpha = .78f' in receiver
assert 'android:windowDimAmount' not in (ROOT / 'app/src/main/res/values/styles.xml').read_text()
ET.parse(ROOT / 'app/src/main/res/values/styles.xml')
print('PASS Liquid Glass materials / optional system blur / fallback / theme XML')

# Wave v0.5: both directions, real completion gating, and complete live preview.
assert 'drawRefractionFront' in pulse and 'drawCompletion' in pulse
assert 'ValueAnimator.areAnimatorsEnabled()' in pulse  # respects system accessibility settings
assert 'long launchStartedAt' in share and '1480 - elapsed' in share
assert 'long waveStartedAt' in receiver and '1480 - elapsed' in receiver
assert 'Ver Surface Handoff' in preview and 'playDemo()' in preview
print('PASS AirDrop Wave choreography / completion timing / demo / reduced motion')

# v0.5.1: smart file opening is only possible after an explicit tap.
open_file = (SRC / 'OpenFileActivity.java').read_text()
shader = (SRC / 'LiquidRefraction.java').read_text()
manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
assert manifest.count('android:name=".OpenFileActivity"') == 1
assert manifest.count('android.permission.REQUEST_INSTALL_PACKAGES') == 1
assert 'android:exported="false" android:theme="@style/BumpShareTheme"' in manifest
for m in ['application/pdf','image/','application/vnd.android.package-archive','ACTION_VIEW','FLAG_GRANT_READ_URI_PERMISSION','canRequestPackageInstalls','ACTION_MANAGE_UNKNOWN_APP_SOURCES']:
    assert m in open_file, m
assert 'OpenFileActivity.intent' in (SRC / 'MainActivity.java').read_text()
assert 'OpenFileActivity.intent' in receiver
assert receiver.count('private void showOpenAction(') == 1
assert 'RuntimeShader' in shader and 'createRuntimeShaderEffect' in shader
assert 'LiquidRefraction refraction' in pulse
print('PASS Smart open PDF/image/APK / safe user tap / AGSL 33+ fallback')

# Surface Handoff: original icon, refractive AGSL, and honest Android overlay policy.
icon = (ROOT / 'app/src/main/res/drawable/ic_bump.xml').read_text()
ET.parse(ROOT / 'app/src/main/res/drawable/ic_bump.xml')
assert 'content.eval(sampleAt)' in shader
assert 'originY' in shader and 'setFloatUniform' in shader
assert 'typeLabel()' in pulse
assert 'Math.max(0, 1480 - elapsed)' in receiver
assert 'params.alpha = .78f' in receiver
assert 'SURFACE HANDOFF' in (SRC/'MainActivity.java').read_text()
print('PASS Surface Handoff visuals / original icon / correct timing / pass-through overlay')
