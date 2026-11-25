import os
import sys

import sigrokdecode as srd

# Share the register table with the I2C decoder (a sibling decoder directory).
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from mcp23008.regs import REGISTERS, ann_reg, next_reg, reg_warnings, with_short  # noqa: E402

ANN_REG  = 0
ANN_WARN = 1


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mcp23s08'
    name = 'MCP23S08'
    longname = 'MCP23S08 8-bit I/O expander (SPI)'
    desc = 'Decode MCP23S08 8-bit SPI I/O expander register transactions.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['mcp23s08']
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
        self.mosi = []      # (ss, es, byte)
        self.miso = []
        self.active = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg, 'WARN']])

    def _finish(self):
        mosi, miso = self.mosi, self.miso
        self.mosi, self.miso = [], []
        if len(mosi) < 2:
            return
        opcode = mosi[0][2]
        if opcode & 0xF8 != 0x40:
            return                       # not an MCP23S08 opcode
        addr = (opcode >> 1) & 3
        read = opcode & 1
        reg = mosi[1][2]
        if reg not in REGISTERS:
            self._warn(mosi[0][0], mosi[-1][1], 'Register address 0x%02X is outside 0x00-0x0A' % reg)
            return
        rw = 'R' if read else 'W'
        data = (miso if read else mosi)[2:]
        for i, (bss, bes, val) in enumerate(data):
            r = next_reg(reg, i)
            self.put(bss, bes, self.out_ann,
                     [ANN_REG, ['%s %s [0x%02X] = %s (dev %d)' % (rw, REGISTERS[r], r, ann_reg(r, val), addr),
                                '%s [0x%02X]' % (rw, r)]])
            self.put(bss, bes, self.out_python,
                     ('REG_READ' if read else 'REG_WRITE', (addr, r, val)))
            if not read:
                for msg in reg_warnings(r, val):
                    self._warn(bss, bes, msg)

    def decode(self, ss, es, data):
        ptype, d1, d2 = data
        if ptype == 'CS-CHANGE':
            self._finish()
            self.active = (d2 == 0)
        elif ptype == 'DATA' and self.active:
            self.mosi.append((ss, es, d1 if d1 is not None else 0))
            self.miso.append((ss, es, d2 if d2 is not None else 0))
