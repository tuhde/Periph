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
        ('warnings', 'Warnings', (ANN_WARNING,)),
        ('timing',   'Timing',   (ANN_READ_CYCLE_START, ANN_READ_CYCLE_DONE)),
    )

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

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

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
                self.put(ss, es, self.out_ann, [ANN_READ_CYCLE_START, ['read_cycle_start']])

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
                self.put(ss, es, self.out_ann, [ANN_READ_CYCLE_DONE, ['read_cycle_done']])
            else:
                self.put(self.ss_block, es, self.out_ann,
                         [ANN_WRITE,
                          ['PCF8575 Write %s: P1=%s  P0=%s' % (hx, pins1, pins0),
                           'W %s' % hx,
                           'W']])

            self.state = 'IDLE'
            self.data_bytes = []
