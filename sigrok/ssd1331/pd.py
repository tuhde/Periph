import sigrokdecode as srd

ANN_COMMAND = 0
ANN_PIXELS  = 1
ANN_STATUS  = 2
ANN_WARNING = 3

WIDTH = 96
HEIGHT = 64

# Command byte -> (name, short tag, number of parameter bytes).
# The SSD1331 receives its parameters with D/C# low, exactly like command bytes: a parameter is
# recognised only by the count the command expects. Only D/C# high carries pixel data.
_CMDS = {
    0x15: ('Set column address', 'COL', 2),
    0x75: ('Set row address', 'ROW', 2),
    0x81: ('Set contrast, colour A (blue)', 'CONTA', 1),
    0x82: ('Set contrast, colour B (green)', 'CONTB', 1),
    0x83: ('Set contrast, colour C (red)', 'CONTC', 1),
    0x87: ('Master current control', 'MCUR', 1),
    0x8A: ('Second pre-charge speed, colour A', 'PRECA', 1),
    0x8B: ('Second pre-charge speed, colour B', 'PRECB', 1),
    0x8C: ('Second pre-charge speed, colour C', 'PRECC', 1),
    0xA0: ('Set remap and colour depth', 'REMAP', 1),
    0xA1: ('Set display start line', 'STLINE', 1),
    0xA2: ('Set display offset', 'OFFSET', 1),
    0xA4: ('Normal display', 'NORMAL', 0),
    0xA5: ('Entire display on (all pixels at GS63)', 'ALLON', 0),
    0xA6: ('Entire display off (all pixels at GS0)', 'ALLOFF', 0),
    0xA7: ('Inverse display', 'INV', 0),
    0xA8: ('Set multiplex ratio', 'MUX', 1),
    0xAB: ('Dim mode setting', 'DIMSET', 5),
    0xAC: ('Display on, dim mode', 'DIMON', 0),
    0xAD: ('Set master configuration', 'MCFG', 1),
    0xAE: ('Display off (sleep)', 'OFF', 0),
    0xAF: ('Display on', 'ON', 0),
    0xB0: ('Power save mode', 'PSAVE', 1),
    0xB1: ('Phase 1 and 2 period adjustment', 'PHASE', 1),
    0xB3: ('Display clock divide ratio / oscillator frequency', 'CLKDIV', 1),
    0xB8: ('Set gray scale table', 'GRAY', 32),
    0xB9: ('Enable linear gray scale table', 'LINGRAY', 0),
    0xBB: ('Set pre-charge level', 'PRELVL', 1),
    0xBE: ('Set VCOMH voltage', 'VCOMH', 1),
    0xBC: ('NOP', 'NOP', 0),
    0xBD: ('NOP', 'NOP', 0),
    0xE3: ('NOP', 'NOP', 0),
    0xFD: ('Set command lock', 'LOCK', 1),
    0x21: ('Draw line', 'LINE', 7),
    0x22: ('Draw rectangle', 'RECT', 10),
    0x23: ('Copy', 'COPY', 6),
    0x24: ('Dim window', 'DIMWIN', 4),
    0x25: ('Clear window', 'CLEAR', 4),
    0x26: ('Fill enable / disable (rectangle, copy)', 'FILL', 1),
    0x27: ('Continuous horizontal and vertical scrolling setup', 'SCRSET', 5),
    0x2E: ('Deactivate scrolling', 'SCROFF', 0),
    0x2F: ('Activate scrolling', 'SCRON', 0),
}

_DEPTH = {0: '256 colours (RGB 3-3-2)', 1: '65k colours (RGB 5-6-5)', 2: '65k colours, format 2 (RGB 5-6-5)'}


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


def _hex(bs, limit=10):
    s = ' '.join('%02X' % b for b in bs[:limit])
    return s + (' ...(%d)' % len(bs) if len(bs) > limit else '')


