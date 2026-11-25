import sigrokdecode as srd

REGISTERS = {
    0x00: 'INPUT',
    0x01: 'OUTPUT',
    0x02: 'POLARITY',
    0x03: 'CONFIG',
}

ADDRS = (0x41,)

ANN_REG  = 0
ANN_WARN = 1


def _pins(byte, bit_set=True):
    return ','.join('P%d' % i for i in range(4) if bool((byte >> i) & 1) == bit_set) or 'none'


def _levels(byte):
    return ','.join('P%d=%d' % (i, (byte >> i) & 1) for i in range(4))


def _decode(reg, byte):
    if reg == 0x03:
        return 'IN=%s OUT=%s' % (_pins(byte, True), _pins(byte, False))
    if reg == 0x02:
        return 'INV=%s' % _pins(byte, True)
    if reg in (0x00, 0x01):
        return _levels(byte)
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
    id = 'pca6408a'
    name = 'PCA6408A'
    longname = 'PCA6408A 8-bit I/O expander (I2C/SMBus)'
    desc = 'Decode PCA6408A 8-bit I2C/SMBus I/O expander register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pca6408a']
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
        self.reg      = None     # register of the transaction being decoded
        self.ss_block = None
        self.data     = []       # (ss, es, value) of data bytes in this transaction
        self.last_in  = None     # previous INPUT value, for the changed-pin mask

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg, 'WARN']])

    def _emit(self, ss, es, rw, reg, val):
        val &= 0xFF
        text = '%s %s [0x%02X] = %s: %s' % (rw, REGISTERS[reg], reg, REGISTERS[reg], _decode(reg, val))
        if rw == 'R' and reg == 0x00:
            if self.last_in is not None and (self.last_in ^ val) & 0x0F:
                text += ' changed=%s' % _pins((self.last_in ^ val) & 0x0F, True)
            self.last_in = val
        self.put(ss, es, self.out_ann, [ANN_REG, [text, '%s [0x%02X]' % (rw, reg)]])
        self.put(ss, es, self.out_python,
                 ('REG_READ' if rw == 'R' else 'REG_WRITE', (reg, val)))

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
                if pdata & 0xFC:
                    self._warn(ss, es, 'Command byte 0x%02X has bits 7:2 set (only B1:B0 select the register)' % pdata)
                pdata &= 0x03
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
                if len(self.data) > 1:
                    self._warn(self.data[1][0], self.data[-1][1],
                               'Multi-byte write: no auto-increment, the same register %s is rewritten'
                               % REGISTERS[self.reg])
                for bss, bes, val in self.data:
                    self._emit(bss, bes, 'W', self.reg, val)
                    if self.reg == 0x00:
                        self._warn(bss, bes, 'Write to read-only register INPUT')
                    elif (val & 0xF0) != (0x00 if self.reg == 0x02 else 0xF0):
                        self._warn(bss, bes, 'Unused bits 7:4 of %s written with 0x%X (reset value is 0x%X)'
                                   % (REGISTERS[self.reg], val >> 4, 0x0 if self.reg == 0x02 else 0xF))
                self.ptr_reg = self.reg      # the pointer stays for stand-alone reads
            elif self.state == 'WAIT_DATA_W':
                self.ptr_reg = self.reg      # pointer-set only; the following read uses it
            elif self.state == 'WAIT_STOP_R' and self.reg is not None and self.reg in REGISTERS:
                if len(self.data) > 1:
                    self._warn(self.data[1][0], self.data[-1][1],
                               'Multi-byte read: no auto-increment, register %s repeats'
                               % REGISTERS[self.reg])
                for bss, bes, val in self.data:
                    self._emit(bss, bes, 'R', self.reg, val)
            self.state = 'IDLE'
            self.reg   = None
            self.data  = []
