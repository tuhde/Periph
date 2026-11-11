import sigrokdecode as srd

REGISTERS = {
    0x00: 'INPUT0',
    0x01: 'INPUT1',
    0x02: 'OUTPUT0',
    0x03: 'OUTPUT1',
    0x04: 'POLARITY0',
    0x05: 'POLARITY1',
    0x06: 'CONFIG0',
    0x07: 'CONFIG1',
}

ADDRS = (0x20, 0x21)

ANN_REG  = 0
ANN_WARN = 1


def _pair_reg(start, index):
    """Register addressed by data byte `index` of a transfer starting at `start`:
    the two registers of a pair alternate (datasheet 8.1 / 8.2)."""
    return (start & ~1) | ((start & 1) ^ (index & 1))


def _pins(byte, port, bit_set=True):
    return ','.join('P%d_%d' % (port, i) for i in range(8) if bool((byte >> i) & 1) == bit_set) or 'none'


def _levels(byte, port):
    return ','.join('P%d_%d=%d' % (port, i, (byte >> i) & 1) for i in range(8))


def _decode(reg, byte):
    port = reg & 1
    kind = reg >> 1
    if kind == 3:
        return 'IN=%s OUT=%s' % (_pins(byte, port, True), _pins(byte, port, False))
    if kind == 2:
        return 'INV=%s' % _pins(byte, port, True)
    if kind in (0, 1):
        return _levels(byte, port)
    return '0x%02X' % byte


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); derive a
    missing short tier from the existing text so a narrow PulseView zoom still
    has something to show."""
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


class Decoder(srd.Decoder):
    api_version = 3
    id = 'pca6416a'
    name = 'PCA6416A'
    longname = 'PCA6416A 16-bit I/O expander (I2C/SMBus)'
    desc = 'Decode PCA6416A 16-bit I2C/SMBus I/O expander register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pca6416a']
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
            data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.ptr_reg  = None     # last pointer written; used by stand-alone reads (datasheet Figure 11)
        self.reg      = None     # first register of the transaction being decoded
        self.ss_block = None
        self.data     = []       # (ss, es, value) of data bytes in this transaction
        self.last_in  = {}       # previous INPUT0/INPUT1 values, for the changed-pin mask

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg, 'WARN']])

    def _emit(self, ss, es, rw, reg, val):
        text = '%s %s [0x%02X] = %s: %s' % (rw, REGISTERS[reg], reg, REGISTERS[reg], _decode(reg, val))
        if rw == 'R' and reg in (0x00, 0x01):
            prev = self.last_in.get(reg)
            if prev is not None and prev != val:
                text += ' changed=%s' % _pins(prev ^ val, reg & 1, True)
            self.last_in[reg] = val
        self.put(ss, es, self.out_ann, [ANN_REG, [text, '%s [0x%02X]' % (rw, reg)]])
        self.put(ss, es, self.out_python,
                 ('REG_READ' if rw == 'R' else 'REG_WRITE', (reg, val)))

    def _emit_transfer(self, rw):
        """Name every byte by its own register; a two-byte transfer also gets one
        combined 16-bit annotation (port 1 in the high byte)."""
        regs = [_pair_reg(self.reg, i) for i in range(len(self.data))]
        for (bss, bes, val), reg in zip(self.data, regs):
            self._emit(bss, bes, rw, reg, val)
            if rw == 'W' and reg in (0x00, 0x01):
                self._warn(bss, bes, 'Write to read-only register %s' % REGISTERS[reg])
        if len(self.data) >= 2 and regs[0] >> 1 == regs[1] >> 1:
            by_reg = {regs[0]: self.data[0][2], regs[1]: self.data[1][2]}
            base = regs[0] & ~1
            word = by_reg[base] | (by_reg[base + 1] << 8)
            name = REGISTERS[base][:-1]
            self.put(self.data[0][0], self.data[1][1], self.out_ann,
                     [ANN_REG, ['%s %s0/1 = 0x%04X' % (rw, name, word), '%s %s 0x%04X' % (rw, name, word)]])

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state    = 'IDLE'
            self.ss_block = ss
            self.reg      = None
            self.data     = []

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
            self.state = 'WAIT_REG'

        elif ptype == 'ADDRESS READ':
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.reg   = self.ptr_reg
            self.state = 'WAIT_DATA_R'

        elif ptype == 'DATA WRITE':
            if self.state == 'WAIT_REG':
                if pdata not in REGISTERS:
                    self._warn(ss, es, 'Register address 0x%02X is outside 0x00-0x07' % pdata)
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
                self._emit_transfer('W')
                self.ptr_reg = self.reg      # the pointer stays for stand-alone reads
            elif self.state == 'WAIT_DATA_W':
                self.ptr_reg = self.reg      # pointer-set only; the following read uses it
            elif self.state == 'WAIT_STOP_R' and self.reg is not None and self.reg in REGISTERS:
                self._emit_transfer('R')
            self.state = 'IDLE'
            self.reg   = None
            self.data  = []
