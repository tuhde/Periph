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
    0x04: ('PON power on', True, None),
    0x07: ('DSLP deep sleep', False, None),
    0x11: ('DSP data stop', True, None),
    0x12: ('DRF display refresh', True, None),
    0x40: ('TSC temperature sensor', True, None),
}


ANN_COMMAND = 0
ANN_STATUS  = 1
ANN_WARNING = 2

# Number of parameter bytes each command takes; None = variable length (pixel data).
_PARAMS = {
    0x00: (1, 2),    # PSR: 1 byte in the datasheet's register definition, 2 in the vendor demo (EF 08)
    0x01: (1, 4),    # PWR: first byte documented, the vendor demo writes 4 (37 00 23 23)
    0x02: 0, 0x03: 1, 0x04: 0, 0x06: 3, 0x07: 1,
    0x10: None, 0x11: 0, 0x12: 0, 0x13: 1, 0x30: 1, 0x41: 1, 0x42: 3, 0x50: 1, 0x60: 1,
    0x61: 4, 0x82: 1, 0xE3: 1,
}

# Commands whose data bytes are read back from the chip. SDA is bidirectional: the bytes are the
# chip's output only if the probe sits on the shared DIN/DOUT line of a half-duplex wiring
# (DisplayConnection HALF_DUPLEX_*); on a write-only board they are not meaningful.
_READS = {0x11, 0x40, 0x43, 0x51, 0x71, 0x81}
_READ_LEN = {0x11: 1, 0x40: 2, 0x43: 2, 0x51: 1, 0x71: 1, 0x81: 1}

