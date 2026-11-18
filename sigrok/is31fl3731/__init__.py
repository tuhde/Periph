"""
IS31FL3731 144-LED matrix driver sigrok protocol decoder.

Stacks on the i2c decoder (addresses 0x74-0x77). Tracks the page selected by writes to the command
register 0xFD (frames 1-8 or the function page 0x0B) and annotates every register access with the
page and register name: per-LED PWM runs, LED-control and blink bytes in the frame pages, and the
decoded CONFIG, PICTURE, AUTOPLAY1/2, DISPLAY, AUDIO_SYNC, FRAME_STATE, BREATH1/2, SHUTDOWN, AGC
and AUDIO_RATE fields (modes, frames, delays in ms, times in ms/s, gains in dB) on the function
page. Warns on writes to the reserved register 0x04 and to read-only FRAME_STATE, unknown page
numbers and frame-register accesses while the function page is selected. The decoded register
accesses are also mirrored to the Python output. No timing annotation pair is emitted (no checked
timing constraint).
"""

from .pd import Decoder
