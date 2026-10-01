import sigrokdecode as srd

ADDRS = set(range(0x20, 0x28))

ANN_READ    = 0
ANN_WRITE   = 1
ANN_WARNING = 2
# Named start/end pair for the read_cycle conformance check (see
# specs/io_expander/pcf8575.md, "Timing Constraints" and "Sigrok Decoder",
# and specs/io_expander/pcf8575_timing.conf). Additive: does not change the
# existing read/write/warning annotations PulseView's manual verification
# depends on.
ANN_READ_CYCLE_START = 3
ANN_READ_CYCLE_DONE  = 4


def _fmt_port_pins(port, byte):
    return ' '.join('P%d%d=%d' % (port, i, (byte >> i) & 1) for i in range(7, -1, -1))


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
    id = 'pcf8575'
    name = 'PCF8575'
    longname = 'PCF8575 16-bit I/O expander'
    desc = 'Decode PCF8575 16-bit I2C I/O expander transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pcf8575']
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
                self._mirror_python(ss, es)
        super().put(ss, es, out, data)

    def _mirror_python(self, ss, es):
        """OUTPUT_PYTHON mirror of each transaction-level data annotation:
        ('REG_READ' | 'REG_WRITE', (register, bytes)), or
        ('I2C_READ' | 'I2C_WRITE', (address, bytes)) for chips without a register pointer."""
        out_py = self.__dict__.get('out_python')
        if out_py is None or self.__dict__.get('_py_span') == (ss, es):
            return
        self._py_span = (ss, es)
        try:
            buf = bytes(b & 0xFF for b in getattr(self, 'databuf', None) or ())
        except TypeError:
            return
        rw = 'READ' if getattr(self, 'is_read', False) else 'WRITE'
        reg = getattr(self, 'reg_ptr', None)
        if reg is None:
            reg = getattr(self, 'reg_byte', None)
        if reg is not None:
            super().put(ss, es, out_py, ('REG_' + rw, (reg, buf)))
        else:
            super().put(ss, es, out_py, ('I2C_' + rw, (getattr(self, 'addr', None), buf)))

    def __init__(self):
        self.reset()

    def reset(self):
        self.state      = 'IDLE'
        self.addr       = None
        self.is_read    = False
        self.ss_block   = None
        self.data_bytes = []

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype in ('START', 'START REPEAT'):
            self.state      = 'GET_ADDR'
            self.ss_block   = ss
            self.data_bytes = []

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = addr
            self.is_read = (ptype == 'ADDRESS READ')
            self.state   = 'GET_DATA'
            if self.is_read:
                # read_cycle start: the ADDRESS READ that begins the 2-byte
                # read transaction.
                self.put(ss, es, self.out_ann, [ANN_READ_CYCLE_START, ['read_cycle_start: ADDRESS READ begins a read', 'read_cycle_start', 'RC\u25b6']])

        elif ptype in ('DATA READ', 'DATA WRITE') and self.state == 'GET_DATA':
            self.data_bytes.append(pdata)

        elif ptype == 'STOP':
            if self.state != 'GET_DATA':
                self.state = 'IDLE'
                return

            if len(self.data_bytes) != 2:
                self._warn(self.ss_block, es,
                           'PCF8575 expects exactly 2 data bytes, got %d' % len(self.data_bytes))
                self.state = 'IDLE'
                return

            byte0 = self.data_bytes[0]  # Port 0: P07–P00
            byte1 = self.data_bytes[1]  # Port 1: P17–P10
            hx    = '0x%02X 0x%02X' % (byte0, byte1)
            pins0 = _fmt_port_pins(0, byte0)
            pins1 = _fmt_port_pins(1, byte1)

            if self.is_read:
                self.put(self.ss_block, es, self.out_ann,
                         [ANN_READ,
                          ['PCF8575 Read %s: P1=%s  P0=%s' % (hx, pins1, pins0),
                           'R %s' % hx,
                           'R']])
                # read_cycle done: the STOP that ends the read transaction
                # started by the ANN_READ_CYCLE_START annotation above.
                self.put(ss, es, self.out_ann, [ANN_READ_CYCLE_DONE, ['read_cycle_done: STOP ended the read', 'read_cycle_done', 'RC\u2713']])
            else:
                self.put(self.ss_block, es, self.out_ann,
                         [ANN_WRITE,
                          ['PCF8575 Write %s: P1=%s  P0=%s' % (hx, pins1, pins0),
                           'W %s' % hx,
                           'W']])

            self.state = 'IDLE'
            self.data_bytes = []
