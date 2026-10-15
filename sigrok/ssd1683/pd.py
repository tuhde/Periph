import sigrokdecode as srd

ANN_COMMAND = 0
ANN_STATUS  = 1
ANN_WARNING = 2

# BUSY pin level that means "busy": SSD1683 BUSY is active high (the module datasheet's note 5-4
# says the opposite; the chip datasheet, the reference code and the status register agree on high).
_BUSY_LEVEL = 1
_BUSY_WORD = 'BUSY high'

# Commands the SSD1683 datasheet says drive BUSY high ("BUSY pad will output high during
# operation"): (note, confirmed, condition). Deep sleep keeps BUSY high until a hardware reset.
_BUSY_CMDS = {
    0x08: ('program initial code setting (OTP)', True, None),
    0x10: ('deep sleep', True, 'sleep'),
    0x12: ('software reset', True, None),
    0x14: ('HV ready detection', True, None),
    0x15: ('VCI detection', True, None),
    0x16: ('program WS password to OTP', True, None),
    0x17: ('program OTP (auto)', True, None),
    0x1C: ('write command to external temperature sensor', True, None),
    0x20: ('master activation', True, None),
    0x28: ('VCOM sense', True, None),
    0x2A: ('program VCOM OTP', True, None),
    0x30: ('program WS OTP', True, None),
    0x31: ('load WS OTP', True, None),
    0x34: ('CRC calculation', True, None),
    0x36: ('program OTP selection', True, None),
    0x46: ('auto write red RAM', True, None),
    0x47: ('auto write B/W RAM', True, None),
}


# Number of parameter bytes each command takes; None = variable length (RAM data, LUT, reads).
_PARAMS = {
    0x01: 3, 0x03: 1, 0x04: 3, 0x08: 0, 0x09: 4, 0x0A: 0, 0x0C: 4, 0x10: 1, 0x11: 1, 0x12: 0,
    0x14: 1, 0x15: 1, 0x16: 1, 0x17: 1, 0x18: 1, 0x1A: 1, 0x1B: 1, 0x1C: 3, 0x20: 0, 0x21: 2,
    0x22: 1, 0x24: None, 0x26: None, 0x27: None, 0x28: 0, 0x29: 1, 0x2A: 0, 0x2C: 1, 0x2D: None,
    0x2E: None, 0x2F: None, 0x30: 0, 0x31: 0, 0x32: 227, 0x34: 0, 0x35: None, 0x36: 0, 0x37: 10,
    0x38: 10, 0x39: 1, 0x3C: 1, 0x3F: 1, 0x41: 3, 0x44: 2, 0x45: 4, 0x46: 1, 0x47: 1, 0x4E: 1,
    0x4F: 2, 0x7F: 0,
}

# Commands whose data bytes are read back from the chip. The chip drives SDA after the command byte:
# the bytes are meaningful only with half-duplex wiring (DisplayConnection HALF_DUPLEX_*); on a
# write-only module they are not.
_READS = {0x1B, 0x27, 0x2D, 0x2E, 0x2F, 0x35}

_NAMES = {
    0x01: 'Driver output control', 0x03: 'Gate driving voltage', 0x04: 'Source driving voltage',
    0x08: 'Program initial code', 0x09: 'Write initial code register', 0x0A: 'Read initial code register',
    0x0C: 'Booster soft start', 0x10: 'Deep sleep', 0x11: 'Data entry mode', 0x12: 'Software reset',
    0x14: 'HV ready detection', 0x15: 'VCI detection', 0x16: 'Program WS password',
    0x17: 'Program OTP (auto)', 0x18: 'Temperature sensor select', 0x1A: 'Write temperature',
    0x1B: 'Read temperature', 0x1C: 'External temperature sensor command', 0x20: 'Master activation',
    0x21: 'Display update control 1', 0x22: 'Display update control 2', 0x24: 'Write RAM (B/W)',
    0x26: 'Write RAM (red)', 0x27: 'Read RAM', 0x28: 'VCOM sense', 0x29: 'VCOM sense duration',
    0x2A: 'Program VCOM OTP', 0x2C: 'Write VCOM', 0x2D: 'OTP register read', 0x2E: 'User ID read',
    0x2F: 'Status bit read', 0x30: 'Program WS OTP', 0x31: 'Load WS OTP', 0x32: 'Write LUT',
    0x34: 'CRC calculation', 0x35: 'CRC status read', 0x36: 'Program OTP selection',
    0x37: 'Write display option', 0x38: 'Write user ID', 0x39: 'OTP program mode',
    0x3C: 'Border waveform', 0x3F: 'End option', 0x41: 'Read RAM option', 0x44: 'RAM X window',
    0x45: 'RAM Y window', 0x46: 'Auto write red RAM', 0x47: 'Auto write B/W RAM',
    0x4E: 'RAM X counter', 0x4F: 'RAM Y counter', 0x7F: 'NOP',
}

