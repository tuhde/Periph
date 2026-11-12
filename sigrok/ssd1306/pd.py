import sigrokdecode as srd

ADDRS = (0x3C, 0x3D)
COLUMNS = 128

ANN_CTRL    = 0
ANN_CMD     = 1
ANN_DATA    = 2
ANN_READ    = 3
ANN_STATUS  = 4
ANN_WARNING = 5

MODES = {0: 'horizontal', 1: 'vertical', 2: 'page'}
INTERVALS = {0: '5 frames', 1: '64 frames', 2: '128 frames', 3: '256 frames', 4: '3 frames', 5: '4 frames',
             6: '25 frames', 7: '2 frames'}


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


def _warn_tag(msg):
    m = msg.lower()
    if 'argument' in m or 'missing' in m:
        return 'ARG?'
    if 'not in the ssd1306' in m or 'unknown' in m:
        return 'BAD?'
    if 'column' in m:
        return 'COL?'
    return 'WARN'


_FIXED = {
    0x20: (1, 'Set memory addressing mode'), 0x21: (2, 'Set column address range'),
    0x22: (2, 'Set page address range'), 0x26: (6, 'Right horizontal scroll setup'),
    0x27: (6, 'Left horizontal scroll setup'), 0x29: (5, 'Vertical and right horizontal scroll setup'),
    0x2A: (5, 'Vertical and left horizontal scroll setup'), 0x2E: (0, 'Deactivate scroll'),
    0x2F: (0, 'Activate scroll'), 0x81: (1, 'Set contrast'), 0x8D: (1, 'Charge pump setting'),
    0xA0: (0, 'Segment re-map'), 0xA1: (0, 'Segment re-map'), 0xA3: (2, 'Set vertical scroll area'),
    0xA4: (0, 'Entire display on'), 0xA5: (0, 'Entire display on'), 0xA6: (0, 'Normal / reverse display'),
    0xA7: (0, 'Normal / reverse display'), 0xA8: (1, 'Set multiplex ratio'), 0xAE: (0, 'Display off / on'),
    0xAF: (0, 'Display off / on'), 0xC0: (0, 'COM scan direction'), 0xC8: (0, 'COM scan direction'),
    0xD3: (1, 'Set display offset'), 0xD5: (1, 'Set clock divide / oscillator'),
    0xD9: (1, 'Set pre-charge period'), 0xDA: (1, 'Set COM pins configuration'), 0xDB: (1, 'Set VCOMH deselect level'),
    0xE3: (0, 'NOP'),
}


def cmd_info(code):
    """Return (argument bytes needed, name) for a command byte, or None if it is not an SSD1306 command."""
    if code <= 0x0F:
        return 0, 'Set lower column address'
    if code <= 0x1F:
        return 0, 'Set higher column address'
    if 0x40 <= code <= 0x7F:
        return 0, 'Set display start line'
    if 0xB0 <= code <= 0xB7:
        return 0, 'Set page address'
    return _FIXED.get(code)


# Commands of the SH1106 / SSD1305 siblings that do not exist on the SSD1306 (named in the warning for a quicker diagnosis).
SIBLING_ONLY = {0x82: 'brightness (SSD1305)', 0x91: 'look-up table (SSD1305)', 0x92: 'bank colour (SSD1305)',
                0x93: 'bank colour (SSD1305)', 0xAB: 'dim mode (SSD1305)', 0xAC: 'dim display on (SSD1305)',
                0xAD: 'master configuration / DC-DC (SSD1305, SH1106)', 0xD8: 'area colour (SSD1305)',
                0xE0: 'read-modify-write (SSD1305, SH1106)', 0xEE: 'end read-modify-write (SSD1305, SH1106)'}


