import sigrokdecode as srd

ADDRS = (0x3C, 0x3D)
COLUMNS = 132

ANN_CTRL    = 0
ANN_CMD     = 1
ANN_DATA    = 2
ANN_READ    = 3
ANN_STATUS  = 4
ANN_WARNING = 5

PUMP = ('7.4 V', '8.0 V', '8.4 V', '9.0 V')


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
    if 'not in the sh1106' in m or 'unknown' in m:
        return 'BAD?'
    if 'column' in m:
        return 'COL?'
    return 'WARN'


def cmd_info(code):
    """Return (argument bytes needed, name) for a command byte, or None if it is not an SH1106 command."""
    if code <= 0x0F:
        return 0, 'Set lower column address'
    if code <= 0x1F:
        return 0, 'Set higher column address'
    if 0x30 <= code <= 0x33:
        return 0, 'Set pump voltage'
    if 0x40 <= code <= 0x7F:
        return 0, 'Set display start line'
    if code == 0x81:
        return 1, 'Set contrast'
    if code in (0xA0, 0xA1):
        return 0, 'Segment re-map'
    if code in (0xA4, 0xA5):
        return 0, 'Entire display on'
    if code in (0xA6, 0xA7):
        return 0, 'Normal / reverse display'
    if code == 0xA8:
        return 1, 'Set multiplex ratio'
    if code == 0xAD:
        return 1, 'DC-DC control'
    if code in (0xAE, 0xAF):
        return 0, 'Display off / on'
    if 0xB0 <= code <= 0xB7:
        return 0, 'Set page address'
    if code in (0xC0, 0xC8):
        return 0, 'COM scan direction'
    if code == 0xD3:
        return 1, 'Set display offset'
    if code == 0xD5:
        return 1, 'Set clock divide / oscillator'
    if code == 0xD9:
        return 1, 'Set pre-charge / discharge'
    if code == 0xDA:
        return 1, 'Set COM pins configuration'
    if code == 0xDB:
        return 1, 'Set VCOM deselect level'
    if code == 0xE0:
        return 0, 'Read-modify-write'
    if code == 0xE3:
        return 0, 'NOP'
    if code == 0xEE:
        return 0, 'End'
    return None


