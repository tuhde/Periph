import sigrokdecode as srd

ANN_INSTR   = 0
ANN_DATA    = 1
ANN_STATUS  = 2
ANN_WARNING = 3

T_INSTR_US = 26.3        # minimum time between two bytes (350 kHz oscillator)
T_CLEAR_MS = 1.08        # Clear Display / Return Home
T_POWERUP_MS = 40.0      # after VDD stable / RESET released

_RAB = ('1', '1.25', '1.5', '1.8', '2', '2.5', '3', '3.75')


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


def _char(code):
    if 0x20 <= code <= 0x7D:
        return chr(code)
    if code < 0x10:
        return '<%d>' % (code & 7)       # CGRAM character
    return '\\x%02X' % code


def _glyph(rows):
    return ' / '.join(''.join('#' if (r >> (4 - i)) & 1 else '.' for i in range(5)) for r in rows)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'st7036'
    name = 'ST7036'
    longname = 'Sitronix ST7036 character LCD controller'
    desc = ('Decode the instruction and data stream of an ST7036 character LCD controller '
            '(e.g. EA DOGM162, 4-wire serial) from the `display_spi` connection decoder.')
    license = 'gplv2+'
    inputs = ['display_spi']
    outputs = ['st7036']
    tags = ['Display', 'IC']

    options = (
        {'id': 'lines', 'desc': 'Display lines (1, 2 or 3)', 'default': 2, 'values': (1, 2, 3)},
        {'id': 'columns', 'desc': 'Characters per line', 'default': 16},
    )
    annotations = (
        ('instruction', 'Instruction'),
        ('data', 'Data'),
        ('status', 'Status'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_INSTR, ANN_DATA)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann and data[0] != ANN_WARNING:
            data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.samplerate = None
        self.res_ss = None
        self.res_rise = None
        self.last_es = None            # end of the previous byte
        self.need_ms = 0.0             # wait the previous byte demanded
        self.need_what = ''
        self.seen_byte = False
        self.table = 0                 # IS[2:1]
        self.n = 0
        self.dh = 0
        self.ram = 'DDRAM'             # RAM the data bytes go to
        self.addr = 0
        self.inc = 1                   # I/D
        self.run_ss = self.run_es = None
        self.run = bytearray()
        self.run_addr = 0
        self.run_ram = 'DDRAM'
        self.c_hi = 0                  # C5..C4 from the Power/ICON byte
        self.c_lo = 0                  # C3..C0
        self.rab = 2
        self.fon = 0
        self.bon = 0

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)
        self.lines = int(self.options['lines'])
        self.columns = int(self.options['columns'])

    # ---- helpers ---------------------------------------------------------------------

    def _s(self, samples):
        return samples / float(self.samplerate) if self.samplerate else None

    def _dur(self, samples):
        t = self._s(samples)
        if t is None:
            return '%d samples' % samples
        if t >= 1.0:
            return '%.2f s' % t
        if t >= 1e-3:
            return '%.2f ms' % (t * 1e3)
        return '%.1f us' % (t * 1e6)

    def _warn(self, ss, es, text, tag):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [text, tag]])

    def _row_col(self, addr):
        """Visible (row, col) of a DDRAM address, or None when outside the window."""
        if self.lines == 1:
            return (0, addr) if addr < self.columns else None
        starts = (0x00, 0x40) if self.lines == 2 else (0x00, 0x10, 0x20)
        for row, s in enumerate(starts):
            if s <= addr < s + self.columns:
                return row, addr - s
        return None

    def _contrast(self):
        return (self.c_hi << 4) | self.c_lo

    # ---- instruction decoding -----------------------------------------------------------

    def _decode_instr(self, b):
        """Return (text, short, wait_ms)."""
        t = self.table
        if b == 0x01:
            return 'Clear Display', 'CLEAR', T_CLEAR_MS
        if b in (0x02, 0x03):
            return 'Return Home', 'HOME', T_CLEAR_MS
        if 0x04 <= b <= 0x07:
            self.inc = 1 if b & 2 else -1
            return 'Entry Mode Set: cursor %s, display shift %s' % (
                'right (I/D=1)' if b & 2 else 'left (I/D=0)', 'on' if b & 1 else 'off'), 'ENTRY', 0
        if 0x08 <= b <= 0x0F:
            return 'Display ON/OFF: display %s, cursor %s, blink %s' % (
                'on' if b & 4 else 'off', 'on' if b & 2 else 'off', 'on' if b & 1 else 'off'), 'DISP', 0
        if 0x20 <= b <= 0x3F:
            self.table = ((b >> 1) & 1) << 1 | (b & 1)
            self.n = (b >> 3) & 1
            self.dh = (b >> 2) & 1
            note = ''
            if self.table == 3:
                note = ' [reserved table]'
            return 'Function Set: %s bus, %s, %s font, instruction table %d%s' % (
                '8-bit' if b & 0x10 else '4-bit', '2/3 lines' if self.n else '1 line',
                'double height' if self.dh else 'normal', self.table, note), 'FUNC', 0
        if b >= 0x80:
            self.ram = 'DDRAM'
            self.addr = b & 0x7F
            rc = self._row_col(self.addr)
            return 'Set DDRAM Address 0x%02X%s' % (
                self.addr, ' (row %d, col %d)' % rc if rc else ' (outside the visible window)'), 'DDRAM', 0
        if t == 0:
            if 0x10 <= b <= 0x1F:
                sc, rl = (b >> 3) & 1, (b >> 2) & 1
                return 'Cursor/Display Shift: %s %s' % ('display' if sc else 'cursor',
                                                        'right' if rl else 'left'), 'SHIFT', 0
            if 0x40 <= b <= 0x7F:
                self.ram = 'CGRAM'
                self.addr = b & 0x3F
                return 'Set CGRAM Address 0x%02X (char %d, row %d)' % (self.addr, self.addr >> 3, self.addr & 7), 'CGRAM', 0
        elif t == 1:
            if 0x10 <= b <= 0x1F:
                return 'Bias Set: %s bias, FX=%d' % ('1/4' if b & 8 else '1/5', b & 1), 'BIAS', 0
            if 0x40 <= b <= 0x4F:
                self.ram = 'ICON'
                self.addr = b & 0x0F
                return 'Set ICON Address 0x%X' % self.addr, 'ICONADR', 0
            if 0x50 <= b <= 0x5F:
                self.c_hi = b & 3
                self.bon = (b >> 2) & 1
                return 'Power/ICON/Contrast high: icon %s, booster %s, C5C4=%d (contrast now %d)' % (
                    'on' if b & 8 else 'off', 'on' if b & 4 else 'off', self.c_hi, self._contrast()), 'PWR', 0
            if 0x60 <= b <= 0x6F:
                self.fon = (b >> 3) & 1
                self.rab = b & 7
                return 'Follower Control: follower %s, 1+Rb/Ra = %s' % (
                    'on' if b & 8 else 'off', _RAB[self.rab]), 'FOLLOW', 0
            if 0x70 <= b <= 0x7F:
                self.c_lo = b & 0xF
                return 'Contrast Set low: C3..C0=%d (contrast now %d of 63)' % (self.c_lo, self._contrast()), 'CONTR', 0
        elif t == 2:
            if 0x10 <= b <= 0x1F:
                return 'Double Height Position Select: %s' % (
                    'COM1-16' if b & 8 else 'COM9-24'), 'DHPOS', 0
        return 'Reserved/unknown instruction 0x%02X in table %d' % (b, t), 'RSVD', 0

    # ---- data runs ----------------------------------------------------------------------

    def _finish_run(self):
        if not self.run:
            return
        data = bytes(self.run)
        a = self.run_addr
        if self.run_ram == 'DDRAM':
            text = ''.join(_char(c) for c in data)
            rc = self._row_col(a)
            where = 'row %d col %d' % rc if rc else 'outside the visible window'
            long = 'Text "%s" at DDRAM 0x%02X (%s)' % (text, a, where)
            self.put(self.run_ss, self.run_es, self.out_ann, [ANN_DATA, [long, '"%s"' % text, text]])
            if rc is None:
                self._warn(self.run_ss, self.run_es, 'DDRAM write at 0x%02X outside the visible window' % a, 'OUT?')
        elif self.run_ram == 'CGRAM':
            chars = []
            for i in range(0, len(data), 8):
                chunk = data[i:i + 8]
                chars.append('char %d: %s' % (((a + i) >> 3) & 7, _glyph(chunk)))
            long = 'CGRAM write at 0x%02X: %s' % (a, '; '.join(chars))
            self.put(self.run_ss, self.run_es, self.out_ann, [ANN_DATA, [long, 'CGRAM %d byte(s)' % len(data), 'CGRAM']])
        else:
            long = 'ICON RAM write at 0x%X: %s' % (a, ' '.join('%02X' % c for c in data))
            self.put(self.run_ss, self.run_es, self.out_ann, [ANN_DATA, [long, 'ICON', 'ICON']])
        self.put(self.run_ss, self.run_es, self.out_python, ('DATA', (self.run_ram, a, data)))
        self.run = bytearray()

    # ---- bytes --------------------------------------------------------------------------

    def _timing(self, ss, es):
        if self.samplerate is None:
            return
        if self.last_es is not None:
            gap = self._s(ss - self.last_es)
            if gap < T_INSTR_US * 1e-6:
                self._warn(ss, es, 'Byte only %.1f us after the previous one (min %.1f us)' % (
                    gap * 1e6, T_INSTR_US), 'FAST?')
            if self.need_ms and gap < self.need_ms * 1e-3:
                self._warn(ss, es, 'Byte only %.2f ms after %s (min %.2f ms)' % (
                    gap * 1e3, self.need_what, self.need_ms), 'WAIT?')
        if not self.seen_byte and self.res_rise is not None:
            dt = self._s(ss - self.res_rise)
            if dt < T_POWERUP_MS * 1e-3:
                self._warn(ss, es, 'First byte only %.1f ms after RESET (min %.0f ms)' % (
                    dt * 1e3, T_POWERUP_MS), 'PWRUP?')

    def _byte_done(self, ss, es, value, rs):
        self._timing(ss, es)
        self.seen_byte = True
        self.last_es = es
        if rs == 1:
            if not self.run or self.run_ram != self.ram:
                self._finish_run()
                self.run_ss = ss
                self.run_addr = self.addr
                self.run_ram = self.ram
            self.run_es = es
            self.run.append(value)
            self.addr = (self.addr + self.inc) & 0x7F if self.ram == 'DDRAM' else (self.addr + self.inc) & 0x3F
            self.need_ms = 0.0
            return
        self._finish_run()
        text, short, wait = self._decode_instr(value)
        self.need_ms = wait
        self.need_what = text.split(':')[0]
        self.put(ss, es, self.out_ann, [ANN_INSTR, ['0x%02X %s' % (value, text), '0x%02X %s' % (value, short), short]])
        self.put(ss, es, self.out_python, ('INSTR', (value, text)))
        if text.startswith('Reserved'):
            self._warn(ss, es, text, 'RSVD?')

    # ---- packets from display_spi -----------------------------------------------------

    def decode(self, ss, es, data):
        kind, value = data
        if kind == 'BYTE':
            rs, mosi, _miso = value
            self._byte_done(ss, es, mosi, rs)
        elif kind == 'IDLE':
            self._finish_run()
        elif kind == 'RESET':
            if value:
                self.res_ss = ss
            elif self.res_ss is not None:
                d = ss - self.res_ss
                self.put(self.res_ss, ss, self.out_ann,
                         [ANN_STATUS, ['RESET low for ' + self._dur(d), 'RESET ' + self._dur(d), 'RESET']])
                self.res_ss = None
                self.res_rise = ss
                self.seen_byte = False
