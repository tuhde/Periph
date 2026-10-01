import sigrokdecode as srd

ADDRS_PCF8574  = set(range(0x20, 0x28))
ADDRS_PCF8574A = set(range(0x38, 0x40))
ALL_ADDRS = ADDRS_PCF8574 | ADDRS_PCF8574A

ANN_READ    = 0
ANN_WRITE   = 1
ANN_WARNING = 2
# Named start/end pair for the read_cycle conformance check (see
# specs/io_expander/pcf8574.md, "Timing Constraints" and "Sigrok Decoder",
# and specs/io_expander/pcf8574_timing.conf). Additive: does not change the
# existing read/write/warning annotations PulseView's manual verification
# depends on.
ANN_READ_CYCLE_START = 3
ANN_READ_CYCLE_DONE  = 4


def _fmt_pins(byte):
    return ' '.join('P%d=%d' % (i, (byte >> i) & 1) for i in range(7, -1, -1))


def _chip_name(addr):
    return 'PCF8574A' if addr in ADDRS_PCF8574A else 'PCF8574'


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
    id = 'pcf8574'
    name = 'PCF8574'
    longname = 'PCF8574/PCF8574A 8-bit I/O expander'
    desc = 'Decode PCF8574 and PCF8574A 8-bit I2C I/O expander transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pcf8574']
    tags = ['IC']

    annotations = (
        ('read',    'Read'),
        ('write',   'Write'),
        ('warning', 'Warning'),
        ('read-cycle-start', 'Read cycle start'),
        ('read-cycle-done',  'Read cycle done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_READ, ANN_WRITE)),
        ('timing',   'Timing',   (ANN_READ_CYCLE_START, ANN_READ_CYCLE_DONE)),
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
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.ss_block = None
        self.databyte = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self.state    = 'GET_ADDR'
            self.ss_block = ss
            self.databyte = None

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ALL_ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = addr
            self.is_read = (ptype == 'ADDRESS READ')
            self.state   = 'GET_DATA'
            if self.is_read:
                # read_cycle start: the ADDRESS READ that begins a plain
                # single-byte read transaction.
                self.put(ss, es, self.out_ann, [ANN_READ_CYCLE_START, ['read_cycle_start: ADDRESS READ begins a read', 'read_cycle_start', 'RC\u25b6']])

        elif ptype in ('DATA READ', 'DATA WRITE') and self.state == 'GET_DATA':
            if self.databyte is not None:
                self._warn(ss, es, 'Unexpected extra data byte')
            self.databyte = pdata

        elif ptype == 'STOP':
            if self.state != 'GET_DATA' or self.databyte is None:
                self.state = 'IDLE'
                return

            byte  = self.databyte
            hx    = '0x%02X' % byte
            pins  = _fmt_pins(byte)
            chip  = _chip_name(self.addr)

            if self.is_read:
                self.put(self.ss_block, es, self.out_ann,
                         [ANN_READ,
                          ['%s Read %s: %s' % (chip, hx, pins),
                           'R %s' % hx,
                           'R']])
                # read_cycle done: the STOP that ends the read transaction
                # started by the ANN_READ_CYCLE_START annotation above.
                self.put(ss, es, self.out_ann, [ANN_READ_CYCLE_DONE, ['read_cycle_done: STOP ended the read', 'read_cycle_done', 'RC\u2713']])
            else:
                self.put(self.ss_block, es, self.out_ann,
                         [ANN_WRITE,
                          ['%s Write %s: %s' % (chip, hx, pins),
                           'W %s' % hx,
                           'W']])

            self.state    = 'IDLE'
            self.databyte = None