# SSD1306-only commands that do not exist on the SH1106 (named in the warning for a quicker diagnosis).
SSD1306_ONLY = {0x20: 'memory addressing mode', 0x21: 'column address range', 0x22: 'page address range',
                0x8D: 'charge pump', 0x26: 'scroll setup', 0x27: 'scroll setup', 0x29: 'scroll setup',
                0x2A: 'scroll setup', 0x2E: 'deactivate scroll', 0x2F: 'activate scroll', 0xA3: 'vertical scroll area'}


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
        if n <= 0x0F:
            self.col = (self.col & 0xF0) | (n & 0x0F)
            self.dummy = True
            return 'Set lower column address 0x%X' % (n & 0x0F)
        if n <= 0x1F:
            self.col = (self.col & 0x0F) | ((n & 0x0F) << 4)
            self.dummy = True
            return 'Set higher column address 0x%X (column %d)' % (n & 0x0F, self.col)
        if 0x30 <= n <= 0x33:
            return 'Set pump voltage %s' % PUMP[n & 3]
        if 0x40 <= n <= 0x7F:
            return 'Set display start line %d' % (n & 0x3F)
        if n == 0x81:
            return 'Set contrast %d (0x%02X)' % (args[0], args[0]) if args else 'Set contrast'
        if n in (0xA0, 0xA1):
            return 'Segment re-map ADC=%d (%s)' % (n & 1, 'column 131 = SEG0' if n & 1 else 'column 0 = SEG0')
        if n in (0xA4, 0xA5):
            return 'Entire display %s' % ('on (RAM ignored)' if n & 1 else 'follows RAM')
        if n in (0xA6, 0xA7):
            return 'Display %s' % ('reverse' if n & 1 else 'normal')
        if n == 0xA8:
            return 'Set multiplex ratio %d' % ((args[0] & 0x3F) + 1) if args else 'Set multiplex ratio'
        if n == 0xAD:
            if args:
                return 'DC-DC %s (0x%02X)' % ('on' if args[0] & 1 else 'off', args[0])
            return 'DC-DC control'
        if n in (0xAE, 0xAF):
            self.display_on = bool(n & 1)
            return 'Display %s' % ('on' if n & 1 else 'off')
        if 0xB0 <= n <= 0xB7:
            self.page = n & 7
            self.dummy = True
            return 'Set page address %d' % self.page
        if n in (0xC0, 0xC8):
            return 'COM scan direction %s' % ('COM63 -> COM0' if n & 8 else 'COM0 -> COM63')
        if n == 0xD3:
            return 'Set display offset %d' % (args[0] & 0x3F) if args else 'Set display offset'
        if n == 0xD5:
            if args:
                return 'Set clock: divide %d, oscillator %+d %%' % ((args[0] & 0x0F) + 1, ((args[0] >> 4) - 5) * 5)
            return 'Set clock divide / oscillator'
        if n == 0xD9:
            if args:
                return 'Set pre-charge %d / discharge %d DCLK' % (args[0] & 0x0F, args[0] >> 4)
            return 'Set pre-charge / discharge'
        if n == 0xDA:
            return 'COM pins %s (0x%02X)' % ('alternative' if args[0] & 0x10 else 'sequential', args[0]) if args else 'Set COM pins'
        if n == 0xDB:
            return 'Set VCOM deselect level 0x%02X' % args[0] if args else 'Set VCOM deselect level'
        if n == 0xE0:
            self.in_rmw = True
            return 'Read-modify-write start'
        if n == 0xEE:
            self.in_rmw = False
            return 'End (leave read-modify-write)'
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
            hint = SSD1306_ONLY.get(b)
            self._ann(ss, es, ANN_CMD, 'Unknown command 0x%02X' % b, '0x%02X?' % b, '?')
            if hint:
                self._warn(ss, es, 'Command 0x%02X (SSD1306 %s) is not in the SH1106 command set' % (b, hint))
            else:
                self._warn(ss, es, 'Command 0x%02X is not in the SH1106 command set' % b)
            return
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
        if not self.run:
            self.run_ss = ss
            self.run_pos = (self.page, self.col)
        if self.col >= COLUMNS:
            self._warn(ss, es, 'Data written past the last column (%d): the column pointer stops there' % (COLUMNS - 1))
        else:
            self.col += 1
            self.dummy = False
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
            b = pdata
            if not self.last_dc:
                self._ann(ss, es, ANN_READ, 'Read status 0x%02X (BUSY=%d %s)' % (b, (b >> 7) & 1, 'display off' if b & 0x40 else 'display on'),
                          'Status 0x%02X' % b, 'Status')
                self._py(ss, es, 'STATUS', b)
                self._ann(ss, es, ANN_STATUS, 'Display %s' % ('off' if b & 0x40 else 'on'), 'OFF' if b & 0x40 else 'ON')
                return
            if self.dummy and self.read_first:
                self._ann(ss, es, ANN_READ, 'Dummy read 0x%02X' % b, 'dummy', 'D')
                self.dummy = False
                self.read_first = False
                return
            self.read_first = False
            self._ann(ss, es, ANN_READ, 'RAM read: page %d col %d = 0x%02X' % (self.page, self.col, b),
                      'p%d c%d 0x%02X' % (self.page, self.col, b), '%02X' % b)
            self._py(ss, es, 'RAM_READ', (self.page, self.col, bytes([b])))
            if self.col < COLUMNS - 1:
                self.col += 1

    def decode(self, ss, es, data):
        ptype, pdata = data
        if ptype in ('BYTE', 'IDLE', 'RESET', 'CS', 'BUSY'):
            self._decode_spi(ss, es, ptype, pdata)
        else:
            self._decode_i2c(ss, es, ptype, pdata)


class Decoder(_Core, srd.Decoder):
    api_version = 3
    id = 'sh1106'
    name = 'SH1106'
    longname = 'Sino Wealth SH1106 OLED controller-driver (I2C)'
    desc = ('Decode the control bytes, commands, display data and status / RAM reads of an SH1106 '
            '(e.g. 1.3" 128 x 64 OLED modules) on I2C, addresses 0x3C / 0x3D.')
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['sh1106']
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
    id = 'sh1106_spi'
    name = 'SH1106 (SPI)'
    longname = 'Sino Wealth SH1106 OLED controller-driver (4-wire SPI)'
    desc = ('Decode the commands and display data of an SH1106 on 4-wire SPI: stacked on display_spi '
            '(D/C = A0, optional RES).')
    license = 'gplv2+'
    inputs = ['display_spi']
    outputs = ['sh1106_spi']
    tags = ['Display', 'IC']

    annotations = Decoder.annotations
    annotation_rows = Decoder.annotation_rows

    def __init__(self):
        self.reset()
