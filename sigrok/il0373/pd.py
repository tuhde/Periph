import sigrokdecode as srd

# BUSY pin level that means "busy" (active low); the connection decoder reports raw levels.
_BUSY_LEVEL = 0
_BUSY_WORD = 'BUSY low'

# Commands that drive BUSY low, from the chip datasheet's "BUSY Signal" table (flag = BUSY
# goes low): (note, confirmed in the panel datasheet, condition). The panel datasheet confirms only
# PON, DSP (data complete), DRF, APG and temperature-sensor communication; the rest are listed in
# the chip datasheet only, so their warning is hedged.
_BUSY_CMDS = {
    0x00: ('PSR soft reset, RST_N = 0', False, 'softreset'),
    0x02: ('POF power off', True, None),
    0x07: ('DSLP deep sleep', False, None),
    0x11: ('DSP data stop, data complete', False, 'frame'),
    0x12: ('DRF display refresh', True, None),
    0x40: ('TSC temperature sensor', True, None),
    0x51: ('LPD low power detection', False, None),
    0x80: ('AMV auto measure VCOM, AMVE = 1', False, 'amve'),
    0xA1: ('APG active programming', True, None),
}


ANN_COMMAND = 0
ANN_STATUS  = 1
ANN_WARNING = 2

# Number of parameter bytes each command takes; None = variable length (pixel data).
_PARAMS = {
    0x00: (1, 2),    # PSR: 1 byte (OTP flow) or 2 (vendor demo writes 0xBF 0x0E)
    0x01: 5, 0x02: 0, 0x03: 1, 0x04: 0, 0x05: 0, 0x06: 3, 0x07: 1,
    0x10: None, 0x12: 0, 0x13: None, 0x30: 1, 0x41: 1, 0x42: 3, 0x50: 1, 0x60: 1,
    0x61: 3, 0x65: 3, 0x80: 1, 0x82: 1, 0x90: 7, 0x91: 0, 0x92: 0, 0xA0: 1, 0xA1: 0,
    0xE0: 1, 0xE3: 1, 0xE4: 1, 0xE5: 1,
}
# LUTs: the chip datasheet gives 44 (VCOM) / 42 bytes, the panel datasheet 60 / 36 / 60 / 36 / 36;
# AUTO exists only in the panel datasheet; LUTOPT has 2 bytes there.
_PARAMS.update({0x17: 1, 0x20: (44, 60), 0x21: (42, 36), 0x22: (42, 60), 0x23: (42, 36),
                0x24: (42, 36), 0x2A: 2})

# Commands whose data bytes are read back from the chip. SDA is bidirectional: the bytes are the
# chip's output only if the probe sits on the shared DIN/DOUT line of a half-duplex wiring
# (DisplayConnection HALF_DUPLEX_*); on a write-only board they are not meaningful.
_READS = {0x11, 0x40, 0x43, 0x44, 0x51, 0x70, 0x71, 0x72, 0x81, 0xA2}

# Response length of a read command; absent = variable (REV is 1 byte in the panel datasheet and
# 5 in the chip datasheet, ROTP is open-ended) and the next command byte ends it.
_READ_LEN = {0x11: 1, 0x40: 2, 0x43: 2, 0x44: 1, 0x51: 1, 0x71: 1, 0x72: 2, 0x81: 1}

