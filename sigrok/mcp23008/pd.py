import sigrokdecode as srd

from .regs import REGISTERS, NREGS, ann_reg, next_reg, reg_warnings, with_short

ADDRS = set(range(0x20, 0x28))

ANN_REG  = 0
ANN_WARN = 1


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mcp23008'
    name = 'MCP23008'
    longname = 'MCP23008 8-bit I/O expander (I2C)'
    desc = 'Decode MCP23008 8-bit I2C I/O expander register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mcp23008']
    tags = ['IC']

    annotations = (
        ('reg',  'Register'),
        ('warn', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG,)),
        ('warnings', 'Warnings', (ANN_WARN,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann and data[0] == ANN_REG:
            data = [data[0], with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.state   = 'IDLE'
        self.ptr_reg = None     # register pointer left by a pointer-set write, for later reads
        self.reg     = None     # register of the byte being decoded
        self.ss_block = None
        self.writing = False
        self.data    = []       # (ss, es, value) of data bytes in this transaction

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg, 'WARN']])

    def _emit(self, ss, es, rw, reg, val):
        self.put(ss, es, self.out_ann,
                 [ANN_REG, ['%s %s [0x%02X] = %s' % (rw, REGISTERS[reg], reg, ann_reg(reg, val)),
                            '%s [0x%02X]' % (rw, reg)]])
        self.put(ss, es, self.out_python,
                 ('REG_READ' if rw == 'R' else 'REG_WRITE', (reg, val)))

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state    = 'IDLE'
            self.ss_block = ss
            self.reg      = None
            self.data     = []
            # ptr_reg preserved: separate-transaction reads follow a prior STOP

        elif ptype == 'START REPEAT':
            if self.state == 'WAIT_DATA_W':
                self.ptr_reg = self.reg      # pointer-set write followed by a repeated-start read
            self.state    = 'IDLE'
            self.ss_block = ss
            self.reg      = None
            self.data     = []

        elif ptype == 'ADDRESS WRITE':
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.writing = True
            self.state   = 'WAIT_REG'

        elif ptype == 'ADDRESS READ':
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.writing = False
            self.reg     = self.ptr_reg
            self.state   = 'WAIT_DATA_R'

        elif ptype == 'DATA WRITE':
            if self.state == 'WAIT_REG':
                if pdata not in REGISTERS:
                    self._warn(ss, es, 'Register address 0x%02X is outside 0x00-0x0A' % pdata)
                self.reg   = pdata
                self.state = 'WAIT_DATA_W'
            elif self.state in ('WAIT_DATA_W', 'WAIT_STOP_W'):
                self.data.append((ss, es, pdata))
                self.state = 'WAIT_STOP_W'
            else:
                self.state = 'IDLE'

        elif ptype == 'DATA READ':
            if self.state in ('WAIT_DATA_R', 'WAIT_STOP_R'):
                self.data.append((ss, es, pdata))
                self.state = 'WAIT_STOP_R'
            else:
                self.state = 'IDLE'

        elif ptype == 'STOP':
            if self.state == 'WAIT_STOP_W' and self.reg in REGISTERS:
                for i, (bss, bes, val) in enumerate(self.data):
                    reg = next_reg(self.reg, i)
                    self._emit(bss, bes, 'W', reg, val)
                    for msg in reg_warnings(reg, val):
                        self._warn(bss, bes, msg)
                self.ptr_reg = None
            elif self.state == 'WAIT_DATA_W':
                self.ptr_reg = self.reg      # pointer-set only; the following read uses it
            elif self.state == 'WAIT_STOP_R' and self.reg is not None and self.reg in REGISTERS:
                for i, (bss, bes, val) in enumerate(self.data):
                    self._emit(bss, bes, 'R', next_reg(self.reg, i), val)
                self.ptr_reg = None
            else:
                self.ptr_reg = None
            self.state = 'IDLE'
            self.reg   = None
            self.data  = []
