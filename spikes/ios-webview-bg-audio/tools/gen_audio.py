"""Generate the spike's WAV fixtures: two audible tones and a silence loop."""
import math
import os
import struct
import sys
import wave

outdir = sys.argv[1]
os.makedirs(outdir, exist_ok=True)


def tone(path, freq, secs, amp=0.2, rate=22050):
    w = wave.open(path, "wb")
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(rate)
    n = int(secs * rate)
    frames = b"".join(
        struct.pack("<h", int(amp * 32767 * math.sin(2 * math.pi * freq * i / rate)))
        for i in range(n)
    )
    w.writeframes(frames)
    w.close()


tone(os.path.join(outdir, "track1.wav"), 440, 20)
tone(os.path.join(outdir, "track2.wav"), 660, 120)
tone(os.path.join(outdir, "silence.wav"), 440, 2, amp=0.0)
print("audio fixtures written to", outdir)
