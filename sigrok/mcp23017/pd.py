import sigrokdecode as srd

ADDRS = set(range(0x20, 0x28))

REGISTERS = {
    0x00: 'IODIRA',   0x01: 'IODIRB',
    0x02: 'IPOLA',    0x03: 'IPOLB',
    0x04: 'GPINTENA', 0x05: 'GPINTENB',
    0x06: 'DEFVALA',  0x07: 'DEFVALB',
    0x08: 'INTCONA',  0x09: 'INTCONB',
    0x0A: 'IOCON',    0x0B: 'IOCON',
    0x0C: 'GPPUA',    0x0D: 'GPPUB',
    0x0E: 'INTFA',    0x0F: 'INTFB',
    0x10: 'INTCAPA',  0x11: 'INTCAPB',
    0x12: 'GPIOA',    0x13: 'GPIOB',
    0x14: 'OLATA',    0x15: 'OLATB',
}

READONLY = {0x0E, 0x0F, 0x10, 0x11}

ANN_REG  = 0
ANN_WARN = 1


def _ann_reg(reg, byte):
    name = REGISTERS.get(reg, 'REG 0x%02X' % reg)
    if reg in (0x00, 0x01):
        port = 'A' if reg == 0x00 else 'B'
        ins  = ','.join('GP%s%d' % (port, i) for i in range(7) if (byte >> i) & 1)
        outs = ','.join('GP%s%d' % (port, i) for i in range(7) if not (byte >> i) & 1)
        detail = 'IN=%s OUT=%s' % (ins or 'none', outs or 'none')
    elif reg in (0x0C, 0x0D):
        port = 'A' if reg == 0x0C else 'B'
        detail = 'PU=' + ','.join('GP%s%d' % (port, i) for i in range(8) if (byte >> i) & 1) or 'none'
    elif reg == 0x0A:
        detail = 'BANK=%d MIRROR=%d ODR=%d INTPOL=%d' % (
            (byte >> 7) & 1, (byte >> 6) & 1, (byte >> 2) & 1, (byte >> 1) & 1)
    elif reg in (0x0E, 0x0F):
        detail = 'INT=%s' % ','.join('GP%s%d' % ('AB'[reg & 1], i) for i in range(8) if (byte >> i) & 1) or 'none'
    elif reg in (0x10, 0x11):
        detail = 'CAP=0x%02X' % byte
    elif reg in (0x12, 0x13, 0x14, 0x15):
        port = 'A' if reg in (0x12, 0x14) else 'B'
        detail = ','.join('GP%s%d=%d' % (port, i, (byte >> i) & 1) for i in range(8))
    else:
        detail = '0x%02X' % byte
    return '%s: %s' % (name, detail)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mcp23017'
    name = 'MCP23017'
    longname = 'MCP23017 16-bit I/O expander'
    desc = 'Decode MCP23017 16-bit I2C I/O expander register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mcp23017']
    tags = ['IC']

    annotations = (
        ('reg',  'Register'),
        ('warn', 'Warning'),
    )
    annotation_rows = (
        ('data',    'Data',    (ANN_REG,)),
        ('warnings','Warnings',(ANN_WARN,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_byte = None
        self.data_byte = None
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg]])

    def decode(self):
        while True:
            ptype, pdata = self.wait()

            if ptype in ('START', 'START REPEAT'):
                self.state    = 'GET_ADDR'
                self.ss_block = self.ss
                self.reg_byte  = None
                self.data_byte = None

            elif ptype in ('ADDRESS WRITE', 'ADDRESS READ'):
                addr = pdata[0]
                if addr not in ADDRS:
                    self.state = 'IDLE'
                    continue
                self.addr    = addr
                self.is_read = (ptype == 'ADDRESS READ')
                self.state   = 'GET_REG'
                self.data_byte = None

            elif ptype == 'DATA WRITE' and self.state == 'GET_REG':
                self.reg_byte = pdata[0]
                if self.is_read:
                    self.state = 'WAIT_STOP'
                else:
                    self.state = 'GET_DATA'

            elif ptype == 'DATA WRITE' and self.state == 'GET_DATA':
                self._warn(self.ss, self.es, 'Unexpected extra byte in write transaction')
                self.state = 'IDLE'

            elif ptype == 'DATA READ' and self.state in ('WAIT_STOP', 'GET_DATA'):
                if self.state == 'WAIT_STOP':
                    self.data_byte = pdata[0]
                    self.state = 'GET_STOP'
                else:
                    self._warn(self.ss, self.es, 'Unexpected extra read byte')

            elif ptype == 'STOP':
                if self.state not in ('GET_DATA', 'GET_STOP'):
                    self.state = 'IDLE'
                    continue

                if self.is_read:
                    if self.reg_byte not in REGISTERS:
                        self.state = 'IDLE'
                        continue
                    reg  = self.reg_byte
                    data = self.data_byte if self.data_byte is not None else 0
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_REG, ['R %s [0x%02X]' % (REGISTERS.get(reg, 'REG'), reg),
                                         'R [0x%02X]' % reg]])
                else:
                    if self.reg_byte not in REGISTERS:
                        self.state = 'IDLE'
                        continue
                    reg  = self.reg_byte
                    data = self.data_byte if self.data_byte is not None else 0
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_REG, ['W %s [0x%02X]' % (REGISTERS.get(reg, 'REG'), reg),
                                         'W [0x%02X]' % reg]])

                self.state    = 'IDLE'
                self.reg_byte  = None
                self.data_byte = None