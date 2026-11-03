"""
HY3131 sigrok protocol decoder.

Decodes HYCON HY3131 50,000-count DMM analog front end SPI transactions. The
chip's framing is non-standard: the first byte is `(addr << 1) | R/W`
(R/W = 1 read), reads insert one blank clock after the command byte (so the
MISO stream is shifted left by one bit), and addresses auto-increment while CS
stays low. This decoder stacks on `spi` and consumes whole CS frames
(`TRANSFER`), so CS, MOSI and MISO are all required.

Annotations:
- `cmd` — command byte: register name, address, R/W direction.
- `reg` — each register touched by the frame, decoded to named fields
  (INTF/INTE flags, R22 OSR/chop, R23 enables/buffers, R24/R28/R27 muxes,
  R25 gains, R26/R29 AD2/LPF/RMS/peak, R31/R33/R20/R37 ...).
- `value` — multi-byte results: AD1 (24-bit), AD2/LPF/peak (19-bit),
  RMS (38-bit), counters A/B/C (24-bit), as signed codes.
- `spi_cs_start` / `spi_cs_end` — `timing` row: one pair per CS frame, used by
  the conformance checker for the SPI pulse-width constraints.
- `warning` — write to R37 other than 0x00/0x60, unknown address, a read frame
  whose last data byte is clocked short (only 7 valid bits).
"""

import sigrokdecode as srd

REG_NAMES = {
    0x00: 'AD1', 0x03: 'AD2', 0x06: 'LPF', 0x09: 'RMS', 0x0E: 'PKHMIN', 0x11: 'PKHMAX',
    0x14: 'CTSTA', 0x15: 'CTC', 0x18: 'CTB', 0x1B: 'CTA', 0x1E: 'INTF', 0x1F: 'INTE',
    0x20: 'R20', 0x21: 'R21', 0x22: 'R22', 0x23: 'R23', 0x24: 'R24', 0x25: 'R25',
    0x26: 'R26', 0x27: 'R27', 0x28: 'R28', 0x29: 'R29', 0x2A: 'R2A', 0x2B: 'R2B',
    0x2C: 'R2C', 0x2D: 'R2D', 0x2E: 'R2E', 0x2F: 'R2F', 0x30: 'R30', 0x31: 'R31',
    0x32: 'R32', 0x33: 'R33', 0x34: 'R34', 0x35: 'R35', 0x36: 'R36', 0x37: 'R37',
}

# (start address, byte count, bits, label) multi-byte values, little-endian, sign-extended
VALUES = [
    (0x00, 3, 24, 'AD1'), (0x03, 3, 19, 'AD2'), (0x06, 3, 19, 'LPF'),
    (0x09, 5, 38, 'RMS'), (0x0E, 3, 19, 'PKHMIN'), (0x11, 3, 19, 'PKHMAX'),
    (0x15, 3, 24, 'CTC'), (0x18, 3, 24, 'CTB'), (0x1B, 3, 24, 'CTA'),
]
SIGNED = {'AD1', 'AD2', 'LPF', 'RMS', 'PKHMIN', 'PKHMAX'}

INTF_BITS = ((7, 'BORF'), (4, 'RMSF'), (3, 'LPFF'), (2, 'AD1F'), (1, 'AD2F'), (0, 'CTF'))
INTE_BITS = ((4, 'RMSIE'), (3, 'LPFIE'), (2, 'AD1IE'), (1, 'AD2IE'), (0, 'CTIE'))
AD1_GAIN = (0.9, 1.8, 2.7, 3.6)
AD2_GAIN = (0.5, 1.0, 1.5, 2.0)
OSR1 = (256, 512, 1024, 2048, 4096, 8192, 16384, 32768)
OSR2 = (32, 64, 128, 256, 512, 1024, 1024, 1024)
OSR4 = (128, 256, 512, 1024, 2048, 4096, 8192, 16384)
DCOS = (0, 0.25, 0.5, 0.75, 0, -0.25, -0.5, -0.75)
PKHSEL = ('AD2', 'AD1<23:5>', 'LPF', 'LPF')

ANN_CMD = 0
ANN_REG = 1
ANN_VALUE = 2
ANN_WARN = 3
ANN_CS_START = 4
ANN_CS_END = 5


def _sext(value, bits):
    sign = 1 << (bits - 1)
    return value - (sign << 1) if value & sign else value


def _flags(byte, table):
    names = [name for bit, name in table if byte & (1 << bit)]
    return '+'.join(names) if names else 'none'