_NAMES = {
    0x00: 'Panel setting (PSR)', 0x01: 'Power setting (PWR)', 0x02: 'Power off (POF)',
    0x03: 'Power off sequence setting (PFS)', 0x04: 'Power on (PON)', 0x05: 'Power on measure (PMES)',
    0x06: 'Booster soft start (BTST)', 0x07: 'Deep sleep (DSLP)',
    0x10: 'Data start transmission 1, OLD (DTM1)', 0x11: 'Data stop (DSP)', 0x12: 'Display refresh (DRF)',
    0x13: 'Data start transmission 2, NEW (DTM2)', 0x30: 'PLL control (PLL)',
    0x40: 'Temperature sensor calibration (TSC)', 0x41: 'Temperature sensor select (TSE)',
    0x42: 'Temperature sensor write (TSW)', 0x43: 'Temperature sensor read (TSR)',
    0x50: 'VCOM and data interval setting (CDI)', 0x51: 'Low power detection (LPD)',
    0x60: 'TCON setting (TCON)', 0x61: 'Resolution setting (TRES)', 0x65: 'GSST setting (GSST)',
    0x70: 'Revision (REV)', 0x71: 'Get status (FLG)', 0x80: 'Auto measure VCOM (AMV)',
    0x81: 'VCOM value (VV)', 0x82: 'VCOM-DC setting (VDCS)', 0x90: 'Partial window (PTL)',
    0x91: 'Partial in (PTIN)', 0x92: 'Partial out (PTOUT)', 0xA0: 'Program mode (PGM)',
    0xA1: 'Active program (APG)', 0xA2: 'Read OTP (ROTP)', 0xE0: 'Cascade setting (CCSET)',
    0xE3: 'Power saving (PWS)', 0xE4: 'LPD selection (LPSEL)', 0xE5: 'Force temperature (TSSET)',
}

# Short tag used for the shortest annotation tier.
_SHORT = {
    0x00: 'PSR', 0x01: 'PWR', 0x02: 'POF', 0x03: 'PFS', 0x04: 'PON', 0x05: 'PMES', 0x06: 'BTST',
    0x07: 'DSLP', 0x10: 'DTM1', 0x11: 'DSP', 0x12: 'DRF', 0x13: 'DTM2', 0x30: 'PLL', 0x40: 'TSC',
    0x41: 'TSE', 0x42: 'TSW', 0x43: 'TSR', 0x50: 'CDI', 0x51: 'LPD', 0x60: 'TCON', 0x61: 'TRES',
    0x65: 'GSST', 0x70: 'REV', 0x71: 'FLG', 0x80: 'AMV', 0x81: 'VV', 0x82: 'VDCS', 0x90: 'PTL',
    0x91: 'PTIN', 0x92: 'PTOUT', 0xA0: 'PGM', 0xA1: 'APG', 0xA2: 'ROTP', 0xE0: 'CCSET',
    0xE3: 'PWS', 0xE4: 'LPSEL', 0xE5: 'TSSET',
}
_NAMES.update({
    0x17: 'Auto sequence (AUTO)', 0x20: 'VCOM LUT (LUTC)', 0x21: 'W2W LUT (LUTWW)',
    0x22: 'B2W LUT (LUTBW)', 0x23: 'W2B LUT (LUTWB)', 0x24: 'B2B LUT (LUTBB)',
    0x2A: 'LUT option (LUTOPT)', 0x44: 'Panel break check (PBC)', 0x72: 'Cyclic redundancy check (CRC)',
})
_SHORT.update({
    0x17: 'AUTO', 0x20: 'LUTC', 0x21: 'LUTWW', 0x22: 'LUTBW', 0x23: 'LUTWB', 0x24: 'LUTBB',
    0x2A: 'LUTOPT', 0x44: 'PBC', 0x72: 'CRC',
})

_RESOLUTIONS = {0: (96, 230), 1: (96, 252), 2: (128, 296), 3: (160, 296)}

# PLL frame rate in Hz, indexed [M][N - 1]. Panel datasheet p. 24: PLL byte = M[5:3] N[2:0],
# POR 0x3C (M=7, N=4) = 50 Hz. The chip datasheet defines the byte differently (FRS[4:0], POR 0x09);
# the panel layout is used because the panel datasheet describes the product that ships.
_PLL_FR = {
    1: (29, 14, 10, 7, 6, 5, 4),     2: (57, 29, 19, 14, 11, 10, 8),
    3: (86, 43, 29, 21, 17, 14, 12), 4: (114, 57, 38, 29, 23, 19, 16),
    5: (150, 72, 48, 36, 29, 24, 20), 6: (171, 86, 57, 43, 34, 29, 24),
    7: (200, 100, 67, 50, 40, 33, 29),
}

