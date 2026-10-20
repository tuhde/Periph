import sigrokdecode as srd

ADDRS = set(range(0x38, 0x40))

ANN_CMD     = 0
ANN_DATA    = 1
ANN_READ    = 2
ANN_STATUS  = 3
ANN_WARNING = 4

TEMP_COMP = ('-0.05 %/C', '-0.10 %/C', '-0.15 %/C', '0.00 %/C')
BIAS = ('1/5', '1/10', '1/11', '1/12')
LINE_RATE_ONOFF = ('5.9', '7.1', '8.2', '9.4')
SHADES = ('On/Off', '8-shade', '4-shade', '16-shade')

PAGES = 20


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
    if 'reserved' in m or 'unknown' in m or 'not used' in m:
        return 'BAD?'
    if 'after system reset' in m:
        return 'RST?'
    if 'column' in m:
        return 'COL?'
    return 'WARN'


def _cmd_info(code):
    """Return (argument bytes needed, name) for a command byte, or None if it is not a command."""
    if code <= 0x0F:
        return 0, 'Set column address LSB'
    if code <= 0x1F:
        return 0, 'Set column address MSB'
    if 0x24 <= code <= 0x27:
        return 0, 'Temperature compensation'
    if 0x28 <= code <= 0x2B:
        return 0, 'Panel loading'
    if 0x2C <= code <= 0x2F:
        return 0, 'Pump control'
    if 0x30 <= code <= 0x33:
        return 1, 'Advanced program control (reserved)'
    if 0x40 <= code <= 0x4F:
        return 0, 'Set scroll line LSB'
    if 0x50 <= code <= 0x5F:
        return 0, 'Set scroll line MSB'
    if 0x60 <= code <= 0x6F:
        return 0, 'Set page address LSB'
    if 0x70 <= code <= 0x77:
        return 0, 'Set page address MSB'
    if code == 0x81:
        return 1, 'Set potentiometer'
    if code == 0x82:
        return 2, 'Set isolation clock'
    if 0x84 <= code <= 0x87:
        return 0, 'Partial display control'
    if 0x88 <= code <= 0x8F:
        return 0, 'RAM address control'
    if 0x90 <= code <= 0x9F:
        return 0, 'Set fixed lines'
    if 0xA0 <= code <= 0xA3:
        return 0, 'Set line rate'
    if code in (0xA4, 0xA5):
        return 0, 'All pixels on'
    if code in (0xA6, 0xA7):
        return 0, 'Inverse display'
    if 0xA8 <= code <= 0xAF:
        return 0, 'Display enable'
    if code in (0xB8, 0xB9):
        return 1, 'MTP control (reserved)'
    if code == 0xC0:
        return 1, 'LCD mapping control'
    if code == 0xC8:
        return 1, 'N-line inversion'
    if 0xD0 <= code <= 0xD7:
        return 0, 'Display pattern'
    if code == 0xE2:
        return 0, 'System reset'
    if code == 0xE3:
        return 0, 'NOP'
    if code == 0xE4:
        return 1, 'Test control (reserved)'
    if 0xE8 <= code <= 0xEB:
        return 0, 'Bias ratio'
    if code == 0xF1:
        return 1, 'Set COM end'
    if code == 0xF2:
        return 1, 'Set partial display start'
    if code == 0xF3:
        return 1, 'Set partial display end'
    if 0xF4 <= code <= 0xF7:
        return 1, 'Window program'
    if code in (0xF8, 0xF9):
        return 0, 'Window program mode'
    if code == 0xFE:
        return 0, 'Get status (serial modes only)'
    return None


