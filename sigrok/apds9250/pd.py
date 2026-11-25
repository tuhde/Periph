import sigrokdecode as srd

REGISTERS = {
    0x00: 'MAIN_CTRL',
    0x04: 'LS_MEAS_RATE',
    0x05: 'LS_GAIN',
    0x06: 'PART_ID',
    0x07: 'MAIN_STATUS',
    0x0A: 'LS_DATA_IR_0', 0x0B: 'LS_DATA_IR_1', 0x0C: 'LS_DATA_IR_2',
    0x0D: 'LS_DATA_GREEN_0', 0x0E: 'LS_DATA_GREEN_1', 0x0F: 'LS_DATA_GREEN_2',
    0x10: 'LS_DATA_BLUE_0', 0x11: 'LS_DATA_BLUE_1', 0x12: 'LS_DATA_BLUE_2',
    0x13: 'LS_DATA_RED_0', 0x14: 'LS_DATA_RED_1', 0x15: 'LS_DATA_RED_2',
    0x19: 'INT_CFG',
    0x1A: 'INT_PERSISTENCE',
    0x21: 'LS_THRES_UP_0', 0x22: 'LS_THRES_UP_1', 0x23: 'LS_THRES_UP_2',
    0x24: 'LS_THRES_LOW_0', 0x25: 'LS_THRES_LOW_1', 0x26: 'LS_THRES_LOW_2',
    0x27: 'LS_THRES_VAR',
}
READ_ONLY = (0x06, 0x07) + tuple(range(0x0A, 0x16))

ADDR = 0x52

# Triples of consecutive 3-byte registers: first register -> (label, kind)
TRIPLES = {
    0x0A: ('IR', 'count'), 0x0D: ('GREEN', 'count'), 0x10: ('BLUE', 'count'), 0x13: ('RED', 'count'),
    0x21: ('THRES_UP', 'threshold'), 0x24: ('THRES_LOW', 'threshold'),
}

RESOLUTION = {0: (20, '400 ms'), 1: (19, '200 ms'), 2: (18, '100 ms'), 3: (17, '50 ms'),
              4: (16, '25 ms'), 5: (13, '3.125 ms')}
INTEGRATION_S = {20: 0.4, 19: 0.2, 18: 0.1, 17: 0.05, 16: 0.025, 13: 0.003125}
RATE = ('25 ms', '50 ms', '100 ms', '200 ms', '500 ms', '1000 ms', '2000 ms', '2000 ms')
GAIN = (1, 3, 6, 9, 18)
CHANNEL = ('IR', 'green/ALS', 'red', 'blue')

ANN_REG = 0
ANN_WARN = 1


def _count(b0, b1, b2):
    return b0 | (b1 << 8) | ((b2 & 0x0F) << 16)


def _decode(reg, v):
    if reg == 0x00:
        return 'SW_RESET=%d CS_MODE=%d LS_EN=%d' % ((v >> 4) & 1, (v >> 2) & 1, (v >> 1) & 1)
    if reg == 0x04:
        res = RESOLUTION.get((v >> 4) & 7)
        res_text = '%d bit (%s)' % res if res else 'reserved'
        return 'resolution %s, rate %s' % (res_text, RATE[v & 7])
    if reg == 0x05:
        g = v & 7
        return 'gain x%d' % GAIN[g] if g < 5 else 'gain reserved'
    if reg == 0x06:
        return 'part 0x%X revision 0x%X' % (v >> 4, v & 0x0F)
    if reg == 0x07:
        return 'POWER_ON=%d LS_INT=%d LS_DATA=%d' % ((v >> 5) & 1, (v >> 4) & 1, (v >> 3) & 1)
    if reg == 0x19:
        return 'channel %s, %s mode, LS_INT_EN=%d' % (CHANNEL[(v >> 4) & 3], 'variation' if (v >> 3) & 1 else 'threshold', (v >> 2) & 1)
    if reg == 0x1A:
        return 'persistence %d' % (((v >> 4) & 0x0F) + 1)
    if reg == 0x27:
        return 'variance %d counts' % (8 << (v & 7))
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
    id = 'apds9250'
    name = 'APDS-9250'
    longname = 'APDS-9250 digital RGB, IR and ambient light sensor (I2C)'
    desc = 'Decode APDS-9250 I2C register transactions: colour counts, configuration, thresholds and interrupt.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['apds9250']
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
        self.bits = 18             # resolution seen on the bus
        self.gain = 3              # gain seen on the bus
        self.var_code = 0
        self.int_cfg = 0
        self.data_flag = False     # last MAIN_STATUS read showed LS_DATA = 1

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg, 'WARN']])

    def _normalized(self, counts):
        t = INTEGRATION_S.get(self.bits, 0.1)
        return counts / (self.gain * t)

    def _track(self, rw, reg, val, ss, es):
        """Remember settings seen on the bus and raise register-level warnings."""
        if reg == 0x04:
            res = RESOLUTION.get((val >> 4) & 7)
            if res:
                self.bits = res[0]
        elif reg == 0x05 and (val & 7) < 5:
            self.gain = GAIN[val & 7]
        elif reg == 0x27:
            self.var_code = val & 7
        elif reg == 0x19:
            self.int_cfg = val
        elif reg == 0x06 and rw == 'R' and (val >> 4) != 0xB:
            self._warn(ss, es, 'PART_ID upper nibble 0x%X is not 0xB: not an APDS-9250' % (val >> 4))
        elif reg == 0x07 and rw == 'R':
            self.data_flag = bool((val >> 3) & 1)
        if reg in (0x19, 0x27, 0x04) and rw == 'W':
            var_on = (self.int_cfg & 0x0C) == 0x0C
            if var_on and self.var_code == 0 and self.bits == 13:
                self._warn(ss, es, 'Variation interrupt with a variance of 8 counts at 13 bit may fire on noise')

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
            if rw == 'R' and 0x0A <= addr <= 0x15 and not self.data_flag:
                self._warn(ss, es, 'Data registers read without a preceding MAIN_STATUS with LS_DATA = 1')
            self._track(rw, addr, val, ss, es)
            self._emit(ss, es, rw, addr, val)
            if addr in TRIPLES and i + 2 < len(items):
                label, kind = TRIPLES[addr]
                c = _count(val, items[i + 1][2], items[i + 2][2])
                if kind == 'count':
                    text = '%s count = %d (%.0f counts/gain-s)' % (label, c, self._normalized(c))
                else:
                    text = '%s = %d' % (label, c)
                self.put(ss, items[i + 2][1], self.out_ann, [ANN_REG, [text, label]])
                for j in (1, 2):
                    a2 = addr + j
                    s2, e2, v2 = items[i + j]
                    self._emit(s2, e2, rw, a2, v2)
                i += 3
                continue
            i += 1
        if rw == 'R' and any(0x0A <= reg + k <= 0x15 for k in range(len(items))):
            self.data_flag = False

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