# Short tag used for the shortest annotation tier.
_SHORT = {
    0x01: 'DRV', 0x03: 'VGH', 0x04: 'VSRC', 0x0C: 'SOFT', 0x10: 'SLEEP', 0x11: 'ENTRY', 0x12: 'SWRST',
    0x18: 'TSEL', 0x1A: 'TEMP', 0x20: 'ACT', 0x21: 'DUC1', 0x22: 'DUC2', 0x24: 'BW', 0x26: 'RED',
    0x2C: 'VCOM', 0x32: 'LUT', 0x3C: 'BORD', 0x3F: 'EOPT', 0x44: 'XWIN', 0x45: 'YWIN', 0x46: 'FILLR',
    0x47: 'FILLB', 0x4E: 'XCNT', 0x4F: 'YCNT', 0x7F: 'NOP',
}

_UPDATE_SEQ = {
    0x80: 'clock on', 0x01: 'clock off', 0xC0: 'clock + analog on', 0x03: 'analog + clock off',
    0x91: 'load LUT (3-colour)', 0x99: 'load LUT (B/W)',
    0xB1: 'load temperature + LUT (3-colour)', 0xB9: 'load temperature + LUT (B/W)',
    0xC7: 'display (3-colour)', 0xCF: 'display (B/W)',
    0xF7: 'load temperature + LUT + display (3-colour)', 0xFF: 'load temperature + LUT + display (B/W)',
}

_SLEEP = {0x00: 'normal', 0x01: 'deep sleep 1 (RAM kept)', 0x03: 'deep sleep 2 (RAM lost)'}


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


def _vgh(c):
    if c == 0x00:
        return 20.0
    return 10.0 + (c - 3) * 0.5 if 3 <= c <= 0x17 else None


def _vsh1(c):
    return 8.6 + (c - 0x21) * 0.2 if 0x21 <= c <= 0x4B else None


def _vsh2(c):
    if c & 0x80:
        return 2.4 + (c - 0x8E) * 0.1 if 0x8E <= c <= 0xCC else None
    return _vsh1(c)


def _vsl(c):
    return -5.0 - (c - 0x0A) * 0.25 if 0x0A <= c <= 0x3A and (c & 1) == 0 else None


def _vcom(c):
    return -0.2 - (c - 0x08) * 0.025 if 0x08 <= c <= 0x78 else None


def _volts(v):
    return 'n/a' if v is None else '%.2f V' % v


def _signed(b):
    return b - 256 if b >= 128 else b


def _hex(bs, limit=8):
    s = ' '.join('%02X' % b for b in bs[:limit])
    return s + (' ...(%d)' % len(bs) if len(bs) > limit else '')