class _Core:
    """Command table, RAM tracker and annotations shared by the I2C and SPI decoders."""

    def put(self, ss, es, out, data):
        if out == self.out_ann and data[0] != ANN_WARNING:
            data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def reset(self):
        self.state = 'IDLE'
        self.is_read = False
        self.ss_block = None
        self.expect_ctrl = True
        self.co = 0
        self.dc = 0              # D/C of the last control byte (0 command, 1 data)
        self.last_dc = 0         # D/C of the last write: decides what a read returns
        self.ctrl_first = True
        self.cmd_count = 0
        self.pend = None         # [code, need, args, ss]
        self.run = bytearray()
        self.run_ss = None
        self.run_es = None
        self.run_pos = None
        self.page = 0
        self.col = 0
        self.dummy = False
        self.read_first = True
        self.display_on = None
        self.in_rmw = False
        self.mode = 2            # addressing mode: 0 horizontal, 1 vertical, 2 page (reset)
        self.col_start, self.col_end = 0, COLUMNS - 1
        self.page_start, self.page_end = 0, 7
        self.scrolling = False
        self.pump = False
        self.res_ss = None
        self.run_pos_seen = False
        self.ctrl_first_seen = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    # ---- helpers ---------------------------------------------------------------------

    def _ann(self, ss, es, cls, *strings):
        self.put(ss, es, self.out_ann, [cls, list(strings)])

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _py(self, ss, es, kind, value):
        super().put(ss, es, self.out_python, (kind, value))

    # ---- command decoding -------------------------------------------------------------

    def _decode_cmd(self, code, args):
        n = code
        a = args
        if n <= 0x0F:
            self.col = (self.col & 0xF0) | (n & 0x0F)
            return 'Set lower column address 0x%X' % (n & 0x0F)
        if n <= 0x1F:
            self.col = (self.col & 0x0F) | ((n & 0x0F) << 4)
            return 'Set higher column address 0x%X (column %d)' % (n & 0x0F, self.col)
        if n == 0x20:
            if a:
                self.mode = a[0] & 3
                return 'Set memory addressing mode: %s' % MODES.get(a[0] & 3, 'invalid')
            return 'Set memory addressing mode'
        if n == 0x21:
            if len(a) == 2:
                self.col_start, self.col_end = a[0], a[1]
                self.col = a[0]
                return 'Set column address range %d..%d' % (a[0], a[1])
            return 'Set column address range'
        if n == 0x22:
            if len(a) == 2:
                self.page_start, self.page_end = a[0] & 7, a[1] & 7
                self.page = a[0] & 7
                return 'Set page address range %d..%d' % (a[0] & 7, a[1] & 7)
            return 'Set page address range'
        if n in (0x26, 0x27):
            d = 'right' if n == 0x26 else 'left'
            if len(a) == 6:
                return 'Horizontal scroll setup (%s): pages %d..%d, interval %s' % (d, a[1] & 7, a[3] & 7, INTERVALS[a[2] & 7])
            return 'Horizontal scroll setup (%s)' % d
        if n in (0x29, 0x2A):
            d = 'right' if n == 0x29 else 'left'
            if len(a) == 5:
                return 'Vertical + horizontal scroll setup (%s): pages %d..%d, interval %s, %d row(s) per step' % (
                    d, a[1] & 7, a[3] & 7, INTERVALS[a[2] & 7], a[4] & 0x3F)
            return 'Vertical + horizontal scroll setup (%s)' % d
        if n == 0x2E:
            self.scrolling = False
            return 'Deactivate scroll (rewrite the RAM afterwards)'
        if n == 0x2F:
            self.scrolling = True
            return 'Activate scroll'
        if 0x40 <= n <= 0x7F:
            return 'Set display start line %d' % (n & 0x3F)
        if n == 0x81:
            return 'Set contrast %d (0x%02X)' % (a[0], a[0]) if a else 'Set contrast'
        if n == 0x8D:
            if a:
                self.pump = a[0] == 0x14
                return 'Charge pump %s (0x%02X)' % ('enabled' if a[0] == 0x14 else 'disabled' if a[0] == 0x10 else 'invalid', a[0])
            return 'Charge pump setting'
        if n in (0xA0, 0xA1):
            return 'Segment re-map ADC=%d (%s)' % (n & 1, 'column 127 = SEG0' if n & 1 else 'column 0 = SEG0')
        if n == 0xA3:
            return 'Set vertical scroll area: %d fixed rows, %d scrolling rows' % (a[0] & 0x3F, a[1] & 0x7F) if len(a) == 2 else 'Set vertical scroll area'
        if n in (0xA4, 0xA5):
            return 'Entire display %s' % ('on (RAM ignored)' if n & 1 else 'follows RAM')
        if n in (0xA6, 0xA7):
            return 'Display %s' % ('reverse' if n & 1 else 'normal')
        if n == 0xA8:
            return 'Set multiplex ratio %d' % ((a[0] & 0x3F) + 1) if a else 'Set multiplex ratio'
        if n in (0xAE, 0xAF):
            self.display_on = bool(n & 1)
            return 'Display %s' % ('on' if n & 1 else 'off')
        if 0xB0 <= n <= 0xB7:
            self.page = n & 7
            return 'Set page address %d' % self.page
        if n in (0xC0, 0xC8):
            return 'COM scan direction %s' % ('COM63 -> COM0' if n & 8 else 'COM0 -> COM63')
        if n == 0xD3:
            return 'Set display offset %d' % (a[0] & 0x3F) if a else 'Set display offset'
        if n == 0xD5:
            if a:
                return 'Set clock: divide %d, oscillator setting %d' % ((a[0] & 0x0F) + 1, a[0] >> 4)
            return 'Set clock divide / oscillator'
        if n == 0xD9:
            if a:
                return 'Set pre-charge phase 1 = %d, phase 2 = %d DCLK' % (a[0] & 0x0F, a[0] >> 4)
            return 'Set pre-charge period'
        if n == 0xDA:
            return 'COM pins %s%s (0x%02X)' % ('alternative' if a[0] & 0x10 else 'sequential', ', left/right remap' if a[0] & 0x20 else '', a[0]) if a else 'Set COM pins'
        if n == 0xDB:
            return 'Set VCOMH deselect level 0x%02X' % a[0] if a else 'Set VCOMH deselect level'
        if n == 0xE3:
            return 'NOP'
        return 'Command 0x%02X' % n

    def _flush_cmd(self, ss, es, missing_ok=False):
        p = self.pend
        if p is None:
            return
        code, need, args, pss = p
        self.pend = None
        if len(args) < need and not missing_ok:
            self._warn(pss, es, 'Missing argument byte(s) for command 0x%02X' % code)
        text = self._decode_cmd(code, args)
        info = cmd_info(code)
        name = info[1] if info else 'Command'
        raw = '0x%02X' % code + ''.join(' 0x%02X' % a for a in args)
        self._ann(pss, es, ANN_CMD, '%s: %s' % (raw, text), text, name)
        self._py(pss, es, 'COMMAND', (code, text))
        if code in (0xAE, 0xAF):
            self._ann(pss, es, ANN_STATUS, 'Display %s' % ('on' if code & 1 else 'off'), 'ON' if code & 1 else 'OFF')

    def _command_byte(self, b, ss, es):
        """One byte of a command stream (control byte D/C = 0, or D/C low on SPI)."""
        self.cmd_count += 1
        if self.pend is not None:
            code, need, args, pss = self.pend
            args.append(b)
            if len(args) >= need:
                self._flush_cmd(pss, es)
            return
        info = cmd_info(b)
        if info is None:
            hint = SIBLING_ONLY.get(b)
            self._ann(ss, es, ANN_CMD, 'Unknown command 0x%02X' % b, '0x%02X?' % b, '?')
            if hint:
                self._warn(ss, es, 'Command 0x%02X (%s) is not in the SSD1306 command set' % (b, hint))
            else:
                self._warn(ss, es, 'Command 0x%02X is not in the SSD1306 command set' % b)
            return
        if b == 0xAF and not self.pump:
            self._warn(ss, es, 'Display on without charge pump (0x8D 0x14 not seen): VCC must be external')
        if b in (0x26, 0x27, 0x29, 0x2A) and self.scrolling:
            self._warn(ss, es, 'Scroll setup 0x%02X issued while scrolling is active (send 0x2E first)' % b)
        self.pend = [b, info[0], [], ss]
        if info[0] == 0:
            self._flush_cmd(ss, es)

    # ---- data run ---------------------------------------------------------------------

    def _flush_run(self):
        if not self.run:
            return
        data = bytes(self.run)
        page, col = self.run_pos
        end_col = min(col + len(data) - 1, COLUMNS - 1)
        head = ' '.join('%02X' % b for b in data[:8]) + (' ...' if len(data) > 8 else '')
        self._ann(self.run_ss, self.run_es, ANN_DATA,
                  'RAM write: page %d col %d..%d (%d bytes): %s' % (page, col, end_col, len(data), head),
                  'p%d c%d..%d %d B' % (page, col, end_col, len(data)),
                  'p%d' % page)
        self._py(self.run_ss, self.run_es, 'DATA', (page, col, data))
        self.run = bytearray()

    def _data_byte(self, b, ss, es):
        if self.scrolling:
            self._warn(ss, es, 'RAM data written while a scroll is active (prohibited)')
        if not self.run:
            self.run_ss = ss
            self.run_pos = (self.page, self.col)
        if self.mode == 2:
            self.col = (self.col + 1) % COLUMNS
        elif self.mode == 0:
            if self.col >= self.col_end:
                self.col = self.col_start
                self.page = self.page_start if self.page >= self.page_end else self.page + 1
            else:
                self.col += 1
        else:
            if self.page >= self.page_end:
                self.page = self.page_start
                self.col = self.col_start if self.col >= self.col_end else self.col + 1
            else:
                self.page += 1
        self.run.append(b)
        self.run_es = es

    def _end_payload(self, es):
        if self.pend is not None:
            self._flush_cmd(self.pend[3], es)
        self._flush_run()

    # ---- SPI --------------------------------------------------------------------------

    def _decode_spi(self, ss, es, ptype, pdata):
        """Packets of display_spi: every byte is its own CS window; D/C low = command byte (arguments of
        double-byte commands are command bytes too), high = display RAM data."""
        if ptype == 'BYTE':
            dc, value, _miso = pdata
            if dc:
                if self.pend is not None:
                    self._flush_cmd(self.pend[3], ss)
                self._data_byte(value, ss, es)
            else:
                self._flush_run()
                self._command_byte(value, ss, es)
        elif ptype == 'IDLE':
            self._end_payload(ss)
        elif ptype == 'RESET':
            if pdata:
                self.res_ss = ss
            elif self.res_ss is not None:
                self._ann(self.res_ss, ss, ANN_STATUS, 'RES low', 'RES')
                self.res_ss = None

    # ---- I2C --------------------------------------------------------------------------

    def _finish(self, es):
        if self.state == 'IDLE':
            return
        if self.is_read:
            return
        self._end_payload(es)
        if self.cmd_count == 0 and not self.run_pos_seen and self.ctrl_first_seen == (0, 0):
            self._warn(self.ss_block, es, 'Control byte Co=0 D/C=0 but no command bytes follow')

    def _decode_i2c(self, ss, es, ptype, pdata):
        if ptype in ('START', 'START REPEAT'):
            self._finish(ss)
            self.state = 'ADDR'
            self.ss_block = ss
            self.pend = None
            self.run = bytearray()
            self.expect_ctrl = True
            self.cmd_count = 0
            self.run_pos_seen = False
            self.ctrl_first_seen = None
            self.read_first = True
            return

        if ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.is_read = ptype == 'ADDRESS READ'
            self.state = 'DATA'
            return

        if ptype == 'STOP':
            self._finish(es)
            self.state = 'IDLE'
            return

        if self.state != 'DATA':
            return

        if ptype == 'DATA WRITE' and not self.is_read:
            b = pdata
            if self.expect_ctrl:
                co, dc = (b >> 7) & 1, (b >> 6) & 1
                if b & 0x3F:
                    self._warn(ss, es, 'Control byte 0x%02X has non-zero reserved bits' % b)
                self._end_payload(ss)
                self.co, self.dc = co, dc
                self.last_dc = dc
                if self.ctrl_first_seen is None:
                    self.ctrl_first_seen = (co, dc)
                self._ann(ss, es, ANN_CTRL, 'Control: Co=%d D/C=%d (%s)' % (co, dc, 'data' if dc else 'command'),
                          'Co=%d D/C=%d' % (co, dc), 'CTL')
                self.expect_ctrl = False
                return
            if self.dc:
                self.run_pos_seen = True
                self._data_byte(b, ss, es)
            else:
                self._command_byte(b, ss, es)
            if self.co:
                if self.dc:
                    self._flush_run()
                self.expect_ctrl = True
            return

        if ptype == 'DATA READ' and self.is_read:
            self._warn(ss, es, 'The SSD1306 serial interface has no read mode (byte 0x%02X not decoded)' % pdata)

    def decode(self, ss, es, data):
        ptype, pdata = data
        if ptype in ('BYTE', 'IDLE', 'RESET', 'CS', 'BUSY'):
            self._decode_spi(ss, es, ptype, pdata)
        else:
            self._decode_i2c(ss, es, ptype, pdata)