def _vcomh(code):
    """VCOMH / VCC for A[5:1] = code: linear between the datasheet's table points."""
    pts = ((0, 0.44), (8, 0.52), (16, 0.61), (24, 0.71), (31, 0.83))
    code = min(code, 31)
    for (c0, v0), (c1, v1) in zip(pts, pts[1:]):
        if c0 <= code <= c1:
            return v0 + (v1 - v0) * (code - c0) / float(c1 - c0)
    return pts[-1][1]


def _rgb565(v):
    r = (v >> 11) & 0x1F
    g = (v >> 5) & 0x3F
    b = v & 0x1F
    return '#%02X%02X%02X' % ((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2))


def _rgb332(v):
    r = (v >> 5) & 7
    g = (v >> 2) & 7
    b = v & 3
    return '#%02X%02X%02X' % (r * 255 // 7, g * 255 // 7, b * 255 // 3)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'ssd1331'
    name = 'SSD1331'
    longname = 'Solomon Systech SSD1331 RGB OLED controller'
    desc = ('Decode the command stream of an SSD1331 96x64 RGB OLED controller '
            '(e.g. Waveshare 0.95inch RGB OLED) from the `display_spi` connection decoder.')
    license = 'gplv2+'
    inputs = ['display_spi']
    outputs = ['ssd1331']
    tags = ['Display', 'IC']

    annotations = (
        ('command', 'Command'),
        ('pixels',  'Pixel data'),
        ('status',  'Status'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_COMMAND, ANN_PIXELS)),
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
        self.cmd = None
        self.cmd_ss = None
        self.cmd_es = None
        self.params = bytearray()
        self.pix_ss = None
        self.pix_es = None
        self.pixels = bytearray()
        self.res_ss = None
        self.col = (0, WIDTH - 1)
        self.row = (0, HEIGHT - 1)
        self.remap = 0x40            # power-on value: 65k colours, no remap
        self.fill = False

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

    def _bytes_per_pixel(self):
        return 1 if (self.remap >> 6) == 0 else 2

    # ---- command descriptions ---------------------------------------------------------

    def _describe(self, cmd, p):
        name, short, want = _CMDS[cmd]
        n = len(p)
        a = ''
        if n < want:
            a = 'incomplete: %d of %d parameter byte(s) (%s)' % (n, want, _hex(p))
        elif cmd == 0x15 or cmd == 0x75:
            a = '%d .. %d%s' % (p[0], p[1], ' [start > end]' if p[0] > p[1] else '')
        elif cmd in (0x81, 0x82, 0x83):
            a = '%d / 255' % p[0]
        elif cmd == 0x87:
            a = '%d / 16 of the maximum current' % ((p[0] & 0xF) + 1)
        elif cmd in (0x8A, 0x8B, 0x8C):
            a = '%d' % p[0]
        elif cmd == 0xA0:
            v = p[0]
            a = '%s, %s increment, column %s, colour order %s, COM left-right swap %s, COM scan %s, COM split %s' % (
                _DEPTH.get(v >> 6, 'reserved depth'), 'vertical' if v & 1 else 'horizontal',
                'remapped (95 -> 0)' if v & 2 else 'normal', 'C-B-A (BGR)' if v & 4 else 'A-B-C (RGB)',
                'on' if v & 8 else 'off', 'reversed (N-1 -> 0)' if v & 0x10 else 'normal',
                'odd/even' if v & 0x20 else 'sequential')
        elif cmd in (0xA1, 0xA2):
            a = '%d' % p[0]
        elif cmd == 0xA8:
            a = '%d MUX (%d rows)' % ((p[0] & 0x3F) + 1, (p[0] & 0x3F) + 1)
        elif cmd == 0xAB:
            a = 'reserved=0x%02X A=%d B=%d C=%d precharge=0x%02X' % (p[0], p[1], p[2], p[3], p[4])
        elif cmd == 0xAD:
            a = 'master configuration 0x%02X (%s)' % (p[0], 'external VCC' if p[0] == 0x8E else
                                                       'reserved' if p[0] == 0x8F else 'unexpected value')
        elif cmd == 0xB0:
            a = '0x%02X (%s)' % (p[0], 'power save on' if p[0] == 0x1A else
                                 'power save off' if p[0] == 0x0B else 'not 0x1A / 0x0B')
        elif cmd == 0xB1:
            a = 'phase 1 = %d DCLK, phase 2 = %d DCLK' % (p[0] & 0xF, p[0] >> 4)
        elif cmd == 0xB3:
            a = 'divide ratio %d, oscillator frequency setting %d' % ((p[0] & 0xF) + 1, p[0] >> 4)
        elif cmd == 0xB8:
            a = 'GS1..GS32 = %s' % _hex(p, 8)
        elif cmd == 0xBB:
            a = '0x%02X (~%.2f x VCC)' % (p[0], 0.10 + 0.40 * ((p[0] >> 1) & 0x1F) / 31.0)
        elif cmd == 0xFD:
            a = '0x%02X (%s)' % (p[0], 'unlock' if p[0] == 0x12 else 'lock' if p[0] == 0x16 else 'unexpected value')
        elif cmd == 0xBE:
            a = '0x%02X (~%.2f x VCC)' % (p[0], _vcomh(p[0] >> 1))
        elif cmd == 0x21:
            a = '(%d,%d) -> (%d,%d), colour C=%d B=%d A=%d' % (p[0], p[1], p[2], p[3], p[4], p[5], p[6])
        elif cmd == 0x22:
            a = '(%d,%d) -> (%d,%d), line C=%d B=%d A=%d, fill C=%d B=%d A=%d' % tuple(p[:10])
        elif cmd == 0x23:
            a = '(%d,%d)-(%d,%d) to (%d,%d)' % tuple(p[:6])
        elif cmd in (0x24, 0x25):
            a = '(%d,%d) -> (%d,%d)' % tuple(p[:4])
        elif cmd == 0x26:
            a = 'fill %s, copy %s' % ('on' if p[0] & 1 else 'off', 'reversed' if p[0] & 0x10 else 'normal')
        elif cmd == 0x27:
            a = 'horizontal %d, start row %d, rows %d, vertical %d, interval %s' % (
                p[0], p[1], p[2], p[3], ('6', '10', '100', '200')[p[4] & 3])
        elif n:
            a = _hex(p)
        text = '0x%02X %s' % (cmd, name) + (': ' + a if a else '')
        return text, short

    def _finish_command(self):
        if self.cmd is None:
            return
        cmd, p = self.cmd, bytes(self.params)
        text, short = self._describe(cmd, p)
        want = _CMDS[cmd][2]
        self.put(self.cmd_ss, self.cmd_es, self.out_ann, [ANN_COMMAND, [text, '0x%02X %s' % (cmd, short), short]])
        self.put(self.cmd_ss, self.cmd_es, self.out_python, ('COMMAND', (cmd, p)))
        if len(p) < want:
            self._warn(self.cmd_ss, self.cmd_es,
                       'Command 0x%02X has %d of %d parameter byte(s)' % (cmd, len(p), want), 'PARAM?')
        elif len(p) >= want:
            if cmd == 0x15:
                self.col = (p[0], p[1])
                if p[0] > p[1] or p[1] >= WIDTH:
                    self._warn(self.cmd_ss, self.cmd_es, 'Column address %d..%d outside 0..%d' % (
                        p[0], p[1], WIDTH - 1), 'COL?')
            elif cmd == 0x75:
                self.row = (p[0], p[1])
                if p[0] > p[1] or p[1] >= HEIGHT:
                    self._warn(self.cmd_ss, self.cmd_es, 'Row address %d..%d outside 0..%d' % (
                        p[0], p[1], HEIGHT - 1), 'ROW?')
            elif cmd == 0xA0:
                self.remap = p[0]
            elif cmd == 0x26:
                self.fill = bool(p[0] & 1)
            elif cmd in (0x21, 0x22, 0x23, 0x24, 0x25):
                xs = (p[0], p[2]) if cmd != 0x23 else (p[0], p[2], p[4])
                ys = (p[1], p[3]) if cmd != 0x23 else (p[1], p[3], p[5])
                if any(x >= WIDTH for x in xs) or any(y >= HEIGHT for y in ys):
                    self._warn(self.cmd_ss, self.cmd_es,
                               'Graphics command 0x%02X coordinates outside 96 x 64' % cmd, 'COORD?')
        self.cmd = None
        self.params = bytearray()

    def _finish_pixels(self):
        if self.pix_ss is None:
            return
        n = len(self.pixels)
        w = self.col[1] - self.col[0] + 1
        h = self.row[1] - self.row[0] + 1
        bpp = self._bytes_per_pixel()
        px = n // bpp
        if bpp == 2:
            words = [(self.pixels[i] << 8) | self.pixels[i + 1] for i in range(0, n - 1, 2)]
            conv = _rgb565
        else:
            words = list(self.pixels)
            conv = _rgb332
        hist = {}
        for v in words:
            hist[v] = hist.get(v, 0) + 1
        top = sorted(hist.items(), key=lambda kv: -kv[1])[:3]
        colours = ', '.join('%s x%d' % (conv(v), k) for v, k in top)
        text = 'Pixel data: %d bytes = %d px at %d byte(s) per pixel, window %d x %d (%d px); %d distinct: %s' % (
            n, px, bpp, w, h, w * h, len(hist), colours)
        self.put(self.pix_ss, self.pix_es, self.out_ann,
                 [ANN_PIXELS, [text, 'Pixels %d bytes' % n, 'Pixels']])
        self.put(self.pix_ss, self.pix_es, self.out_python, ('PIXELS', bytes(self.pixels)))
        if n % bpp:
            self._warn(self.pix_ss, self.pix_es, 'Pixel data is %d bytes, not a multiple of %d' % (n, bpp), 'ODD?')
        if px > w * h > 0:
            self._warn(self.pix_ss, self.pix_es,
                       '%d pixels exceed the %d x %d window (the write pointer wraps)' % (px, w, h), 'SIZE?')
        self.pix_ss = None
        self.pixels = bytearray()

    # ---- bytes ------------------------------------------------------------------------

    def _byte_done(self, ss, es, value, dc):
        if dc == 1:
            self._finish_command()
            if self.pix_ss is None:
                self.pix_ss = ss
            self.pix_es = es
            self.pixels.append(value)
            return
        self._finish_pixels()
        # D/C# low: a parameter of the current command, or a new command byte.
        if self.cmd is not None and len(self.params) < _CMDS[self.cmd][2]:
            self.params.append(value)
            self.cmd_es = es
            if len(self.params) == _CMDS[self.cmd][2]:
                self._finish_command()
            return
        self._finish_command()
        if value not in _CMDS:
            self._warn(ss, es, 'Unknown SSD1331 command 0x%02X' % value, 'CMD?')
            self.put(ss, es, self.out_ann, [ANN_COMMAND, ['0x%02X unknown command' % value, '0x%02X' % value]])
            return
        self.cmd = value
        self.cmd_ss = ss
        self.cmd_es = es
        self.params = bytearray()
        if _CMDS[value][2] == 0:
            self._finish_command()

    # ---- packets from display_spi -----------------------------------------------------

    def decode(self, ss, es, data):
        kind, value = data
        if kind == 'BYTE':
            dc, mosi, _miso = value
            self._byte_done(ss, es, mosi, dc)
        elif kind == 'IDLE':
            self._finish_command()
            self._finish_pixels()
        elif kind == 'RESET':
            if value:
                self.res_ss = ss
            elif self.res_ss is not None:
                d = ss - self.res_ss
                self.put(self.res_ss, ss, self.out_ann,
                         [ANN_STATUS, ['RES# low for ' + self._dur(d), 'RES# ' + self._dur(d), 'RES#']])
                self.res_ss = None
