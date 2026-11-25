import sigrokdecode as srd

ADDRS = {0x3C, 0x3D}

CMD_SET_MODE       = 0x40
CMD_DEVICE_SELECT  = 0x60
CMD_RAM_ACCESS     = 0x70
CMD_SET_START_BANK = 0x7C

STATUS_NAMES = {0: 'blank', 1: 'normal', 2: 'all segments on', 3: 'inverse video'}
MUX_ROWS = {1: 8, 2: 16, 3: 24, 0: 32}
ACCESS_NAMES = {0: 'character', 1: 'half-graphic', 2: 'full-graphic', 3: 'invalid (G=11)'}

RAM_COLUMNS = 40

ANN_CMD = 0
ANN_DATA = 1
ANN_READ = 2
ANN_POWERON_START = 3
ANN_POWERON_READY = 4
ANN_WARNING = 5


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'chip id' in m or 'device id' in m:
        return 'ID?'
    if 'address' in m:
        return 'ADDR?'
    if any(k in m for k in ('length', 'byte', 'short', 'missing', 'expected', 'no data', 'empty')):
        return 'LEN?'
    if any(k in m for k in ('unknown', 'unexpected', 'invalid', 'undefined', 'reserved', 'out of range')):
        return 'BAD?'
    return 'WARN'


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
        while n < len(text) and (text[n].isalnum() or text[n] in '_\u2192'):
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
    id = 'pcf8579'
    name = 'PCF8579'
    longname = 'PCF8579 LCD column driver'
    desc = 'Decode PCF8579 I2C command-stream transactions and display RAM accesses (companion of the PCF8578).'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pcf8579']
    tags = ['IC', 'Display', 'LCD']

    annotations = (
        ('cmd',           'Command'),
        ('data',          'Display data written'),
        ('read',          'Display RAM read'),
        ('poweron-start', 'Capture start (power-on marker)'),
        ('poweron-ready', 'First transaction after power-on'),
        ('warning',       'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CMD, ANN_DATA, ANN_READ)),
        ('timing',   'Timing',   (ANN_POWERON_START, ANN_POWERON_READY)),
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
        # Chip state tracked across transactions (power-on defaults, spec "Power-on reset defaults").
        self.mixed = False       # T bit: PCF8578 mode in the system (row driver / mixed)
        self.status = 0          # E[1:0]: blank
        self.rows = 32           # 1:32
        self.start_bank = 0
        self.access = 0          # character
        self.bank = 0            # Y
        self.x = 0               # data pointer column
        self.sub = 0             # subaddress counter
        self.seen_first = False
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.ss_block = None
        self.es = None
        self.cmds = []           # (raw 7-bit value, ss, es)
        self.data = []           # (byte, ss, es, sub, x, bank)
        self.raw = []
        self.c_open = False
        self.lost_warned = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    @staticmethod
    def _bitmap(b):
        """8-pixel column bitmap, bit 0 (top row) first."""
        return ''.join('#' if b & (1 << i) else '.' for i in range(8))

    # -- command handling ------------------------------------------------

    def _apply_command(self, raw, ss, es):
        """Update tracked state and return the human-readable decode."""
        if raw & 0x7C == CMD_SET_START_BANK:
            self.start_bank = raw & 3
            return 'set-start-bank 0x%02X: bank=%d' % (raw, self.start_bank)
        if raw & 0x70 == CMD_RAM_ACCESS:
            self.access = (raw >> 2) & 3
            self.bank = raw & 3
            if self.access == 3:
                self._warn(ss, es, 'RAM-access mode G=11 is not allowed')
            return 'RAM-access 0x%02X: %s, bank=%d' % (raw, ACCESS_NAMES[self.access], self.bank)
        if raw & 0x70 == CMD_DEVICE_SELECT:
            self.sub = raw & 0x0F
            return 'device-select 0x%02X: subaddress=%d' % (raw, self.sub)
        if raw & 0x60 == CMD_SET_MODE:
            self.mixed = bool((raw >> 4) & 1)
            self.status = (raw >> 2) & 3
            self.rows = MUX_ROWS[raw & 3]
            return 'set-mode 0x%02X: PCF8578 %s mode (T=%d), %s, 1:%d (%d rows)' % (
                raw, 'mixed' if self.mixed else 'row', int(self.mixed), STATUS_NAMES[self.status],
                self.rows, self.rows)
        # remaining pattern: 0 X5..X0
        self.x = raw & 0x3F
        if self.x >= RAM_COLUMNS:
            self._warn(ss, es, 'load-X address %d is out of range (0-39)' % self.x)
        return 'load-X 0x%02X: column=%d' % (raw, self.x)

    def _advance(self):
        """Advance (x, bank) after one data byte per the current access mode."""
        if self.access in (0, 3):
            self.x += 1
        elif self.access == 1:
            if self.bank & 1:
                self.bank -= 1
                self.x += 1
            else:
                self.bank += 1
        else:
            self.bank = (self.bank + 1) & 3
            if self.bank == 0:
                self.x += 1
        if self.x >= RAM_COLUMNS:
            self.x = 0
            self.sub = (self.sub + 1) & 0x0F
            return self.sub == 0
        return False

    def _data_byte(self, byte, ss, es, reading):
        entry = (byte, ss, es, self.sub, self.x, self.bank)
        if self._advance() and not self.lost_warned:
            self.lost_warned = True
            self._warn(ss, es, 'Transfer passes column 39 of subaddress 15: subaddress counter wraps to 0')
        return entry

    # -- transaction end ----------------------------------------------------

    def _finish_transaction(self):
        if self.state not in ('GET_CMD', 'GET_DATA', 'READ'):
            return
        ss, es = self.ss_block, self.es
        if self.cmds:
            labels = [lab for (_raw, _s, _e, lab) in self.cmds]
            label = 'Cmd: ' + ' \u2192 '.join(labels)
            short = 'C' if len(self.cmds) == 1 else 'C(%d)' % len(self.cmds)
            self.put(self.cmds[0][1], self.cmds[-1][2], self.out_ann, [ANN_CMD, [label, short]])
        if self.state == 'GET_CMD' and self.cmds and self.c_open:
            self._warn(ss, es, 'Transaction ends while the last command still has C=1 (no data phase)')
        if self.data:
            first, last = self.data[0], self.data[-1]
            parts = ['%sX=%d b%d 0x%02X [%s]' % ('sub%d ' % d[3] if d[3] else '', d[4], d[5], d[0],
                                                    self._bitmap(d[0])) for d in self.data[:8]]
            more = ' \u2026 (+%d)' % (len(self.data) - 8) if len(self.data) > 8 else ''
            kind = 'Read RAM' if self.is_read else 'Write RAM'
            label = '%s: ' % kind + '; '.join(parts) + more
            medium = '%s %d byte%s @X=%d b%d' % (kind, len(self.data), '' if len(self.data) == 1 else 's',
                                                  first[4], first[5])
            short = ('R' if self.is_read else 'W') + '[%d]' % len(self.data)
            self.put(first[1], last[2], self.out_ann,
                     [ANN_READ if self.is_read else ANN_DATA, [label, medium, short]])
        if self.raw and self.out_python is not None:
            super().put(ss, es, self.out_python,
                        ('I2C_READ' if self.is_read else 'I2C_WRITE', (self.addr, bytes(self.raw))))
        if not self.seen_first:
            self.seen_first = True
            self.put(ss, es, self.out_ann,
                     [ANN_POWERON_READY, ['poweron_ready: first transaction after power-on',
                                          'poweron_ready', '\u26a1']])
        self.state = 'IDLE'
        self.cmds = []
        self.data = []
        self.raw = []

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.es = es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            if not self.seen_first and self.ss_block is None:
                # marker spanning capture start up to the first START (the power-on wait)
                self.put(0, ss, self.out_ann,
                         [ANN_POWERON_START, ['poweron_start: capture start (power-on)',
                                              'poweron_start', 'P\u25b6']])
            self.ss_block = ss
            self.state = 'GET_ADDR'
            self.cmds = []
            self.data = []
            self.raw = []
            self.c_open = False
            self.lost_warned = False

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self._warn(ss, es, 'Unexpected address 0x%02X' % pdata)
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = ptype == 'ADDRESS READ'
            self.state = 'READ' if self.is_read else 'GET_CMD'

        elif ptype == 'DATA WRITE' and self.state in ('GET_CMD', 'GET_DATA'):
            byte = pdata
            self.raw.append(byte)
            if self.state == 'GET_CMD':
                raw = byte & 0x7F
                label = self._apply_command(raw, ss, es)
                self.cmds.append((raw, ss, es, label))
                self.c_open = bool(byte & 0x80)
                if not byte & 0x80:
                    self.state = 'GET_DATA'
            else:
                self.data.append(self._data_byte(byte, ss, es, False))

        elif ptype == 'DATA READ' and self.state == 'READ':
            self.raw.append(pdata)
            self.data.append(self._data_byte(pdata, ss, es, True))

        elif ptype == 'STOP':
            self._finish_transaction()
