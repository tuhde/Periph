import sigrokdecode as srd

CH_CLK  = 0
CH_MOSI = 1
CH_CS   = 2
CH_DC   = 3
CH_MISO = 4
CH_BUSY = 5
CH_RES  = 6


class ChannelError(Exception):
    pass


ANN_BYTE     = 0
ANN_TRANSFER = 1
ANN_TIMING   = 2
ANN_WARNING  = 3

# Bus-idle time (CS# high, no clock) after which an IDLE packet is sent, so stacked chip decoders
# can report a pending open-ended command: libsigrokdecode has no end-of-capture hook.
_IDLE_S = 10e-3

_MAX_LISTED = 8     # bytes listed in a transfer annotation before "..."


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


class Decoder(srd.Decoder):
    api_version = 3
    id = 'display_spi'
    name = 'Display-SPI'
    longname = 'Display SPI connection (SPI plus D/C, RES, BUSY)'
    desc = ('Frame the SPI stream of a display controller (OLED, e-paper, ...: 4-wire with a D/C# line, or '
            '3-wire 9-bit frames) and report CS# transfers, RES# and BUSY. Chip decoders stack on it.')
    license = 'gplv2+'
    inputs = ['logic']
    outputs = ['display_spi']
    tags = ['Display', 'Embedded/industrial']

    channels = (
        {'id': 'clk',  'name': 'SCK',  'desc': 'SPI clock (SCK / SCL)'},
        {'id': 'mosi', 'name': 'DIN',  'desc': 'Serial data in (MOSI / DIN / SDA); probe the shared '
                                               'DIN/DOUT line for half-duplex wiring'},
        {'id': 'cs',   'name': 'CS#',  'desc': 'Chip select, active low'},
    )
    optional_channels = (
        {'id': 'dc',   'name': 'D/C#', 'desc': 'Data/command select (4-wire mode; low = command)'},
        {'id': 'miso', 'name': 'DOUT', 'desc': 'Separate chip-output line (half-duplex with MISO '
                                               'wired through a resistor)'},
        {'id': 'busy', 'name': 'BUSY', 'desc': "BUSY output (raw level; the polarity is the chip decoder's)"},
        {'id': 'res',  'name': 'RES#', 'desc': 'Reset input, active low'},
    )
    options = (
        {'id': 'wire', 'desc': 'SPI framing', 'default': '4-wire', 'values': ('4-wire', '3-wire')},
    )
    annotations = (
        ('byte',     'Byte'),
        ('transfer', 'Transfer'),
        ('timing',   'Timing'),
        ('warning',  'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_TRANSFER,)),
        ('bytes',    'Bytes',    (ANN_BYTE,)),
        ('timing',   'Timing',   (ANN_TIMING,)),
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
        self.bit_n = 0
        self.cur = 0
        self.cur_miso = 0
        self.cur_dc = 1
        self.dc_moved = False
        self.byte_ss = None
        self.win_ss = None        # start of the current CS# window
        self.win_bytes = []       # [(dc, mosi, miso)] in the current CS# window
        self.win_edges = []       # rising SCK edges in the current CS# window
        self.miso_idle = 0        # DOUT level when CS# was asserted
        self.busy_ss = None       # start of the current BUSY level span
        self.busy_cur = None      # current raw BUSY level
        self.res_ss = None

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)
        self.wire3 = (self.options['wire'] == '3-wire')

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

    def _fmt(self, dc, mosi, miso):
        """`C xx` / `D xx`; with DOUT wired, a value that differs from the line's idle level at CS#
        assertion is appended as the chip's output (`D 00 <- 13`)."""
        s = '%s %02X' % ('C' if dc == 0 else 'D', mosi)
        idle = 0xFF if self.miso_idle else 0x00
        if dc == 1 and miso is not None and miso != idle:
            s += ' \u2190 %02X' % miso
        return s

    def _rate(self):
        """Fastest SCK rate seen in the current window (from consecutive rising edges)."""
        e = self.win_edges
        if not self.samplerate or len(e) < 2:
            return None
        gap = min(b - a for a, b in zip(e, e[1:]))
        return self.samplerate / float(gap) if gap > 0 else None

    def _end_window(self, sn):
        if self.win_ss is None:
            return
        n = len(self.win_bytes)
        if n:
            shown = ' '.join(self._fmt(*b) for b in self.win_bytes[:_MAX_LISTED])
            if n > _MAX_LISTED:
                shown += ' ...(%d)' % n
            rate = self._rate()
            if not rate:
                clk = ''
            elif rate >= 1e6:
                clk = ' @ %.2f MHz' % (rate / 1e6)
            else:
                clk = ' @ %.0f kHz' % (rate / 1e3)
            self.put(self.win_ss, sn, self.out_ann, [ANN_TRANSFER, [
                'CS# window: %d byte(s): %s%s' % (n, shown, clk),
                '%d byte(s)' % n, '%dB' % n]])
        self.win_ss = None
        self.win_bytes = []
        self.win_edges = []

    # ---- main loop --------------------------------------------------------------------

    def decode(self):
        have_dc = self.has_channel(CH_DC)
        have_miso = self.has_channel(CH_MISO)
        have_busy = self.has_channel(CH_BUSY)
        have_res = self.has_channel(CH_RES)

        if not self.wire3 and not have_dc:
            raise ChannelError('4-wire mode needs the D/C# channel; connect it or set wire=3-wire')

        conds = [{CH_CLK: 'r'}, {CH_CS: 'e'}]
        idx_busy = idx_res = None
        if have_busy:
            idx_busy = len(conds)
            conds.append({CH_BUSY: 'e'})
        if have_res:
            idx_res = len(conds)
            conds.append({CH_RES: 'e'})

        bits_per_byte = 9 if self.wire3 else 8
        idle_gap = int(self.samplerate * _IDLE_S) if self.samplerate else 100000
        # Initial levels from sample 0 (a wait() without conditions returns there), not from the
        # first edge: BUSY/RES# may already be active when the capture starts.
        pins = self.wait()
        sn = self.samplenum
        prev_cs = pins[CH_CS]
        self.cs_active = (prev_cs == 0)
        idle_armed = not self.cs_active
        if have_busy:
            self.busy_ss = sn
            self.busy_cur = pins[CH_BUSY]
            self.put(sn, sn + 1, self.out_python, ('BUSY', self.busy_cur))
        if have_res and not pins[CH_RES]:
            self.res_ss = sn
            self.put(sn, sn + 1, self.out_python, ('RESET', True))
        while True:
            idx_idle = None
            wait_conds = conds
            if idle_armed and not self.cs_active:
                idx_idle = len(conds)
                wait_conds = conds + [{'skip': idle_gap}]
            pins = self.wait(wait_conds)
            sn = self.samplenum
            if idx_idle is not None and self.matched[idx_idle]:
                self.put(sn, sn + 1, self.out_python, ('IDLE', None))
                idle_armed = False
                continue
            # BUSY level changes (raw; which level means busy is chip-specific) and RES# (active-low)
            if idx_busy is not None and self.matched[idx_busy]:
                lvl = pins[CH_BUSY]
                if self.busy_ss is not None and self.busy_cur is not None:
                    d = self._dur(sn - self.busy_ss)
                    was = 'high' if self.busy_cur else 'low'
                else:
                    d, was = None, None
                if lvl:
                    s = 'busy_rise: BUSY went high' + (' (was low for %s)' % d if was == 'low' else '')
                    self.put(sn, sn + 1, self.out_ann, [ANN_TIMING, [s, 'busy_rise', '\u2191BSY']])
                else:
                    s = 'busy_fall: BUSY went low' + (' (was high for %s)' % d if was == 'high' else '')
                    self.put(sn, sn + 1, self.out_ann, [ANN_TIMING, [s, 'busy_fall', '\u2193BSY']])
                self.busy_ss = sn
                self.busy_cur = lvl
                self.put(sn, sn + 1, self.out_python, ('BUSY', lvl))
            if idx_res is not None and self.matched[idx_res]:
                if not pins[CH_RES]:
                    self.res_ss = sn
                    self.put(sn, sn + 1, self.out_ann, [ANN_TIMING,
                             ['res_fall: RES# went low', 'res_fall', '\u2193RES']])
                    self.put(sn, sn + 1, self.out_python, ('RESET', True))
                elif self.res_ss is not None:
                    d = sn - self.res_ss
                    self.put(sn, sn + 1, self.out_ann, [ANN_TIMING,
                             ['res_rise: RES# released after ' + self._dur(d), 'res_rise', '\u2191RES']])
                    self.put(sn, sn + 1, self.out_python, ('RESET', False))
                    self.res_ss = None

            # CS# edges
            if self.matched[1]:
                cs = pins[CH_CS]
                if cs == 0 and prev_cs == 1:        # asserted
                    self.cs_active = True
                    idle_armed = False
                    self.bit_n = 0
                    self.cur = 0
                    self.win_ss = sn
                    self.win_bytes = []
                    self.win_edges = []
                    if have_miso:
                        self.miso_idle = pins[CH_MISO]
                    self.put(sn, sn + 1, self.out_python, ('CS', True))
                elif cs == 1 and prev_cs == 0:      # released
                    self.cs_active = False
                    idle_armed = True
                    if self.bit_n:
                        self._warn(self.byte_ss or sn, sn,
                                   'CS# released after %d bit(s), expected multiples of %d' %
                                   (self.bit_n, bits_per_byte), 'BITS?')
                    self.bit_n = 0
                    self.cur = 0
                    self._end_window(sn)
                    self.put(sn, sn + 1, self.out_python, ('CS', False))
                prev_cs = cs

            # SCK rising edge: sample DIN (and DOUT / D/C#) while CS# is low
            if self.matched[0] and self.cs_active:
                if self.win_ss is not None:
                    self.win_edges.append(sn)
                if self.bit_n == 0:
                    self.byte_ss = sn
                    self.cur = 0
                    self.cur_miso = 0
                    self.dc_moved = False
                    if not self.wire3:
                        self.cur_dc = pins[CH_DC]
                elif not self.wire3 and pins[CH_DC] != self.cur_dc:
                    self.dc_moved = True
                bit = pins[CH_MOSI]
                if self.wire3 and self.bit_n == 0:
                    self.cur_dc = bit               # first bit of a 9-bit frame is D/C
                else:
                    self.cur = (self.cur << 1) | bit
                    if have_miso:
                        self.cur_miso = (self.cur_miso << 1) | pins[CH_MISO]
                self.bit_n += 1
                if self.bit_n == bits_per_byte:
                    value, dc = self.cur, self.cur_dc
                    miso = self.cur_miso if have_miso else None
                    self.put(self.byte_ss, sn, self.out_ann, [ANN_BYTE, [
                        self._fmt(dc, value, miso), '%s %02X' % ('C' if dc == 0 else 'D', value),
                        '%02X' % value]])
                    if self.dc_moved:
                        self._warn(self.byte_ss, sn, 'D/C# changed inside a byte', 'DC?')
                    self.win_bytes.append((dc, value, miso))
                    self.put(self.byte_ss, sn, self.out_python, ('BYTE', (dc, value, miso)))
                    self.bit_n = 0
                    self.cur = 0
