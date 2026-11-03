"""
Display-SPI (display controller SPI connection: SPI plus D/C#, RES#, BUSY) sigrok protocol decoder.

Frames the SPI stream of a display controller (OLED, e-paper, ...) and reports its side-band
lines. It is the transport-level decoder that chip decoders (`uc8276c`, ...) stack
on, mirroring the `DisplayConnection` of `specs/transport_display.md`. The decoder
takes `logic` input and samples the SPI itself (rather than stacking on the
built-in `spi` decoder) because it needs the D/C#, BUSY and RES# lines, and
libsigrokdecode does not support declaring extra logic channels on a decoder
stacked on a non-`logic` input.

Channels: SCK, DIN, CS# (required); D/C# (required in 4-wire mode), DOUT, BUSY,
RES# (optional). SPI Mode 0, MSB first, bits sampled on SCK rising edges while
CS# is low. Option:
  - `wire`: `4-wire` (8 bits + D/C# pin, default) or `3-wire` (9-bit frames, the
    first bit is D/C)
The BUSY polarity is a property of the chip, not of the connection: this decoder
reports the raw level and each chip decoder decides which level means busy.

Wiring: with write-only (simplex) boards DIN is the host's MOSI. With half-duplex
wiring the chip drives the same line during a read, so probe DIN at the shared
DIN/DOUT line; if MISO is wired through a resistor and probed separately, connect
it to the optional DOUT channel and each byte carries both values.

Annotations:
  - Data:     one annotation per CS# window listing its bytes (`C xx` command,
              `D xx` data; with DOUT wired a value that differs from the line's
              idle level at CS# assertion is shown as `D xx <- yy`) and the
              fastest SCK rate seen (`CS# window: 3 byte(s): C 12 D 00 D FF @ 4.00 MHz`)
  - Bytes:    every frame as `C xx` (command) or `D xx` (data); with DOUT wired
              a value that differs from the line's idle level is shown as
              `D xx <- yy`
  - Timing:   polarity-neutral edge markers: `busy_fall` / `busy_rise` (with the
              duration of the previous level) and `res_fall` / `res_rise`
              (with the time RES# was low). Which BUSY edge means busy is the
              chip decoder's knowledge
  - Warnings: CS# released after a partial frame (`BITS?`), D/C# changing inside a
              byte (`DC?`)

Outputs OUTPUT_PYTHON packets `(kind, value)` for stacked chip decoders:
  ('BYTE', (dc, mosi_byte, miso_byte_or_None))   dc: 0 = command, 1 = data
  ('CS',   asserted)                              True on CS# falling, False on rising
  ('BUSY', level)                                 raw pin level 0/1, sent at the start and
                                                  on every change
  ('RESET', asserted)                             True while RES# is low
  ('IDLE', None)                                  CS# high and no clock for 10 ms; lets a
                                                  chip decoder report a pending open-ended
                                                  command (no end-of-capture hook exists)
"""

from .pd import Decoder
