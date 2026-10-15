#!/usr/bin/env python3
"""Generate a synthetic UC8276C logic capture for the `uc8276c` sigrok decoder.

Writes a raw binary file, one byte per sample (bit0 = clk, bit1 = sda, bit2 = cs,
bit3 = dc, bit4 = busy_n, bit5 = res_n), 4-wire SPI Mode 0, one CS# window per
byte for single-byte commands/parameters and one window for streamed pixel data.
It covers write-only traffic and half-duplex reads (SDA driven by the chip).

Usage:
    make_capture.py OUT.bin [--wire 4|3] [--dout] [--race]

--race asserts BUSY on the same sample as the last SCK rising edge of the DRF command byte, as a
chip that starts the operation on the final clock does (regression: the decoder must not report
"command sent while BUSY_N is low" for the command that caused the busy).

--dout puts the chip's read data on a separate 7th channel (bit6 = dout, MISO wired
through a resistor) and leaves SDA low during reads; without it the chip drives SDA
(shared DIN/DOUT probe).

Decode with the connection decoder and the chip decoder stacked on it:
    SIGROKDECODE_DIR=sigrok sigrok-cli -I binary:numchannels=6:samplerate=1000000 \\
        -i OUT.bin -P display_spi:clk=0:mosi=1:cs=2:dc=3:busy=4:res=5,uc8276c -A uc8276c
(3-wire: omit `dc=3` and add `:wire=3-wire`; --dout: numchannels=7 and add `:miso=6`.)
"""
import sys

CLK, SDA, CS, DC, BUSY, RES, DOUT = 0, 1, 2, 3, 4, 5, 6
HALF = 10                       # samples per half SCL period (1 MHz sample rate -> 50 kHz SCL)


class Cap:
    def __init__(self, wire3=False, dout=False, busy_active=0):
        self.wire3 = wire3
        self.dout = dout
        self.busy_active = busy_active           # BUSY level that means busy (0: BUSY_N, 1: SSD1683)
        self.s = bytearray()
        self.pins = {CLK: 0, SDA: 0, CS: 1, DC: 1, BUSY: 1 - busy_active, RES: 1, DOUT: 0}

    def _emit(self, n=1):
        v = sum(b << p for p, b in self.pins.items())
        self.s.extend(bytes([v]) * n)

    def idle(self, n):
        self._emit(n)

    def set(self, pin, level, n=1):
        self.pins[pin] = level
        self._emit(n)

    def _bits(self, bits, chip=False):
        for b in bits:
            if chip and self.dout:
                self.pins[SDA] = 0               # host released the line
                self.pins[DOUT] = b
            else:
                self.pins[SDA] = b               # host-driven, or chip on the shared line
            self.pins[CLK] = 0
            self._emit(HALF)
            self.pins[CLK] = 1
            self._emit(HALF)
        self.pins[CLK] = 0
        if chip and self.dout:
            self.pins[DOUT] = 0                  # the chip releases DOUT after the byte
        self._emit(HALF)

    def _frame(self, dc, value, chip=False):
        bits = [(value >> i) & 1 for i in range(7, -1, -1)]
        if self.wire3:
            self._bits([dc])                     # D/C bit is always host-driven
            self._bits(bits, chip)
        else:
            self.pins[DC] = dc
            self._emit(HALF)
            self._bits(bits, chip)

    def window(self, items, busy=None):
        """One CS# low window carrying [(dc, value), ...].

        busy: None leaves BUSY alone; an int asserts BUSY at the same sample as the CS# rising
        edge (as a chip does for a command that needs it), holds it that many samples, then
        releases it; 'hold' asserts it and leaves it asserted.
        """
        self.pins[CS] = 0
        self._emit(HALF)
        for it in items:
            self._frame(it[0], it[1], len(it) > 2 and it[2])
        self.pins[CS] = 1
        if busy is not None:
            self.pins[BUSY] = self.busy_active   # same sample as the CS# rising edge
        self._emit(HALF * 2)
        if isinstance(busy, int):
            self._emit(busy)
            self.pins[BUSY] = 1 - self.busy_active
            self._emit(10)

    def cmd(self, c, params=(), split=True, busy=None):
        """Command (+ parameters); `busy` applies to the CS# window that completes the command."""
        if split:
            self.window([(0, c)], busy if not params else None)
            for i, p in enumerate(params):
                self.window([(1, p)], busy if i == len(params) - 1 else None)
        else:
            self.window([(0, c)] + [(1, p) for p in params], busy)

    def release_busy(self, n=1):
        self.set(BUSY, 1 - self.busy_active, n)

    def cmd_busy_on_last_clock(self, c, busy):
        """A single-byte command whose BUSY assertion coincides with the last SCK rising edge."""
        self.pins[CS] = 0
        self._emit(HALF)
        self.pins[DC] = 0
        self._emit(HALF)
        bits = [(c >> i) & 1 for i in range(7, -1, -1)]
        for i, b in enumerate(bits):
            self.pins[SDA] = b
            self.pins[CLK] = 0
            self._emit(HALF)
            self.pins[CLK] = 1
            if i == 7:
                self.pins[BUSY] = self.busy_active
            self._emit(HALF)
        self.pins[CLK] = 0
        self._emit(HALF)
        self.pins[CS] = 1
        self._emit(HALF * 2)
        self._emit(busy)
        self.pins[BUSY] = 1 - self.busy_active
        self._emit(10)

    def rd(self, v):
        """One CS# window carrying a byte driven by the chip (read response)."""
        self.window([(1, v, True)])