# The first FLG read after BUSY falls must be later than this (DRF description, chip and panel
# datasheets: "> 200 us").
_FLG_HOLDOFF_S = 200e-6

# BUSY-low spans shorter than this are not annotated (the module's BUSY toggles after PON).
_MIN_BUSY_S = 20e-6

# BUSY may fall this long after the CS# edge that completes a BUSY-driving command.
_BUSY_GRACE_S = 20e-6


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); if a
    call site supplied fewer, derive the missing tier from the existing text
    (leading name token, <= 8 chars when it becomes the shortest tier) so a
    narrow PulseView zoom still has something to show."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings

    def lead(text):
        text = text.strip()
        n = 0
        while n < len(text) and (text[n].isalnum() or text[n] in '_→'):
            n += 1
        return text[:n]

    first = strings[0]
    words = first.split()
    for cand in (lead(strings[-1])[:8], lead(first), ' '.join(words[:2]).rstrip(':,'),
                 ' '.join(words[:3]).rstrip(':,'), lead(first)[:8]):
        if len(strings) >= 3:
            break
        if cand and cand not in strings and len(cand) < len(first):
            i = 0
            while i < len(strings) and len(strings[i]) > len(cand):
                i += 1
            strings.insert(i, cand)
    return strings


def _vd(c):
    """VDH / VDL magnitude: 6.4 V + 0.2 V per step, 0x00..0x2B (15.0 V top)."""
    return 6.4 + c * 0.2 if c <= 0x2B else None


def _vcom(c):
    """VV / VDCS: -0.10 V - 0.05 V per step, 0x00..0x3A (-3.00 V)."""
    return -0.10 - c * 0.05 if c <= 0x3A else None


def _volts(v):
    return 'n/a' if v is None else '%.2f V' % v


def _signed(b):
    return b - 256 if b >= 128 else b


def _want_ok(want, n):
    """True when n parameter bytes satisfy a _PARAMS entry (int, tuple of ints, or None = any)."""
    if want is None:
        return True
    return n in want if isinstance(want, tuple) else n == want


def _describe_read(cmd, p):
    """Decode the bytes clocked out by the chip after a read command (half-duplex wiring)."""
    n = len(p)
    if not n:
        return 'read'
    if cmd == 0x71:
        b = p[0]
        s = ('BUSY=%d POF=%d PON=%d data_flag=%d I2C_BUSYN=%d I2C_ERR=%d PTL=%d' %
             (b & 1, (b >> 1) & 1, (b >> 2) & 1, (b >> 3) & 1, (b >> 4) & 1, (b >> 5) & 1, (b >> 6) & 1))
    elif cmd == 0x40:
        s = '%+d C' % _signed(p[0]) + (' (D[2:0]=%d)' % (p[1] >> 5) if n >= 2 else '')
    elif cmd == 0x44:
        s = 'panel OK' if p[0] & 1 else 'PANEL BROKEN'
    elif cmd == 0x51:
        s = 'LPD=%d (1 = normal)' % (p[0] & 1)
    elif cmd == 0x70:
        s = ('LUT_REV=0x%02X' % p[0] if n == 1 else
             'chip rev 0x%02X, LUT version 0x%06X' % (p[1], p[2] | (p[3] << 8) | (p[4] << 16))
             if n >= 5 else _hex(p))
    elif cmd == 0x72 and n >= 2:
        s = 'CRC=0x%04X' % ((p[0] << 8) | p[1])
    elif cmd == 0x81:
        s = 'VCOM=' + _volts(_vcom(p[0] & 0x3F))
    elif cmd == 0x11:
        s = 'data_flag=%d' % (p[0] >> 7)
    elif cmd == 0xA2:
        s = '%d byte(s), first is dummy: %s' % (n, _hex(p))
    else:
        s = _hex(p)
    return 'read: ' + s + ' [SDA = chip output; meaningful only on a shared DIN/DOUT probe]'


