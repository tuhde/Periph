"""
HT16K33 16x8 LED matrix driver with key scan — sigrok protocol decoder.

Stacks on the i2c decoder (addresses 0x70-0x77). The first byte of each write
is a command / address byte; the decoder annotates:
  - display data address pointer (0x00-0x0F) with the following RAM bytes as
    per-COM ROW bitmaps
  - system setup (oscillator on / standby), display setup (display on/off,
    blink frequency), ROW/INT set (ROW15 or INT output, polarity), dimming set
  - key data (0x40) and INT flag (0x60) pointers and the reads that follow
    (pressed-key list, INT flag)
Warnings: test-mode command 0xD9, commands other than system setup after a
standby command, display data running past RAM address 0x0F.
"""

from .pd import Decoder
