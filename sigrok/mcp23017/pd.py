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
        self.state     = 'IDLE'
        self.addr      = None
        self.reg_byte  = None
        self.data_byte = None
        self.ptr_reg   = None   # register saved from pointer-set phase for subsequent reads
        self.ss_block  = None
        self.ss        = None
        self.es        = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg]])

    def _emit(self, ss_block, es, rw, reg, val):
        self.put(ss_block, es, self.out_ann,
                 [ANN_REG, ['%s %s [0x%02X] = %s' % (rw, REGISTERS[reg], reg, _ann_reg(reg, val)),
                            '%s [0x%02X]' % (rw, reg)]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype == 'START':
            self.state     = 'IDLE'
            self.ss_block  = ss
            self.reg_byte  = None
            self.data_byte = None
            # ptr_reg preserved: separate-transaction reads follow a prior STOP

        elif ptype == 'START REPEAT':
            if self.state == 'WAIT_DATA_W':
                # ADDRESS WRITE + DATA WRITE(reg) + REPEAT: pointer set for repeated-start read
                self.ptr_reg = self.reg_byte
            self.state     = 'IDLE'
            self.ss_block  = ss
            self.reg_byte  = None
            self.data_byte = None

        elif ptype == 'ADDRESS WRITE':
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr  = addr
            self.state = 'WAIT_REG'

        elif ptype == 'ADDRESS READ':
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr      = addr
            self.reg_byte  = self.ptr_reg   # may be None if no prior pointer-set
            self.state     = 'WAIT_DATA_R'

        elif ptype == 'DATA WRITE':
            if self.state == 'WAIT_REG':
                self.reg_byte = pdata
                self.state    = 'WAIT_DATA_W'
            elif self.state == 'WAIT_DATA_W':
                self.data_byte = pdata
                self.state     = 'WAIT_STOP_W'
            else:
                self.state = 'IDLE'

        elif ptype == 'DATA READ':
            if self.state == 'WAIT_DATA_R':
                self.data_byte = pdata
                self.state     = 'WAIT_STOP_R'
            else:
                self.state = 'IDLE'

        elif ptype == 'STOP':
            if self.state == 'WAIT_STOP_W':
                reg = self.reg_byte
                if reg in REGISTERS:
                    val = self.data_byte if self.data_byte is not None else 0
                    self._emit(self.ss_block, es, 'W', reg, val)
                    if reg in READONLY:
                        self._warn(ss, es, 'Write to read-only register %s' % REGISTERS[reg])
                self.ptr_reg = None

            elif self.state == 'WAIT_DATA_W':
                # ADDRESS WRITE + DATA WRITE(reg) + STOP: pointer-set only, save for next read
                self.ptr_reg = self.reg_byte

            elif self.state == 'WAIT_STOP_R':
                reg = self.reg_byte
                if reg is not None and reg in REGISTERS:
                    val = self.data_byte if self.data_byte is not None else 0
                    self._emit(self.ss_block, es, 'R', reg, val)
                self.ptr_reg = None

            self.state     = 'IDLE'
            self.reg_byte  = None
            self.data_byte = None