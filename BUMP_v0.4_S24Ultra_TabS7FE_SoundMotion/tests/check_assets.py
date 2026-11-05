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
assert 'versionCode 7' in (ROOT / 'app/build.gradle').read_text()
assert 'BUMP' in (ROOT / 'README.md').read_text()
print('PASS Audio resources / event wiring / AndroidManifest XML: 6 WAV + 5 event cues')

# Liquid glass implementation is bundled and still offers an opaque-tinted fallback.
glass = (SRC / 'GlassStyle.java').read_text()
assert 'setBlurBehindRadius' in glass and 'isCrossWindowBlurEnabled' in glass
assert 'GradientDrawable' in glass and 'PowerManager' in glass
assert 'drawGlass' in pulse and 'drawBreathingRings' in pulse and 'drawHandoff' in pulse
for activity in ['ShareActivity.java', 'PreviewActivity.java']:
    assert 'GlassStyle.prepareTranslucentActivity(this)' in (SRC / activity).read_text()
assert 'params.alpha = .78f' in receiver
assert 'android:windowDimAmount' not in (ROOT / 'app/src/main/res/values/styles.xml').read_text()
ET.parse(ROOT / 'app/src/main/res/values/styles.xml')
print('PASS Liquid Glass materials / optional system blur / fallback / theme XML')

# Wave v0.5: both directions, real completion gating, and complete live preview.
assert 'drawWaveLine' in pulse and 'drawConfirmation' in pulse
assert 'ValueAnimator.areAnimatorsEnabled()' in pulse  # respects system accessibility settings
assert 'long launchStartedAt' in share and '980 - elapsed' in share
assert 'long waveStartedAt' in receiver and '960 - elapsed' in receiver
assert 'Ver sequência AirDrop Wave' in preview and 'playDemo()' in preview
print('PASS AirDrop Wave choreography / completion timing / demo / reduced motion')
