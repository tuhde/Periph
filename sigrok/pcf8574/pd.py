import sigrokdecode as srd

ADDRS_PCF8574  = set(range(0x20, 0x28))
ADDRS_PCF8574A = set(range(0x38, 0x40))
ALL_ADDRS = ADDRS_PCF8574 | ADDRS_PCF8574A

ANN_READ    = 0
ANN_WRITE   = 1
ANN_WARNING = 2


def _fmt_pins(byte):
    return ' '.join('P%d=%d' % (i, (byte >> i) & 1) for i in range(7, -1, -1))


def _chip_name(addr):
    return 'PCF8574A' if addr in ADDRS_PCF8574A else 'PCF8574'


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
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_READ, ANN_WRITE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

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
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def decode(self):
        while True:
            ptype, pdata = self.wait()

            if ptype in ('START', 'START REPEAT'):
                self.state    = 'GET_ADDR'
                self.ss_block = self.ss
                self.databyte = None

            elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
                addr = pdata[0]
                if addr not in ALL_ADDRS:
                    self.state = 'IDLE'
                    continue
                self.addr    = addr
                self.is_read = (ptype == 'ADDRESS READ')
                self.state   = 'GET_DATA'

            elif ptype in ('DATA READ', 'DATA WRITE') and self.state == 'GET_DATA':
                if self.databyte is not None:
                    self._warn(self.ss, self.es, 'Unexpected extra data byte')
                self.databyte = pdata[0]

            elif ptype == 'STOP':
                if self.state != 'GET_DATA' or self.databyte is None:
                    self.state = 'IDLE'
                    continue

                byte  = self.databyte
                hx    = '0x%02X' % byte
                pins  = _fmt_pins(byte)
                chip  = _chip_name(self.addr)

                if self.is_read:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_READ,
                              ['%s Read %s: %s' % (chip, hx, pins),
                               'R %s' % hx,
                               'R']])
                else:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WRITE,
                              ['%s Write %s: %s' % (chip, hx, pins),
                               'W %s' % hx,
                               'W']])

                self.state    = 'IDLE'
                self.databyte = None