def main(out, wire3, dout, race=False):
    c = Cap(wire3, dout)
    c.idle(50)
    c.set(RES, 0, 100)                               # hardware reset
    c.set(RES, 1, 1500)
    c.cmd(0x00, [0x0F])                              # PSR
    c.cmd(0x30, [0x3C])                              # PLL: M=7 N=4
    c.cmd(0x61, [0x01, 0x90, 0x01, 0x2C])            # TRES 400x300
    c.cmd(0x50, [0xD7])                              # CDI
    c.cmd(0x10, [0xFF] * 8, split=False)             # DTM1, streamed under one CS
    c.cmd(0x13, [0x00] * 8, split=False)             # DTM2
    c.cmd(0x04, busy=3000)                           # PON: BUSY_N low from the CS# rising edge
    if race:
        c.idle(200)                                  # host waited: BUSY_N high when DRF starts
        c.cmd_busy_on_last_clock(0x12, 3000)         # DRF: BUSY_N low on the last SCK rising edge
    else:
        c.cmd(0x12, busy='hold')                     # DRF: BUSY_N low from the CS# rising edge ...
        c.idle(100)
        c.cmd(0x71)                                  # ... FLG after only ~100 samples -> warning
        c.idle(20000)
        c.release_busy(50)
    c.cmd(0x02, busy=500)                            # POF
    # half-duplex reads: SDA carries the chip's output after the command byte
    c.window([(0, 0x71)])
    c.rd(0x13)                                       # FLG = 0x13 (idle, POF)
    c.window([(0, 0x40)])
    c.rd(0x19)                                       # TSC: +25 C
    c.rd(0x00)
    c.window([(0, 0x70)])
    for v in (0xFF, 0x07, 0xFF, 0xFF, 0xFF):         # REV: reserved, chip rev 7, LUT 0xFFFFFF
        c.rd(v)
    c.window([(0, 0x51)])
    c.rd(0x01)                                       # LPD normal
    c.window([(0, 0x81)])
    c.rd(0x1C)                                       # VV
    c.cmd(0x17, [0xA5], busy=1000)                   # AUTO (chip datasheet only)
    c.cmd(0x99)                                      # unknown command
    c.cmd(0x07, [0xA5])                              # deep sleep
    c.set(RES, 0, 100)                               # wake: hardware reset
    c.set(RES, 1, 1500)
    c.window([(0, 0xA2)])                            # ROTP: open-ended read, ends the capture
    for v in (0x00, 0xA5, 0x12, 0x34):               # dummy, then OTP bytes 0x000..0x002
        c.rd(v)
    c.idle(15000)                                    # > 10 ms: the decoder flushes the open-ended read
    with open(out, 'wb') as f:
        f.write(bytes(c.s))


if __name__ == '__main__':
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args:
        sys.exit(__doc__)
    main(args[0], '--wire=3' in sys.argv or ('--wire' in sys.argv and sys.argv[sys.argv.index('--wire') + 1] == '3'),
         '--dout' in sys.argv, '--race' in sys.argv)