class Decoder(_Core, srd.Decoder):
    api_version = 3
    id = 'ssd1306'
    name = 'SSD1306'
    longname = 'Solomon Systech SSD1306 OLED controller-driver (I2C)'
    desc = ('Decode the control bytes, commands, display data and status / RAM reads of an SSD1306 '
            '(e.g. 0.96" 128 x 64 and 128 x 32 OLED modules) on I2C, addresses 0x3C / 0x3D.')
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ssd1306']
    tags = ['Display', 'IC']

    annotations = (
        ('control', 'Control byte'),
        ('command', 'Command'),
        ('data', 'Display data'),
        ('read', 'Read'),
        ('status', 'Status'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CTRL, ANN_CMD, ANN_DATA, ANN_READ)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()


class SpiDecoder(_Core, srd.Decoder):
    api_version = 3
    id = 'ssd1306_spi'
    name = 'SSD1306 (SPI)'
    longname = 'Solomon Systech SSD1306 OLED controller-driver (4-wire SPI)'
    desc = ('Decode the commands and display data of an SSD1306 on 4-wire SPI: stacked on display_spi '
            '(D/C, optional RES).')
    license = 'gplv2+'
    inputs = ['display_spi']
    outputs = ['ssd1306_spi']
    tags = ['Display', 'IC']

    annotations = Decoder.annotations
    annotation_rows = Decoder.annotation_rows

    def __init__(self):
        self.reset()