_NAMES = {
    0x00: 'Panel setting (PSR)', 0x01: 'Power setting (PWR)', 0x02: 'Power off (POF)',
    0x03: 'Power off sequence setting (PFS)', 0x04: 'Power on (PON)', 0x06: 'Booster soft start (BTST)',
    0x07: 'Deep sleep (DSLP)', 0x10: 'Data start transmission 1 (DTM1)', 0x11: 'Data stop (DSP)',
    0x12: 'Display refresh (DRF)', 0x13: 'Image process (IPC)', 0x30: 'PLL control (PLL)',
    0x40: 'Temperature sensor calibration (TSC)', 0x41: 'Temperature sensor enable (TSE)',
    0x42: 'Temperature sensor write (TSW)', 0x43: 'Temperature sensor read (TSR)',
    0x50: 'VCOM and data interval setting (CDI)', 0x51: 'Low power detection (LPD)',
    0x60: 'TCON setting (TCON)', 0x61: 'Resolution setting (TRES)', 0x71: 'Get status (FLG)',
    0x81: 'VCOM value (VV)', 0x82: 'VCOM-DC setting (VDCS)', 0xE3: 'Power saving (PWS)',
}
_SHORT = {
    0x00: 'PSR', 0x01: 'PWR', 0x02: 'POF', 0x03: 'PFS', 0x04: 'PON', 0x06: 'BTST', 0x07: 'DSLP',
    0x10: 'DTM1', 0x11: 'DSP', 0x12: 'DRF', 0x13: 'IPC', 0x30: 'PLL', 0x40: 'TSC', 0x41: 'TSE',
    0x42: 'TSW', 0x43: 'TSR', 0x50: 'CDI', 0x51: 'LPD', 0x60: 'TCON', 0x61: 'TRES', 0x71: 'FLG',
    0x81: 'VV', 0x82: 'VDCS', 0xE3: 'PWS',
}
_COLOURS = ('black', 'white', 'green', 'blue', 'red', 'yellow', 'orange', 'clean')
_COLOURS_DDX0 = ('blue', 'green', 'white', 'black', 'clean', 'orange', 'yellow', 'red')
_FRAME_BYTES = 600 * 448 // 2

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
        s = ('BUSY_N=%d POF=%d PON=%d data_flag=%d I2C_BUSYN=%d I2C_ERR=%d' %
             (b & 1, (b >> 1) & 1, (b >> 2) & 1, (b >> 3) & 1, (b >> 4) & 1, (b >> 5) & 1))
    elif cmd == 0x40:
        s = '%+d C' % _signed(p[0])
    elif cmd == 0x51:
        s = 'LPD=%d (1 = normal)' % (p[0] & 1)
    elif cmd == 0x81:
        c = p[0] & 0x7F
        s = 'VCOM=' + _volts(-0.10 - 0.05 * (c - 2) if 2 <= c <= 0x50 else None)
    elif cmd == 0x11:
        s = 'data_flag=%d' % (p[0] >> 7)
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
    id = 'uc8159'
    name = 'UC8159'
    longname = 'UC8159 e-paper controller'
    desc = ('Decode the command stream of a UC8159 seven-colour EPD controller '
            '(Waveshare 5.65inch e-Paper (F)) from the `display_spi` connection decoder.')
    license = 'gplv2+'
    inputs = ['display_spi']
    outputs = ['uc8159']
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

    def _describe(self, cmd, p):
        """Return (long text, short tag) for a command with its parameter bytes."""
        name = _NAMES.get(cmd, 'Unknown command 0x%02X' % cmd)
        short = _SHORT.get(cmd, '0x%02X' % cmd)
        args = ''
        n = len(p)
        if cmd == 0x00 and n >= 1:
            args = 'gate scan %s, source shift %s, booster %s%s' % (
                'up' if p[0] & 0x08 else 'down', 'right' if p[0] & 0x04 else 'left',
                'on' if p[0] & 0x02 else 'off', '' if p[0] & 0x01 else ', SOFT RESET')
            if n >= 2:
                args += ' (byte 2 = 0x%02X)' % p[1]
        elif cmd == 0x01 and n >= 1:
            args = 'byte 1 = 0x%02X (VDS_EN=%d VDG_EN=%d)%s' % (
                p[0], (p[0] >> 1) & 1, p[0] & 1, ', remaining: ' + _hex(p[1:]) if n > 1 else '')
        elif cmd == 0x03 and n >= 1:
            args = 'VGH/VGL, VSH/VSL off after %d frame(s)' % (((p[0] >> 4) & 3) + 1)
        elif cmd == 0x06 and n >= 3:
            args = 'phaseA=0x%02X phaseB=0x%02X phaseC=0x%02X' % (p[0], p[1], p[2])
        elif cmd == 0x07 and n >= 1:
            args = 'check code OK' if p[0] == 0xA5 else 'BAD check code 0x%02X (needs 0xA5)' % p[0]
        elif cmd == 0x10:
            if n:
                hist = {}
                for b in p:
                    for nib in (b >> 4, b & 0xF):
                        hist[nib] = hist.get(nib, 0) + 1
                top = sorted(hist.items(), key=lambda kv: -kv[1])[:4]
                content = ', ' + ', '.join('%s %d px' % (_COLOURS[c & 7] if c < 8 else '0x%X' % c, k)
                                           for c, k in top)
            else:
                content = ''
            args = '%d bytes (full frame %d = 600 x 448 at 4 bpp)%s' % (n, _FRAME_BYTES, content)
        elif cmd == 0x13 and n >= 1:
            args = '0x%02X' % p[0]
        elif cmd == 0x30 and n >= 1:
            fr = {0x00: 12.5, 0x01: 25, 0x0E: 187.5, 0x0F: 200, 0x39: 200, 0x3A: 100, 0x3C: 50}.get(p[0])
            args = 'code 0x%02X%s' % (p[0], ' (%s Hz)' % fr if fr is not None else '')
        elif cmd == 0x41 and n >= 1:
            args = '%s sensor, offset %+d C' % ('external' if p[0] & 0x80 else 'internal',
                                               (p[0] & 0xF) - 16 if p[0] & 8 else p[0] & 0xF)
        elif cmd == 0x42 and n >= 3:
            args = 'attr=0x%02X msb=0x%02X lsb=0x%02X' % (p[0], p[1], p[2])
        elif cmd == 0x50 and n >= 1:
            ddx = (p[0] >> 4) & 1
            table = _COLOURS if ddx else _COLOURS_DDX0
            args = 'border=%s (VBD=%d) DDX=%d interval=%d hsync' % (
                table[p[0] >> 5], p[0] >> 5, ddx, 17 - (p[0] & 0xF))
        elif cmd == 0x60 and n >= 1:
            args = 'S2G=%d G2S=%d' % (p[0] >> 4, p[0] & 0xF)
        elif cmd == 0x61 and n >= 4:
            args = '%d x %d' % ((p[0] << 8) | p[1], (p[2] << 8) | p[3])
        elif cmd == 0x82 and n >= 1:
            c = p[0] & 0x7F
            args = 'VCOM_DC=%s' % _volts(-0.10 - 0.05 * (c - 2) if 2 <= c <= 0x50 else None)
        elif cmd == 0xE3 and n >= 1:
            args = '0x%02X' % p[0]
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
            self._warn(self.cmd_ss, self.cmd_es, 'Unknown UC8159 command 0x%02X' % cmd, 'CMD?')
        elif not _want_ok(want, len(p)):
            exp = ' or '.join(str(w) for w in want) if isinstance(want, tuple) else want
            self._warn(self.cmd_ss, self.cmd_es,
                       'Command 0x%02X has %d parameter byte(s), expected %s' % (cmd, len(p), exp), 'PARAM?')
        if cmd == 0x10 and len(p) != _FRAME_BYTES:
            self._warn(self.cmd_ss, self.cmd_es,
                       'DTM1 carried %d bytes, a frame is %d' % (len(p), _FRAME_BYTES), 'SIZE?')
        if cmd == 0x07 and p and p[0] != 0xA5:
            self._warn(self.cmd_ss, self.cmd_es, 'Check code must be 0xA5', 'CHK?')

        if cmd == 0x10:
            self.plane_full[cmd] = len(p) >= _FRAME_BYTES
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
            return bool(self.plane_full.get(0x10))
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