class Decoder(srd.Decoder):
    api_version = 3
    id = 'ssd1683'
    name = 'SSD1683'
    longname = 'Solomon Systech SSD1683 e-paper controller'
    desc = ('Decode the command stream of an SSD1683 red/black/white EPD controller from the '
            '`display_spi` connection decoder.')
    license = 'gplv2+'
    inputs = ['display_spi']
    outputs = ['ssd1683']
    tags = ['Display', 'IC']

    options = (
        {'id': 'warn_cs_split', 'desc': 'Warn when CS# is released between a command and its parameters',
         'default': 'yes', 'values': ('yes', 'no')},
    )
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
        self.cmd_cs_split = False
        self.cs_reasserted = False   # CS# went low again while a command was still pending
        self.busy_ss = None
        self.busy_since = None        # sample BUSY last became active
        self.busy_until = None        # sample it was released (None while active)
        self.busy_wired = False       # a BUSY packet was seen (channel probed)
        self.busy_checked = False     # expected-BUSY check done for the current command
        self.in_sleep = False         # deep sleep entered, no hardware reset since
        self.res_ss = None
        self.activation = None
        # RAM window tracking (for plausibility checks)
        self.x_win = (0x00, 0x31)
        self.y_win = (0x000, 0x12B)

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)
        self.warn_split = (self.options['warn_cs_split'] == 'yes')

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

    def _describe(self, cmd, p):
        """Return (long text, short tag) for a command with its parameter bytes."""
        name = _NAMES.get(cmd, 'Unknown command 0x%02X' % cmd)
        short = _SHORT.get(cmd, '0x%02X' % cmd)
        args = ''
        n = len(p)
        if cmd == 0x01 and n >= 3:
            gates = (p[0] | ((p[1] & 1) << 8)) + 1
            args = 'gate lines=%d GD=%d SM=%d TB=%d' % (gates, (p[2] >> 2) & 1, (p[2] >> 1) & 1, p[2] & 1)
        elif cmd == 0x03 and n >= 1:
            args = 'VGH=%s' % _volts(_vgh(p[0] & 0x1F))
        elif cmd == 0x04 and n >= 3:
            args = 'VSH1=%s VSH2=%s VSL=%s' % (_volts(_vsh1(p[0])), _volts(_vsh2(p[1])), _volts(_vsl(p[2])))
        elif cmd == 0x0C and n >= 4:
            args = 'phase1=0x%02X phase2=0x%02X phase3=0x%02X duration=0x%02X' % (p[0], p[1], p[2], p[3])
        elif cmd == 0x10 and n >= 1:
            args = _SLEEP.get(p[0] & 3, 'mode 0x%02X' % p[0])
        elif cmd == 0x11 and n >= 1:
            idx = ['Y-/X-', 'Y-/X+', 'Y+/X-', 'Y+/X+'][p[0] & 3]
            args = '%s, %s first' % (idx, 'Y' if p[0] & 4 else 'X')
        elif cmd == 0x18 and n >= 1:
            args = {0x80: 'internal sensor', 0x48: 'external I2C sensor'}.get(p[0], '0x%02X' % p[0])
        elif cmd == 0x1A and n >= 1:
            args = '%d C' % _signed(p[0])
        elif cmd == 0x21 and n >= 2:
            opt = {0: 'normal', 4: 'bypass(0)', 8: 'inverse'}
            args = 'red RAM %s, B/W RAM %s' % (opt.get(p[0] >> 4, '?'), opt.get(p[0] & 0xF, '?'))
        elif cmd == 0x22 and n >= 1:
            args = _UPDATE_SEQ.get(p[0], 'sequence 0x%02X' % p[0])
        elif cmd in (0x24, 0x26):
            colour = 'B/W' if cmd == 0x24 else 'red'
            x0, x1 = self.x_win
            y0, y1 = self.y_win
            args = '%s plane, %d bytes (window X %d..%d, Y %d..%d = %d bytes)' % (
                colour, n, x0, x1, y0, y1, (abs(x1 - x0) + 1) * (abs(y1 - y0) + 1))
        elif cmd == 0x2C and n >= 1:
            args = 'VCOM=%s' % _volts(_vcom(p[0]))
        elif cmd == 0x32:
            args = 'LUT, %d bytes' % n
        elif cmd == 0x3C and n >= 1:
            args = '0x%02X' % p[0]
        elif cmd == 0x44 and n >= 2:
            args = 'X %d..%d (bytes; pixels %d..%d)' % (p[0] & 0x3F, p[1] & 0x3F, (p[0] & 0x3F) * 8,
                                                         (p[1] & 0x3F) * 8 + 7)
        elif cmd == 0x45 and n >= 4:
            args = 'Y %d..%d' % (p[0] | ((p[1] & 1) << 8), p[2] | ((p[3] & 1) << 8))
        elif cmd == 0x46 and n >= 1:
            args = 'first step=%d, height=%s, width=%s' % (
                p[0] >> 7, {0: 8, 1: 16, 2: 32, 3: 64, 4: 128, 5: 256, 6: 300}.get((p[0] >> 4) & 7, '?'),
                {0: 8, 1: 16, 2: 32, 3: 64, 4: 128, 5: 256, 6: 400}.get(p[0] & 7, '?'))
        elif cmd == 0x47 and n >= 1:
            args = 'first step=%d, height=%s, width=%s' % (
                p[0] >> 7, {0: 8, 1: 16, 2: 32, 3: 64, 4: 128, 5: 256, 6: 300}.get((p[0] >> 4) & 7, '?'),
                {0: 8, 1: 16, 2: 32, 3: 64, 4: 128, 5: 256, 6: 400}.get(p[0] & 7, '?'))
        elif cmd == 0x4E and n >= 1:
            args = 'X=%d' % (p[0] & 0x3F)
        elif cmd == 0x4F and n >= 2:
            args = 'Y=%d' % (p[0] | ((p[1] & 1) << 8))
        elif cmd in _READS:
            args = ('read: ' + _hex(p) + ' [chip output; meaningful only with half-duplex wiring]'
                    if n else 'read')
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
        if want == 'unknown':
            self._warn(self.cmd_ss, self.cmd_es, 'Unknown SSD1683 command 0x%02X' % cmd, 'CMD?')
        elif want is not None and len(p) != want:
            self._warn(self.cmd_ss, self.cmd_es,
                       'Command 0x%02X has %d parameter byte(s), expected %d' % (cmd, len(p), want), 'PARAM?')
        if cmd in (0x24, 0x26):
            x0, x1 = self.x_win
            y0, y1 = self.y_win
            cap = (abs(x1 - x0) + 1) * (abs(y1 - y0) + 1)
            if len(p) > cap:
                self._warn(self.cmd_ss, self.cmd_es,
                           'RAM write of %d bytes exceeds the %d-byte window' % (len(p), cap), 'WIN?')

        # Track window/entry for later RAM writes
        if cmd == 0x44 and len(p) >= 2:
            self.x_win = (p[0] & 0x3F, p[1] & 0x3F)
        elif cmd == 0x45 and len(p) >= 4:
            self.y_win = (p[0] | ((p[1] & 1) << 8), p[2] | ((p[3] & 1) << 8))
        elif cmd == 0x20:
            self.activation = self.cmd_ss

        if self.warn_split and self.cmd_cs_split:
            self._warn(self.cmd_ss, self.cmd_es,
                       'CS# was released between command 0x%02X and its parameters (datasheet Fig. 6-1 keeps it low)'
                       % cmd, 'CS-SPLIT')

        if cmd == 0x10 and p and (p[0] & 3):
            self.in_sleep = True      # BUSY must now stay high until a hardware reset
        self.cmd = None
        self.params = bytearray()
        self.cmd_cs_split = False

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
        """BUSY must already be active at the CS# rising edge that completes a command which the
        datasheet says drives BUSY."""
        if self.cmd is None or self.busy_checked or not self.busy_wired:
            return
        cmd, p = self.cmd, bytes(self.params)
        if not self._busy_complete(cmd, p):
            return
        self.busy_checked = True
        exp = self._busy_expected(cmd, p)
        if exp and self.busy_ss is None:
            note, confirmed = exp
            self._warn(self.cmd_ss, ss, 'Command 0x%02X (%s) should drive %s by the CS# rising edge '
                       'that ends it, but it was not active%s' % (
                           cmd, note, _BUSY_WORD,
                           '' if confirmed else ' (only the chip datasheet lists this command)'),
                       'NOBUSY' if confirmed else 'NOBUSY?')

    def _busy_cond(self, cond, p):
        if cond == 'sleep':        # A[1:0] = 01 / 11 enter deep sleep, 00 is normal mode
            return bool(p) and bool(p[0] & 3)
        return True


    def _busy_at(self, ref):
        """True when BUSY was active at sample `ref`. Judged by timestamps, not by packet order: a
        command must be compared with the BUSY state when it *started* (CS# active), never with the
        BUSY the command itself causes at or just after its last clock edge."""
        return (self.busy_since is not None and self.busy_since <= ref and
                (self.busy_until is None or self.busy_until > ref))

    def _byte_done(self, ss, es, value, dc):
        if dc == 1 and self.cs_reasserted and self.cmd not in _READS:
            self.cmd_cs_split = True
        self.cs_reasserted = False
        if dc == 0:
            self._finish_command()
            self.busy_checked = False
            self.cmd = value
            self.cmd_ss = ss
            self.cmd_es = es
            self.params = bytearray()
            if self._busy_at(ss):
                self._warn(ss, es, 'Command 0x%02X sent while BUSY is high' % value, 'BUSY!')
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
            if self.cs_active:
                # Remember a re-assertion while a command is pending; it is a split only if the
                # next byte turns out to be a parameter (D/C high), not a new command.
                if self.cmd is not None:
                    self.cs_reasserted = True
            elif self.cmd is not None:
                # Flush on release when the command cannot take further parameters (zero-parameter,
                # unknown, or complete fixed-length); variable-length commands and reads are
                # flushed by the next command byte or by IDLE.
                want = _PARAMS.get(self.cmd, 'unknown')
                if want == 'unknown' or want == 0 or (want is not None and len(self.params) >= want):
                    self._finish_command()

        elif kind == 'IDLE':
            self._finish_command()

        elif kind == 'BUSY':
            self.busy_wired = True
            if value != _BUSY_LEVEL and self.in_sleep:
                self._warn(ss, es, 'BUSY went low in deep sleep, the datasheet keeps it high until '
                                   'a hardware reset', 'BUSY-DROP')
            if value == _BUSY_LEVEL:
                self.busy_since = ss
                self.busy_until = None
                self.busy_ss = ss
            elif self.busy_ss is not None:
                self.busy_until = ss
                d = ss - self.busy_ss
                self.put(self.busy_ss, ss, self.out_ann,
                         [ANN_STATUS, ['Busy for ' + self._dur(d), 'Busy ' + self._dur(d), 'Busy']])
                self.busy_ss = None

        elif kind == 'RESET':
            if value:
                self.res_ss = ss
                self.in_sleep = False
            elif self.res_ss is not None:
                d = ss - self.res_ss
                self.put(self.res_ss, ss, self.out_ann,
                         [ANN_STATUS, ['RES# low for ' + self._dur(d), 'RES# ' + self._dur(d), 'RES#']])
                self.res_ss = None
