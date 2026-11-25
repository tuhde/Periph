import sigrokdecode as srd

REGISTERS = {
    0x00: 'DEVICE_ID',
    0x01: 'CONFIG1',
    0x02: 'CONFIG2',
    0x03: 'CONFIG3',
    0x04: 'LOW_TH_0', 0x05: 'LOW_TH_1',
    0x06: 'HIGH_TH_0', 0x07: 'HIGH_TH_1',
    0x08: 'STATUS',
    0x09: 'GREEN_0', 0x0A: 'GREEN_1',
    0x0B: 'RED_0', 0x0C: 'RED_1',
    0x0D: 'BLUE_0', 0x0E: 'BLUE_1',
}
READ_ONLY = (0x08,) + tuple(range(0x09, 0x0F))

ADDR = 0x44
DEVICE_ID = 0x7D
RESET_VALUE = 0x46

# Pairs of consecutive 16-bit registers: first register -> (label, kind)
PAIRS = {
    0x04: ('LOW_TH', 'threshold'), 0x06: ('HIGH_TH', 'threshold'),
    0x09: ('GREEN', 'count'), 0x0B: ('RED', 'count'), 0x0D: ('BLUE', 'count'),
}

MODE = ('power-down', 'green only', 'red only', 'blue only', 'standby', 'green/red/blue',
        'green/red', 'green/blue')
CHANNEL = ('none', 'green', 'red', 'blue')
PERSISTENCE = (1, 2, 4, 8)

ANN_REG = 0
ANN_WARN = 1


def _word(b0, b1):
    return b0 | (b1 << 8)


def _decode(reg, v):
    if reg == 0x00:
        return 'device id 0x%02X%s' % (v, '' if v == DEVICE_ID else ' (expected 0x7D)')
    if reg == 0x01:
        return 'mode %s, range %s lux, %d bit, %s start' % (
            MODE[v & 7], '10000' if (v >> 3) & 1 else '375', 12 if (v >> 4) & 1 else 16,
            'INT' if (v >> 5) & 1 else 'I2C')
    if reg == 0x02:
        return 'IR compensation %s adjust %d' % ('106-169' if (v >> 7) & 1 else '0-63', v & 0x3F)
    if reg == 0x03:
        return 'channel %s, persistence %d, conversion interrupt %s' % (
            CHANNEL[v & 3], PERSISTENCE[(v >> 2) & 3], 'on' if (v >> 4) & 1 else 'off')
    if reg == 0x08:
        return 'RGBCF=%d BOUTF=%d CONVENF=%d RGBTHF=%d' % ((v >> 4) & 3, (v >> 2) & 1, (v >> 1) & 1, v & 1)
    return 'value 0x%02X' % v


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
    id = 'isl29125'
    name = 'ISL29125'
    longname = 'ISL29125 digital RGB colour light sensor (I2C)'
    desc = 'Decode ISL29125 I2C register transactions: colour counts, configuration, thresholds and interrupt.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['isl29125']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg', 'Register'),
        ('warn', 'Warning'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_REG,)),
        ('warnings', 'Warnings', (ANN_WARN,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann and data[0] == ANN_REG:
            data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.ptr_reg = None        # last register pointer written (used by stand-alone reads)
        self.reg = None
        self.data = []             # (ss, es, value) of the data bytes in this transaction
        self.conv_flag = False     # last STATUS read showed CONVENF = 1

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg, 'WARN']])

    def _track(self, rw, reg, val, ss, es):
        """Raise register-level warnings."""
        if reg == 0x00 and rw == 'W' and val != RESET_VALUE:
            self._warn(ss, es, 'Write of 0x%02X to DEVICE_ID: only 0x46 (reset) is valid' % val)
        elif reg == 0x00 and rw == 'R' and val != DEVICE_ID:
            self._warn(ss, es, 'DEVICE_ID 0x%02X is not 0x7D: not an ISL29125' % val)
        elif reg == 0x08 and rw == 'R':
            self.conv_flag = bool((val >> 1) & 1)
            if (val >> 2) & 1:
                self._warn(ss, es, 'BOUTF set: power-down or brown-out occurred, reconfigure the sensor')

    def _emit(self, ss, es, rw, reg, val):
        name = REGISTERS[reg]
        text = '%s %s [0x%02X] = 0x%02X: %s' % (rw, name, reg, val, _decode(reg, val))
        self.put(ss, es, self.out_ann, [ANN_REG, [text, '%s %s' % (rw, name)]])
        self.put(ss, es, self.out_python, ('REG_READ' if rw == 'R' else 'REG_WRITE', (reg, val)))

    def _flush(self, rw):
        """Annotate the collected bytes of one transaction, auto-incrementing the register."""
        reg = self.reg
        items = self.data
        if reg is None:
            return
        i = 0
        while i < len(items):
            addr = reg + i
            ss, es, val = items[i]
            if addr not in REGISTERS:
                if rw == 'W':
                    self._warn(ss, es, 'Write to reserved register 0x%02X' % addr)
                i += 1
                continue
            if rw == 'W' and addr in READ_ONLY:
                self._warn(ss, es, 'Write to read-only register %s' % REGISTERS[addr])
            if rw == 'R' and 0x09 <= addr <= 0x0E and not self.conv_flag:
                self._warn(ss, es, 'Data registers read without a preceding STATUS with CONVENF = 1')
            self._track(rw, addr, val, ss, es)
            self._emit(ss, es, rw, addr, val)
            if addr in PAIRS and i + 1 < len(items):
                label, kind = PAIRS[addr]
                c = _word(val, items[i + 1][2])
                text = '%s %s = %d' % (label, 'count' if kind == 'count' else '', c) if kind == 'count' else '%s = %d' % (label, c)
                self.put(ss, items[i + 1][1], self.out_ann, [ANN_REG, [text, label]])
                s2, e2, v2 = items[i + 1]
                self._emit(s2, e2, rw, addr + 1, v2)
                i += 2
                continue
            i += 1
        if rw == 'R' and any(0x09 <= reg + k <= 0x0E for k in range(len(items))):
            self.conv_flag = False

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.reg = None
            self.data = []

        elif ptype == 'START REPEAT':
            if self.state == 'WAIT_DATA_W':
                self.ptr_reg = self.reg     # pointer write followed by a repeated-start read
            self.state = 'IDLE'
            self.reg = None
            self.data = []

        elif ptype == 'ADDRESS WRITE':
            self.state = 'WAIT_REG' if pdata == ADDR else 'IDLE'

        elif ptype == 'ADDRESS READ':
            if pdata != ADDR:
                self.state = 'IDLE'
                return
            self.reg = self.ptr_reg
            self.state = 'WAIT_DATA_R'

        elif ptype == 'DATA WRITE':
            if self.state == 'WAIT_REG':
                self.reg = pdata
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
            if self.state == 'WAIT_STOP_W':
                self._flush('W')
                self.ptr_reg = self.reg
            elif self.state == 'WAIT_DATA_W':
                self.ptr_reg = self.reg
            elif self.state == 'WAIT_STOP_R':
                self._flush('R')
            self.state = 'IDLE'
            self.reg = None
            self.data = []