def _describe(addr, v):
    """Return (long, medium, short) strings for a single register byte."""
    n = REG_NAMES.get(addr, '0x%02X' % addr)
    if addr == 0x1E:
        return ('INTF: %s' % _flags(v, INTF_BITS), 'INTF %s' % _flags(v, INTF_BITS), 'F=%02X' % v)
    if addr == 0x1F:
        return ('INTE: %s' % _flags(v, INTE_BITS), 'INTE %s' % _flags(v, INTE_BITS), 'E=%02X' % v)
    if addr == 0x22:
        s = 'R22: DCOS=%+g chop=%d OSR1=%d' % (DCOS[v >> 5], (v >> 3) & 3, OSR1[v & 7])
        return (s, 'R22 OSR1=%d' % OSR1[v & 7], 'R22=%02X' % v)
    if addr == 0x23:
        s = 'R23: AD1 %s RG=%s bufRH=%d RL=%d IP=%d IN=%d' % (
            'on' if v & 0x80 else 'off', '0.333' if v & 0x10 else '1.0',
            (v >> 3) & 1, (v >> 2) & 1, (v >> 1) & 1, v & 1)
        return (s, 'R23 AD1 %s' % ('on' if v & 0x80 else 'off'), 'R23=%02X' % v)
    if addr == 0x24:
        return ('R24: SAD1FP=%d SAD1FN=%d' % (v >> 4, v & 7), 'R24 FP=%d FN=%d' % (v >> 4, v & 7), 'R24=%02X' % v)
    if addr == 0x25:
        s = 'R25: AD2IG=%g AD1IG=%g SACM=%d' % (AD2_GAIN[v >> 6], AD1_GAIN[(v >> 4) & 3], (v >> 2) & 3)
        return (s, 'R25 IG1=%g' % AD1_GAIN[(v >> 4) & 3], 'R25=%02X' % v)
    if addr == 0x26:
        s = 'R26: AD2 %s chop=%d RG=%s clk=/%d OSR2=%d' % (
            'on' if v & 0x80 else 'off', (v >> 5) & 1, '0.333' if v & 0x10 else '1.0',
            4 if v & 0x08 else 2, OSR2[v & 7])
        return (s, 'R26 AD2 %s' % ('on' if v & 0x80 else 'off'), 'R26=%02X' % v)
    if addr == 0x27:
        return ('R27: SAD2IP=%d SAD2IN=%d SAD2RH=%d SAD2RL=%d' % (v >> 6, (v >> 4) & 3, (v >> 2) & 3, v & 3),
                'R27 mux', 'R27=%02X' % v)
    if addr == 0x28:
        return ('R28: SAD1RH=%d SAD1RL=%d' % ((v >> 4) & 7, v & 7), 'R28 ref', 'R28=%02X' % v)
    if addr == 0x29:
        s = 'R29: RMS=%d LPF=%d OSR4=%d PKH=%d src=%s' % (
            (v >> 7) & 1, (v >> 6) & 1, OSR4[(v >> 3) & 7], (v >> 2) & 1, PKHSEL[v & 3])
        return (s, 'R29 RMS=%d LPF=%d' % ((v >> 7) & 1, (v >> 6) & 1), 'R29=%02X' % v)
    if addr == 0x31:
        s = 'R31: REFO=%d BIAS=%d SAGND=%d SFUVR=%d' % ((v >> 7) & 1, (v >> 6) & 1, (v >> 4) & 3, v & 15)
        return (s, 'R31 REFO=%d BIAS=%d' % ((v >> 7) & 1, (v >> 6) & 1), 'R31=%02X' % v)
    if addr == 0x33:
        s = 'R33: OP1CHOP=%d OSC=%d XI=%d SFT1=%d SAD1I=%d' % (v >> 6, (v >> 5) & 1, (v >> 4) & 1, (v >> 2) & 3, v & 3)
        return (s, 'R33 OSC=%d SAD1I=%d' % ((v >> 5) & 1, v & 3), 'R33=%02X' % v)
    if addr == 0x34:
        return ('R34: AD3 %s SDO23=%d' % ('on' if v & 0x80 else 'off', (v >> 2) & 1), 'R34 AD3 %s' % ('on' if v & 0x80 else 'off'), 'R34=%02X' % v)
    if addr == 0x20:
        return ('R20: ENCMP=%d ENCNTI=%d ENPCMPO=%d ENCTR=%d' % ((v >> 4) & 1, (v >> 3) & 1, (v >> 2) & 1, (v >> 1) & 1),
                'R20 ENCTR=%d' % ((v >> 1) & 1), 'R20=%02X' % v)
    if addr == 0x14:
        return ('CTSTA: PCNTI=%d CTBOV=%d' % (v >> 7, v & 1), 'CTSTA CTBOV=%d' % (v & 1), 'CT=%02X' % v)
    if addr == 0x37:
        return ('R37: %s' % ('SOFTWARE RESET' if v == 0x60 else '0x%02X' % v),
                'R37 %s' % ('RESET' if v == 0x60 else '0x%02X' % v), 'RST' if v == 0x60 else 'R37')
    return ('%s = 0x%02X' % (n, v), '%s=0x%02X' % (n, v), '%02X' % v)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'hy3131'
    name = 'HY3131'
    longname = 'HYCON HY3131 50,000-count DMM analog front end'
    desc = 'Decode HY3131 register-access SPI transactions.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['hy3131']
    tags = ['IC', 'ADC']

    annotations = (
        ('cmd', 'Command byte'),
        ('reg', 'Register'),
        ('value', 'Multi-byte value'),
        ('warning', 'Warning'),
        ('spi_cs_start', 'CS frame start'),
        ('spi_cs_end', 'CS frame end'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_CMD, ANN_REG, ANN_VALUE)),
        ('timing', 'Timing', (ANN_CS_START, ANN_CS_END)),
        ('warnings', 'Warnings', (ANN_WARN,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        pass

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _ann(self, ss, es, cls, strings):
        self.put(ss, es, self.out_ann, [cls, list(strings)])

    def _warn(self, ss, es, msg, tag):
        self._ann(ss, es, ANN_WARN, [msg, tag])

    def decode(self, ss, es, data):
        if data[0] != 'TRANSFER':
            return
        mosi, miso = data[1], data[2]
        if not mosi:
            self._warn(ss, es, 'MOSI channel required to decode commands', 'MOSI?')
            return
        self._ann(ss, ss, ANN_CS_START, ['spi_cs_start: CS asserted, frame begins', 'spi_cs_start', 'CS▶'])
        self._ann(es, es, ANN_CS_END, ['spi_cs_end: CS released, frame ends', 'spi_cs_end', 'CS✓'])
        cmd = mosi[0].val
        addr = cmd >> 1
        read = bool(cmd & 1)
        name = REG_NAMES.get(addr, '0x%02X' % addr)
        self._ann(mosi[0].ss, mosi[0].es, ANN_CMD,
                  ['%s %s (0x%02X)' % ('Read' if read else 'Write', name, addr),
                   '%s %s' % ('RD' if read else 'WR', name), 'R' if read else 'W'])
        if addr > 0x37:
            self._warn(ss, es, 'Address 0x%02X is outside the register map (0x00-0x37)' % addr, 'ADDR?')

        if read:
            raw = [m.val for m in miso[1:]] if miso else []
            spans = [(m.ss, m.es) for m in miso[1:]] if miso else []
            nbytes = len(raw) - 1
            if nbytes < 0:
                return
            vals = [((raw[i] << 1) | (raw[i + 1] >> 7)) & 0xFF for i in range(nbytes)]
            vspans = [(spans[i][0], spans[i + 1][1]) for i in range(nbytes)]
        else:
            vals = [m.val for m in mosi[1:]]
            vspans = [(m.ss, m.es) for m in mosi[1:]]

        self._emit(addr, vals, vspans, read)
        idx = 0x37 - addr
        if not read and 0 <= idx < len(vals) and vals[idx] not in (0x00, 0x60):
            self._warn(ss, es, 'R37 is test mode: write 0x00 or 0x60 (reset) only, got 0x%02X' % vals[idx], 'R37!')
        self.put(ss, es, self.out_python,
                 ('REG_READ' if read else 'REG_WRITE', (addr, bytes(vals))))

    def _emit(self, addr, vals, vspans, read):
        # Multi-byte values that lie entirely inside this frame
        covered = set()
        for start, nb, bits, label in VALUES:
            i = start - addr
            if 0 <= i and i + nb <= len(vals):
                raw = 0
                for k in range(nb):
                    raw |= vals[i + k] << (8 * k)
                raw &= (1 << (nb * 8)) - 1
                code = _sext(raw, bits) if label in SIGNED else raw
                self._ann(vspans[i][0], vspans[i + nb - 1][1], ANN_VALUE,
                          ['%s = %d (0x%0*X)' % (label, code, nb * 2, raw), '%s=%d' % (label, code), str(code)])
                covered.update(range(i, i + nb))
        for i, v in enumerate(vals):
            if i in covered:
                continue
            a = addr + i
            long_s, med_s, short_s = _describe(a, v)
            self._ann(vspans[i][0], vspans[i][1], ANN_REG, [long_s, med_s, short_s])