class Decoder(srd.Decoder):
    api_version = 3
    id = 'uc1611s'
    name = 'UC1611s'
    longname = 'UltraChip UC1611s graphic LCD controller-driver'
    desc = ('Decode the I2C command, data and read transactions of a UC1611s '
            '(e.g. EA DOGXL240-7, command address 0x38 + 2n, data address one higher).')
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['uc1611s']
    tags = ['Display', 'IC']

    options = (
        {'id': 'width', 'desc': 'Columns of the panel (RAM column wrap)', 'default': 240},
    )
    annotations = (
        ('command', 'Command'),
        ('data', 'Display data'),
        ('read', 'Read'),
        ('status', 'Status'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CMD, ANN_DATA, ANN_READ)),
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
        self.state = 'IDLE'
        self.addr = None
        self.is_cd = False         # True: data address (CD = 1)
        self.is_read = False
        self.ss_block = None
        self.pend = None           # [code, need, args, ss]
        self.reset_seen = False
        self.run = bytearray()     # data bytes of the current transaction
        self.run_ss = None
        self.run_es = None
        self.run_pos = None        # (pattern, page, column) at the start of the run
        self.status_bytes = bytearray()
        self.status_ss = None
        self.status_pos = 0         # position in the Status1/2/3 cycle
        self.dummy = False
        self.page = 0
        self.col = 0
        self.pattern = 0
        self.wrap = True
        self.display_on = None
        self.mx = None
        self.my = None
        self.read_first = True

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)
        self.width = int(self.options['width'])

    # ---- helpers ---------------------------------------------------------------------

    def _ann(self, ss, es, cls, *strings):
        self.put(ss, es, self.out_ann, [cls, list(strings)])

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _py(self, ss, es, kind, value):
        super().put(ss, es, self.out_python, (kind, value))

    def _set_page(self, value, mask):
        pa = (self.pattern << 5) | self.page
        pa = (pa & ~mask) | value
        self.pattern = (pa >> 5) & 3
        self.page = pa & 0x1F
        self.dummy = True

    def _set_col(self, value, mask):
        self.col = (self.col & ~mask) | value
        self.dummy = True

    # ---- command decoding -------------------------------------------------------------

    def _decode_cmd(self, code, args):
        """Return the annotation text for command `code` with argument bytes `args`."""
        n = code
        if n <= 0x0F:
            self._set_col(n & 0x0F, 0x0F)
            return 'Set column address LSB 0x%X' % (n & 0x0F)
        if n <= 0x1F:
            self._set_col((n & 0x0F) << 4, 0xF0)
            return 'Set column address MSB 0x%X (column %d)' % (n & 0x0F, self.col)
        if 0x24 <= n <= 0x27:
            return 'Temperature compensation %s' % TEMP_COMP[n & 3]
        if 0x28 <= n <= 0x2B:
            return 'Panel loading %s' % ('33..55 nF' if (n & 3) == 3 else '<= 33 nF' if (n & 3) == 0 else 'PC[1:0]=%d' % (n & 3))
        if 0x2C <= n <= 0x2F:
            pc = n & 3
            return 'Pump control %s' % ('internal 11x' if pc == 3 else 'external VLCD' if pc == 0 else 'PC[3:2]=%d (invalid)' % pc)
        if 0x30 <= n <= 0x33:
            return 'Advanced program control R=%d (reserved) 0x%02X' % (n & 3, args[0] if args else 0)
        if 0x40 <= n <= 0x4F:
            return 'Set scroll line LSB 0x%X' % (n & 0x0F)
        if 0x50 <= n <= 0x5F:
            return 'Set scroll line MSB 0x%X' % (n & 0x0F)
        if 0x60 <= n <= 0x6F:
            self._set_page(n & 0x0F, 0x0F)
            return 'Set page address LSB 0x%X' % (n & 0x0F)
        if 0x70 <= n <= 0x77:
            self._set_page((n & 0x07) << 4, 0x70)
            return 'Set page address MSB 0x%X (pattern %d, page %d)' % (n & 0x07, self.pattern, self.page)
        if n == 0x81:
            return 'Set potentiometer PM=%d (0x%02X)' % (args[0], args[0]) if args else 'Set potentiometer'
        if n == 0x82:
            if len(args) >= 2:
                which = {0x13: 'front ISOF', 0x14: 'back ISOB'}.get(args[0])
                if which:
                    return 'Set isolation clock %s = %d' % (which, args[1] & 0x0F)
                return 'Set isolation clock (unknown selector 0x%02X)' % args[0]
            return 'Set isolation clock'
        if 0x84 <= n <= 0x87:
            return 'Partial display %s' % ('enabled' if (n & 3) == 3 else 'disabled' if (n & 3) == 0 else 'LC[9:8]=%d' % (n & 3))
        if 0x88 <= n <= 0x8F:
            ac = n & 7
            self.wrap = bool(ac & 1)
            return 'RAM address control: wrap %s, %s first, page %s (AC=%d)' % (
                'on' if ac & 1 else 'off', 'page' if ac & 2 else 'column', '-1' if ac & 4 else '+1', ac)
        if 0x90 <= n <= 0x9F:
            return 'Set fixed lines FL=%d (%d rows)' % (n & 0x0F, 2 * (n & 0x0F))
        if 0xA0 <= n <= 0xA3:
            return 'Set line rate %s klps (On/Off mode)' % LINE_RATE_ONOFF[n & 3]
        if n in (0xA4, 0xA5):
            return 'All pixels %s' % ('on' if n & 1 else 'normal')
        if n in (0xA6, 0xA7):
            return 'Inverse display %s' % ('on' if n & 1 else 'off')
        if 0xA8 <= n <= 0xAF:
            dc = n & 7
            on = bool(dc & 1)
            self.display_on = on
            return 'Display enable: %s, %s mode' % ('on' if on else 'sleep', SHADES[(dc >> 1) & 3])
        if n in (0xB8, 0xB9):
            return 'MTP %s 0x%02X (reserved)' % ('control' if n == 0xB8 else 'write mask', args[0] if args else 0)
        if n == 0xC0:
            if args:
                a = args[0]
                self.mx = bool(a & 2)
                self.my = bool(a & 4)
                return 'LCD mapping: MX=%d MY=%d MSF=%d LC3=%d' % ((a >> 1) & 1, (a >> 2) & 1, a & 1, (a >> 3) & 1)
            return 'LCD mapping control'
        if n == 0xC8:
            if args:
                return 'N-line inversion %d lines%s' % (args[0] & 0x3F, ' (XOR)' if args[0] & 0x40 else '')
            return 'N-line inversion'
        if 0xD0 <= n <= 0xD7:
            dc = n & 7
            return 'Display pattern: %s, screen %d' % ('1 bit/pixel' if dc & 1 else '4 bit/pixel', dc >> 1)
        if n == 0xE2:
            self.reset_seen = True
            self.page = self.col = self.pattern = 0
            self.wrap = True
            return 'System reset'
        if n == 0xE3:
            return 'NOP'
        if n == 0xE4:
            return 'Test control 0x%02X (reserved)' % (args[0] if args else 0)
        if 0xE8 <= n <= 0xEB:
            return 'Bias ratio %s' % BIAS[n & 3]
        if n == 0xF1:
            return 'Set COM end = %d (%d rows)' % (args[0], args[0] + 1) if args else 'Set COM end'
        if n == 0xF2:
            return 'Set partial display start = %d' % args[0] if args else 'Set partial display start'
        if n == 0xF3:
            return 'Set partial display end = %d' % args[0] if args else 'Set partial display end'
        if 0xF4 <= n <= 0xF7:
            name = ('window start column', 'window start page', 'window end column', 'window end page')[n & 3]
            return 'Set %s = %d' % (name, args[0]) if args else 'Set %s' % name
        if n in (0xF8, 0xF9):
            return 'Window program %s' % ('outside' if n & 1 else 'inside')
        if n == 0xFE:
            return 'Get status (serial modes only, not used on I2C)'
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
        info = _cmd_info(code)
        name = info[1] if info else 'Command'
        raw = '0x%02X' % code + ''.join(' 0x%02X' % a for a in args)
        self._ann(pss, es, ANN_CMD, '%s: %s' % (raw, text), text, name)
        self._py(pss, es, 'COMMAND', (code, text))
        if info and 'reserved' in info[1]:
            self._warn(pss, es, 'Reserved command 0x%02X (%s)' % (code, info[1]))
        if code == 0xFE:
            self._warn(pss, es, 'Get status 0xFE is not used on I2C (serial modes only)')

    # ---- data run ---------------------------------------------------------------------

    def _flush_run(self):
        if not self.run:
            return
        data = bytes(self.run)
        pattern, page, col = self.run_pos
        end_col = col + len(data) - 1
        head = ' '.join('%02X' % b for b in data[:8]) + (' ...' if len(data) > 8 else '')
        self._ann(self.run_ss, self.run_es, ANN_DATA,
                  'RAM write: screen %d page %d col %d..%d (%d bytes): %s' % (pattern, page, col, end_col, len(data), head),
                  'p%d c%d..%d %d B' % (page, col, end_col, len(data)),
                  'p%d' % page)
        self._py(self.run_ss, self.run_es, 'DATA', (pattern, page, col, data))
        self.run = bytearray()

    def _advance(self, n, ss, es):
        """Advance the tracked RAM address by n written / read bytes."""
        col = self.col + n
        while col >= self.width:
            if self.wrap:
                col -= self.width
                self.page = (self.page + 1) % PAGES
            else:
                self._warn(ss, es, 'Data written past the last column (%d) with wrap-around off' % (self.width - 1))
                col = self.width - 1
                break
        self.col = col

    # ---- decode -----------------------------------------------------------------------

    def _finish(self, es):
        if self.state == 'IDLE':
            return
        if self.is_read:
            if not self.is_cd and self.status_bytes:
                self._flush_status(es)
        elif self.is_cd:
            self._flush_run()
        else:
            self._flush_cmd(self.pend[3] if self.pend else es, es)

    def _flush_status(self, es):
        """Annotate a status read. The chip's status cycle (Status1, Status2, Status3) advances by n + 1 per read
        transaction and restarts at Status1 after any other transaction, so the labels follow that position."""
        b = bytes(self.status_bytes)
        self.status_bytes = bytearray()
        pos = self.status_pos
        parts = []
        status1 = None
        decoded = {}
        for i, s in enumerate(b):
            idx = (pos + i) % 3
            if idx == 0:
                parts.append('Status1 0x%02X (Ver=%d MX=%d MY=%d WA=%d DE=%d WS=%d MD=%d MS=%d)' % (
                    s, s >> 7, (s >> 6) & 1, (s >> 5) & 1, (s >> 4) & 1, (s >> 3) & 1, (s >> 2) & 1, (s >> 1) & 1, s & 1))
                self.display_on = bool(s & 8)
                self.mx = bool(s & 0x40)
                self.my = bool(s & 0x20)
                status1 = s
            elif idx == 1:
                parts.append('Status2 0x%02X (ID=%d PMO=0x%02X)' % (s, s >> 6, s & 0x3F))
            else:
                parts.append('Status3 0x%02X (product %d, ESD=%d)' % (s, s >> 4, s & 1))
            decoded[idx] = s
        self.status_pos = (pos + len(b) + 1) % 3
        short = ' '.join('%02X' % x for x in b)
        self._ann(self.status_ss, es, ANN_READ, 'Read status: ' + '; '.join(parts), 'Status %s' % short, 'Status')
        self._py(self.status_ss, es, 'STATUS', tuple(decoded.get(i) for i in range(3)))
        if status1 is not None:
            self._ann(self.status_ss, es, ANN_STATUS,
                      'Display %s, MX=%d MY=%d' % ('on' if status1 & 8 else 'sleep', (status1 >> 6) & 1, (status1 >> 5) & 1),
                      'DE=%d' % ((status1 >> 3) & 1), 'DE')

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype in ('START', 'START REPEAT'):
            self._finish(ss)
            self.state = 'ADDR'
            self.ss_block = ss
            self.pend = None
            self.reset_seen = False
            self.run = bytearray()
            self.status_bytes = bytearray()
            self.read_first = True
            return

        if ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = ptype == 'ADDRESS READ'
            self.is_cd = bool(pdata & 1)
            if not (self.is_read and not self.is_cd):
                self.status_pos = 0      # any transaction but a status read restarts the cycle
            self.state = 'DATA'
            self.run_pos = None
            return

        if ptype == 'STOP':
            self._finish(es)
            self.state = 'IDLE'
            return

        if self.state != 'DATA':
            return

        if ptype == 'DATA WRITE' and not self.is_read:
            b = pdata
            if self.reset_seen:
                self._warn(ss, es, 'Byte 0x%02X after System Reset in the same transaction' % b)
            if self.is_cd:
                if not self.run:
                    self.run_ss = ss
                    self.run_pos = (self.pattern, self.page, self.col)
                self.run.append(b)
                self.run_es = es
                self._advance(1, ss, es)
                return
            # command address
            if self.pend is not None:
                code, need, args, pss = self.pend
                args.append(b)
                if len(args) >= need:
                    self._flush_cmd(pss, es)
                return
            info = _cmd_info(b)
            if info is None:
                self._ann(ss, es, ANN_CMD, 'Unknown command 0x%02X' % b, '0x%02X?' % b, '?')
                self._warn(ss, es, 'Unknown command byte 0x%02X' % b)
                return
            self.pend = [b, info[0], [], ss]
            if info[0] == 0:
                self._flush_cmd(ss, es)
            return

        if ptype == 'DATA READ' and self.is_read:
            b = pdata
            if not self.is_cd:
                if not self.status_bytes:
                    self.status_ss = ss
                self.status_bytes.append(b)
                return
            if self.dummy and self.read_first:
                self._ann(ss, es, ANN_READ, 'Dummy read 0x%02X' % b, 'dummy', 'D')
                self.dummy = False
                self.read_first = False
                return
            self.read_first = False
            pos = (self.pattern, self.page, self.col)
            self._ann(ss, es, ANN_READ, 'RAM read: screen %d page %d col %d = 0x%02X' % (pos[0], pos[1], pos[2], b),
                      'p%d c%d 0x%02X' % (pos[1], pos[2], b), '%02X' % b)
            self._py(ss, es, 'RAM_READ', (pos[0], pos[1], pos[2], bytes([b])))
            self._advance(1, ss, es)