def _hex(bs, limit=8):
    s = ' '.join('%02X' % b for b in bs[:limit])
    return s + (' ...(%d)' % len(bs) if len(bs) > limit else '')


def _col9(hi, lo):
    """9-bit horizontal value sent as bit 8 in one byte and bits 7:3 in the next."""
    return ((hi & 1) << 8) | (lo & 0xF8)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'il0373'
    name = 'IL0373'
    longname = 'IL0373 e-paper controller'
    desc = ('Decode the command stream of an IL0373 black/white EPD controller '
            '(e.g. Waveshare 2.13inch e-Paper (D)) from the `display_spi` connection decoder.')
    license = 'gplv2+'
    inputs = ['display_spi']
    outputs = ['il0373']
    tags = ['Display', 'IC']

    annotations = (
        ('command', 'Command'),
        ('status',  'Status'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_COMMAND,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid not in ('timing', 'warnings') for c in classes}
            if data[0] in tiered:
                data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.samplerate = None
        self.cs_active = False
        self.cmd = None           # current command byte
        self.cmd_ss = None
        self.cmd_es = None
        self.params = bytearray()
        self.busy_ss = None       # start of the current BUSY-low span
        self.busy_since = None        # sample BUSY last became active
        self.busy_until = None        # sample it was released (None while active)
        self.busy_wired = False       # a BUSY packet was seen (channel probed)
        self.busy_checked = False     # expected-BUSY check done for the current command
        self.flg_warned = False       # FLG hold-off warned since the last DRF / PON
        self.pending_busy = None      # (cmd, note, confirmed, cmd_ss, cs_end) awaiting BUSY low
        self.refreshing = False       # DRF / POF / DSLP issued and BUSY not yet high again
        self.plane_full = {}          # {0x10/0x13: True} once a whole plane was written
        self.res_ss = None
        self.res = _RESOLUTIONS[0]   # (width, height), from PSR / TRES
        self.partial = False
        self.window = None           # (hrst, hred, vrst, vred) from PTL
        self.ddx = 1                  # CDI.DDX, power-on 01 (CDI POR 0xD7)
        self.cdi_written = False

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    # ---- helpers ---------------------------------------------------------------------

    def _dur(self, samples):
        if not self.samplerate:
            return '%d samples' % samples
        t = samples / float(self.samplerate)
        if t >= 1.0:
            return '%.2f s' % t
        if t >= 1e-3:
            return '%.2f ms' % (t * 1e3)
        return '%.1f us' % (t * 1e6)

    def _warn(self, ss, es, text, tag):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [text, tag]])

    def _frame_bytes(self):
        """Number of bytes one plane write should carry in the current mode."""
        if self.partial and self.window:
            hrst, hred, vrst, vred = self.window
            return ((hred - hrst + 1) // 8) * (vred - vrst + 1)
        return (self.res[0] // 8) * self.res[1]

    def _describe(self, cmd, p):
        """Return (long text, short tag) for a command with its parameter bytes."""
        name = _NAMES.get(cmd, 'Unknown command 0x%02X' % cmd)
        short = _SHORT.get(cmd, '0x%02X' % cmd)
        args = ''
        n = len(p)
        if cmd == 0x00 and n >= 1:
            res = _RESOLUTIONS[p[0] >> 6]
            args = '%dx%d preset, LUT from %s, %s, gate scan %s, source shift %s, booster %s%s' % (
                res[0], res[1], 'register' if p[0] & 0x20 else 'OTP',
                'B/W only' if p[0] & 0x10 else 'B/W/red', 'up' if p[0] & 0x08 else 'down',
                'right' if p[0] & 0x04 else 'left', 'on' if p[0] & 0x02 else 'off',
                '' if p[0] & 0x01 else ', SOFT RESET')
        elif cmd == 0x01 and n >= 5:
            args = ('source %s, gate %s, VCOM_HV=%d, VGH=%d V, VDH=%s, VDL=-%s, VDHR=%s (red, unused in KW)' % (
                'internal' if p[0] & 2 else 'external', 'internal' if p[0] & 1 else 'external',
                (p[1] >> 2) & 1, 20 - (p[1] & 3), _volts(_vd(p[2] & 0x3F)),
                _volts(_vd(p[3] & 0x3F)), _volts(_vd(p[4] & 0x3F))))
            if (p[2] & 0x3F) > 0x2B or (p[3] & 0x3F) > 0x2B:
                args += ' [VDH/VDL code above 0x2B reads as 15.0 V]'
        elif cmd == 0x03 and n >= 1:
            args = 'VDH/VDL off after %d frame(s)' % (((p[0] >> 4) & 3) + 1)
        elif cmd == 0x06 and n >= 3:
            args = 'phaseA=0x%02X phaseB=0x%02X phaseC=0x%02X' % (p[0], p[1], p[2])
        elif cmd == 0x07 and n >= 1:
            args = 'check code OK' if p[0] == 0xA5 else 'BAD check code 0x%02X (needs 0xA5)' % p[0]
        elif cmd in (0x10, 0x13):
            # Pixel polarity follows CDI.DDX (DDX[0] B/W, DDX[1] red); the power-on value is 01.
            if cmd == 0x10:
                plane = 'OLD (KW mode, %s)' % ('1=white' if self.ddx & 1 else '0=white')
            else:
                plane = 'NEW (KW mode, %s)' % ('1=white' if self.ddx & 1 else '0=white')
            if n and len(set(p)) == 1:
                content = ', all 0x%02X' % p[0]
            elif n:
                content = ', %d distinct values' % len(set(p))
            else:
                content = ''
            note = ('' if self.cdi_written else
                    ' [CDI not written: DDX=%d%d is the power-on value, OTP may override]' %
                    ((self.ddx >> 1) & 1, self.ddx & 1))
            args = '%s plane, %d bytes (full frame %d)%s%s' % (plane, n, self._frame_bytes(), content, note)
        elif cmd == 0x17 and n >= 1:
            args = {0xA5: 'PON -> DRF -> POF', 0xA7: 'PON -> DRF -> POF -> DSLP'}.get(
                p[0], 'BAD check code 0x%02X (needs 0xA5 or 0xA7)' % p[0])
        elif cmd in (0x20, 0x21, 0x22, 0x23, 0x24):
            args = '%d byte(s) (expected %s)' % (n, ' or '.join(str(w) for w in _PARAMS[cmd]))
        elif cmd == 0x30 and n >= 1:
            m, nn = (p[0] >> 3) & 7, p[0] & 7
            fr = _PLL_FR[m][nn - 1] if m in _PLL_FR and nn >= 1 else None
            args = 'M=%d N=%d%s' % (m, nn, ' (%d Hz)' % fr if fr else '')
        elif cmd == 0x41 and n >= 1:
            args = '%s sensor, offset %+d C' % ('external' if p[0] & 0x80 else 'internal',
                                               (p[0] & 0xF) - 16 if p[0] & 8 else p[0] & 0xF)
        elif cmd == 0x42 and n >= 3:
            args = 'attr=0x%02X msb=0x%02X lsb=0x%02X' % (p[0], p[1], p[2])
        elif cmd == 0x50 and n >= 1:
            args = 'VBD=%d DDX=%d interval=%d hsync%s' % (
                p[0] >> 6, (p[0] >> 4) & 3, 17 - (p[0] & 0xF),
                ', border floating' if (p[0] >> 6) == 3 else '')
        elif cmd == 0x60 and n >= 1:
            args = 'S2G=%d G2S=%d (x 650 ns)' % (4 + 4 * (p[0] >> 4), 4 + 4 * (p[0] & 0xF))
        elif cmd == 0x61 and n >= 3:
            args = '%d x %d' % (p[0] & 0xF8, ((p[1] & 1) << 8) | p[2])
        elif cmd == 0x65 and n >= 3:
            args = 'first source %d, first gate %d' % (p[0] & 0xF8, ((p[1] & 1) << 8) | p[2])
        elif cmd == 0x80 and n >= 1:
            args = 'time=%d s, XON=%d AMVS=%d AMV=%d AMVE=%d' % (
                (3, 5, 8, 10)[(p[0] >> 4) & 3], (p[0] >> 3) & 1, (p[0] >> 2) & 1, (p[0] >> 1) & 1, p[0] & 1)
        elif cmd == 0x82 and n >= 1:
            args = 'VCOM_DC=%s' % _volts(_vcom(p[0] & 0x3F))
        elif cmd == 0x90 and n >= 7:
            args = 'X %d..%d, Y %d..%d%s' % (
                p[0] & 0xF8, (p[1] & 0xF8) | 7, ((p[2] & 1) << 8) | p[3],
                ((p[4] & 1) << 8) | p[5], ', PT_SCAN' if p[6] & 1 else '')
        elif cmd == 0xA0 and n >= 1:
            args = 'check code OK' if p[0] == 0xA5 else 'BAD check code 0x%02X (needs 0xA5)' % p[0]
        elif cmd == 0xE0 and n >= 1:
            args = 'CCEN=%d TSFIX=%d' % (p[0] & 1, (p[0] >> 1) & 1)
        elif cmd == 0xE3 and n >= 1:
            args = 'VCOM_W=%d SD_W=%d' % (p[0] >> 4, p[0] & 0xF)
        elif cmd == 0xE4 and n >= 1:
            args = 'LPMD=%d LP_SEL=%d' % ((p[0] >> 2) & 1, p[0] & 3)
        elif cmd == 0xE5 and n >= 1:
            args = '%d C' % _signed(p[0])
        elif cmd in _READS:
            args = _describe_read(cmd, p)
        elif n:
            args = _hex(p)
        text = '0x%02X %s' % (cmd, name)
        if args:
            text += ': ' + args
        return text, short

    # ---- command/byte handling --------------------------------------------------------

    def _finish_command(self):
        if self.cmd is None:
            return
        cmd, p = self.cmd, bytes(self.params)
        text, short = self._describe(cmd, p)
        self.put(self.cmd_ss, self.cmd_es, self.out_ann, [ANN_COMMAND, [text, '0x%02X %s' % (cmd, short), short]])
        self.put(self.cmd_ss, self.cmd_es, self.out_python, ('COMMAND', (cmd, p)))

        # Plausibility checks
        want = _PARAMS.get(cmd, 'unknown')
        if cmd in _READS:
            pass
        elif want == 'unknown':
            self._warn(self.cmd_ss, self.cmd_es, 'Unknown IL0373 command 0x%02X' % cmd, 'CMD?')
        elif not _want_ok(want, len(p)):
            exp = ' or '.join(str(w) for w in want) if isinstance(want, tuple) else want
            self._warn(self.cmd_ss, self.cmd_es,
                       'Command 0x%02X has %d parameter byte(s), expected %s' % (cmd, len(p), exp), 'PARAM?')
        if cmd in (0x10, 0x13) and len(p) > self._frame_bytes():
            self._warn(self.cmd_ss, self.cmd_es,
                       'Pixel write of %d bytes exceeds the %d-byte frame' % (len(p), self._frame_bytes()), 'SIZE?')
        if cmd in (0x07, 0xA0) and p and p[0] != 0xA5:
            self._warn(self.cmd_ss, self.cmd_es, 'Check code must be 0xA5', 'CHK?')
        if cmd == 0x17 and p and p[0] not in (0xA5, 0xA7):
            self._warn(self.cmd_ss, self.cmd_es, 'AUTO check code must be 0xA5 or 0xA7', 'CHK?')

        # Track resolution / partial window for later pixel writes
        if cmd == 0x00 and p:
            self.res = _RESOLUTIONS[p[0] >> 6]
        elif cmd == 0x61 and len(p) >= 3:
            self.res = (p[0] & 0xF8, ((p[1] & 1) << 8) | p[2])
        elif cmd == 0x90 and len(p) >= 7:
            self.window = (p[0] & 0xF8, (p[1] & 0xF8) | 7,
                           ((p[2] & 1) << 8) | p[3], ((p[4] & 1) << 8) | p[5])
        elif cmd == 0x91:
            self.partial = True
        elif cmd == 0x92:
            self.partial = False

        if cmd == 0x50 and p:
            self.ddx = (p[0] >> 4) & 3
            self.cdi_written = True
        if cmd in (0x10, 0x13):
            self.plane_full[cmd] = len(p) >= self._frame_bytes()
        elif cmd == 0x12:
            self.plane_full = {}
        self.cmd = None
        self.params = bytearray()

    # ---- expected-BUSY check ---------------------------------------------------------------

    def _busy_expected(self, cmd, p):
        """(note, confirmed) when the datasheet says this command (with these parameters) drives
        BUSY, else None."""
        entry = _BUSY_CMDS.get(cmd)
        if entry is None:
            return None
        note, confirmed, cond = entry
        if cond and not self._busy_cond(cond, p):
            return None
        return note, confirmed

    def _busy_complete(self, cmd, p):
        """True once the command and all parameter bytes that decide its BUSY behaviour arrived."""
        if cmd in _READS:
            return True                     # a read is complete after the command byte
        want = _PARAMS.get(cmd, 'unknown')
        if want is None or want == 'unknown':
            return True
        need = min(want) if isinstance(want, tuple) else want
        return len(p) >= need

    def _check_busy_at_cs(self, ss):
        """A command which the datasheet says drives BUSY must see BUSY go low at, or within
        _BUSY_GRACE_S after, the CS# rising edge that completes it (the module's BUSY falls up to a
        few microseconds late). BUSY already being low counts too. The check is settled by the next
        BUSY or byte packet."""
        if self.cmd is None or self.busy_checked or not self.busy_wired:
            return
        cmd, p = self.cmd, bytes(self.params)
        if not self._busy_complete(cmd, p):
            return
        self.busy_checked = True
        exp = self._busy_expected(cmd, p)
        if exp and self.busy_ss is None:
            self.pending_busy = (cmd, exp[0], exp[1], self.cmd_ss, ss)

    def _settle_pending(self, now, busy_low):
        """Resolve a pending expected-BUSY check at sample `now`."""
        if self.pending_busy is None:
            return
        cmd, note, confirmed, cmd_ss, cs_end = self.pending_busy
        late = (now - cs_end) / float(self.samplerate) if self.samplerate else 0
        if busy_low and late <= _BUSY_GRACE_S:
            self.pending_busy = None
        elif late > _BUSY_GRACE_S or not busy_low:
            self.pending_busy = None
            if not busy_low or late > _BUSY_GRACE_S:
                self._warn(cmd_ss, cs_end, 'Command 0x%02X (%s) should drive %s at the CS# rising edge '
                           'that ends it, but it was not active%s' % (
                               cmd, note, _BUSY_WORD,
                               '' if confirmed else ' (only the chip datasheet lists this command)'),
                           'NOBUSY' if confirmed else 'NOBUSY?')

    def _busy_cond(self, cond, p):
        if cond == 'softreset':
            return bool(p) and not (p[0] & 1)
        if cond == 'amve':
            return bool(p) and bool(p[0] & 1)
        if cond == 'frame':        # DSP starts the refresh only once both planes were received
            return all(self.plane_full.get(c) for c in (0x10, 0x13))
        return True


    def _busy_at(self, ref):
        """True when BUSY was active at sample `ref`. Judged by timestamps, not by packet order: a
        command must be compared with the BUSY state when it *started* (CS# active), never with the
        BUSY the command itself causes at or just after its last clock edge."""
        if self.busy_since is None or self.busy_since > ref:
            return False
        if self.busy_until is not None and self.busy_until <= ref:
            return False
        # BUSY must have been low for a while: the module's BUSY toggles in short pulses.
        return (not self.samplerate or (ref - self.busy_since) / float(self.samplerate) >= _MIN_BUSY_S)

    def _byte_done(self, ss, es, value, dc):
        if dc == 0:
            self._settle_pending(ss, False)
            # The vendor demo polls FLG in a tight loop: warn only for the first FLG of a run.
            self.flg_warned = (self.cmd == 0x71 or (self.cmd is None and self.flg_warned))
            self._finish_command()
            self.busy_checked = False
            self.cmd = value
            self.cmd_ss = ss
            self.cmd_es = es
            self.params = bytearray()
            was_refreshing = self.refreshing
            if value in (0x12, 0x02, 0x07):
                self.refreshing = True
            if self._busy_at(ss):
                if value == 0x71:
                    if (not self.flg_warned and self.samplerate and
                            (ss - self.busy_since) / float(self.samplerate) < _FLG_HOLDOFF_S):
                        self.flg_warned = True
                        self._warn(ss, es, 'FLG (0x71) %s after BUSY fell, datasheet requires > 200 us' %
                                   self._dur(ss - self.busy_since), 'FLG<200us')
                elif was_refreshing and value not in (0x70, 0x81):    # REV and VV are also accepted while busy
                    self._warn(ss, es, 'Command 0x%02X sent while BUSY is low' % value, 'BUSY!')
        else:
            if self.cmd is None:
                self._warn(ss, es, 'Data byte 0x%02X without a preceding command' % value, 'NOCMD')
                return
            self.params.append(value)
            self.cmd_es = es

    # ---- packets from display_spi ----------------------------------------------------------

    def decode(self, ss, es, data):
        kind, value = data

        if kind == 'BYTE':
            dc, mosi, miso = value
            # After a read command the chip drives the data line: prefer the separately probed
            # DOUT value when the connection decoder has one, else the (shared) DIN line.
            byte = miso if (dc == 1 and self.cmd in _READS and miso is not None) else mosi
            self._byte_done(ss, es, byte, dc)

        elif kind == 'CS':
            self.cs_active = bool(value)
            if not self.cs_active:
                self._check_busy_at_cs(ss)
            # Flush on release only when the command takes no parameters (or is unknown); reads
            # and commands with parameters are flushed by the next command byte or by IDLE, so
            # that extra bytes are still attributed to them (an over-long write is PARAM?).
            if not self.cs_active and self.cmd is not None and self.cmd not in _READS:
                want = _PARAMS.get(self.cmd, 'unknown')
                if want == 'unknown' or want == 0:
                    self._finish_command()
            if (not self.cs_active and self.cmd is not None and self.cmd in _READS and
                    _READ_LEN.get(self.cmd) is not None and
                    len(self.params) >= _READ_LEN[self.cmd]):
                self._finish_command()

        elif kind == 'IDLE':
            # Variable-length commands (pixel data, ROTP, REV) have no end marker.
            self._finish_command()

        elif kind == 'BUSY':
            self.busy_wired = True
            if value == _BUSY_LEVEL:
                self.busy_since = ss
                self.busy_until = None
                self.busy_ss = ss
                self._settle_pending(ss, True)
            else:
                self.refreshing = False
            if value != _BUSY_LEVEL and self.busy_ss is not None:
                self.busy_until = ss
                d = ss - self.busy_ss
                # The module's BUSY toggles at ~2 MHz after PON (measured); spans shorter than
                # 20 us are noise, not a busy period, and are not annotated.
                if not self.samplerate or d / float(self.samplerate) >= _MIN_BUSY_S:
                    self.put(self.busy_ss, ss, self.out_ann,
                             [ANN_STATUS, ['Busy for ' + self._dur(d), 'Busy ' + self._dur(d), 'Busy']])
                self.busy_ss = None

        elif kind == 'RESET':
            if value:
                self.res_ss = ss
            elif self.res_ss is not None:
                d = ss - self.res_ss
                self.put(self.res_ss, ss, self.out_ann,
                         [ANN_STATUS, ['RES# low for ' + self._dur(d), 'RES# ' + self._dur(d), 'RES#']])
                self.res_ss = None
