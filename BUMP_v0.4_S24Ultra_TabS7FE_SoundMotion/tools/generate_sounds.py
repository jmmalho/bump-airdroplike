#!/usr/bin/env python3
"""Synthesize original, quiet 22.05 kHz mono BUMP UI sound cues. No audio samples or network.
Requires numpy only when regenerating; .wav files are shipped in res/raw.
"""
from pathlib import Path
import wave
import numpy as np

RATE = 22050
TARGET = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'
TARGET.mkdir(parents=True, exist_ok=True)


def line_env(t, duration, attack=.015, release=.13):
    return np.minimum(1., np.maximum(0., t / attack)) * np.minimum(1., np.maximum(0., (duration-t) / release))


def bell(t, start, freq, length, gain=.2, harmonics=(1, 2.01, 3.98)):
    dt = t-start
    s = np.zeros_like(t)
    valid = (dt >= 0) & (dt < length)
    x = dt[valid]
    env = (1 - np.exp(-x / .007)) * np.exp(-x/(length*.36)) * np.maximum(0, 1-x/length)**1.4
    wave = (np.sin(2*np.pi*freq*x) + .21*np.sin(2*np.pi*freq*harmonics[1]*x) + .065*np.sin(2*np.pi*freq*harmonics[2]*x))
    s[valid] = gain * env * wave
    return s


def airy_whoosh(t, duration, ascending, seed):
    rng = np.random.default_rng(seed)
    noise = rng.normal(0, 1, len(t))
    # Smoothed noise for a non-hissy airy sound rather than white-noise static.
    smooth = np.convolve(noise, np.ones(29)/29, mode='same')
    peak = np.sin(np.pi*np.clip(t/duration,0,1))**1.65
    if ascending:
        freq = 300 + 1100 * (t/duration)**1.3
    else:
        freq = 1020 - 720 * (t/duration)**.85
    phase = 2*np.pi*np.cumsum(freq)/RATE
    return (smooth*.18 + np.sin(phase)*.045) * peak * line_env(t,duration,.035,.095)


def write(name, duration, y):
    n = int(duration*RATE)
    if len(y) != n:
        raise ValueError((name, len(y), n))
    y = y * line_env(np.arange(n)/RATE, duration, attack=.010, release=.055)
    y = np.tanh(1.4*y) / 1.4
    peak = np.max(np.abs(y))
    if peak > .35:
        y *= .35/peak
    with wave.open(str(TARGET / name),'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes((np.clip(y,-1,1)*32767).astype('<i2').tobytes())
    print(name, round(duration,2), 's', 'peak', round(float(np.max(np.abs(y))),3))

# A delicate two-tone awakening; starts as the share interface opens.
d = .53; t=np.arange(int(d*RATE))/RATE
write('bump_ready.wav',d,bell(t,.0,587.33,.36,.22)+bell(t,.092,880,.43,.15)+bell(t,.018,1174.66,.18,.045))

# Bright, upward crossing as the file leaves the sending screen.
d=.68; t=np.arange(int(d*RATE))/RATE
write('bump_send.wav',d,airy_whoosh(t,d,True,41)+bell(t,.34,987.77,.30,.11))

# An arrival glissando, spatially descending into the receiving screen.
d=.72; t=np.arange(int(d*RATE))/RATE
write('bump_incoming.wav',d,airy_whoosh(t,d,False,57)+bell(t,.26,659.25,.42,.105))

# An understated transmitted acknowledgment, NOT the same cue as receive.
d=.48; t=np.arange(int(d*RATE))/RATE
write('bump_sent.wav',d,bell(t,0,783.99,.34,.19)+bell(t,.11,987.77,.34,.15))

# A round 3-note received signature, after the file was saved successfully.
d=.74; t=np.arange(int(d*RATE))/RATE
write('bump_received.wav',d,bell(t,0,587.33,.43,.14)+bell(t,.12,739.99,.43,.15)+bell(t,.245,987.77,.47,.19))

# Low, unobtrusive error acknowledgement.
d=.31; t=np.arange(int(d*RATE))/RATE
write('bump_error.wav',d,bell(t,0,392,.25,.12)+bell(t,.082,349.23,.23,.095))
