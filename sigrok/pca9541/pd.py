import sigrokdecode as srd

REGISTERS = {0x00: 'IE', 0x01: 'CONTROL', 0x02: 'ISTAT'}

ADDRS = tuple(range(0x70, 0x80))

ANN_REG  = 0
ANN_WARN = 1


def _cmd(byte):
    """Decode a command code: (register number, auto-increment, valid)."""
    reg = byte & 0x03
    ai = bool(byte & 0x10)
    valid = (byte & 0xEC) == 0 and reg != 3
    return reg, ai, valid


def _decode_ie(v):
    masked = [n for bit, n in ((8, 'BUSLOST'), (4, 'BUSOK'), (2, 'BUSINIT'), (1, 'INTIN')) if v & bit]
    return 'masked: ' + (', '.join(masked) if masked else 'none')


def _control_state(v):
    mybus, nmybus = v & 1, (v >> 1) & 1
    buson, nbuson = (v >> 2) & 1, (v >> 3) & 1
    control = 'has control' if mybus == nmybus else 'no control'
    bus = 'bus on' if buson != nbuson else 'bus off'
    return '%s, %s' % (control, bus)


def _decode_control(v):
    s = 'MYBUS=%d NMYBUS=%d BUSON=%d NBUSON=%d -> %s' % (v & 1, (v >> 1) & 1, (v >> 2) & 1, (v >> 3) & 1, _control_state(v))
    flags = [n for bit, n in ((0x10, 'BUSINIT'), (0x40, 'TESTON'), (0x80, 'NTESTON')) if v & bit]
    return s + (' ' + ' '.join(flags) if flags else '')


def _decode_istat(v):
    flags = [n for bit, n in ((0x80, 'NMYTEST'), (0x40, 'MYTEST'), (8, 'BUSLOST'), (4, 'BUSOK'), (2, 'BUSINIT'), (1, 'INTIN')) if v & bit]
    return ' '.join(flags) if flags else 'no flags'


def _decode(reg, v):
    return {0: _decode_ie, 1: _decode_control, 2: _decode_istat}[reg](v)


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
    id = 'pca9541'
    name = 'PCA9541'
    longname = 'PCA9541 2-to-1 I2C-bus master selector'
    desc = 'Decode PCA9541 I2C register transactions: IE, CONTROL and ISTAT with bus-control actions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pca9541']
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
        self.state = 'IDLE'
        self.ptr = None            # (register number, auto-increment) of the last command code written
        self.cmd = None
        self.data = []
        self.ss_block = None
        self.last_control = None   # last CONTROL value read, to name the action of a CONTROL write

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg, 'WARN']])

    def _action(self, w):
        r = self.last_control
        if r is None:
            return ''
        acts = []
        buson, nbuson_r = (w >> 2) & 1, (r >> 3) & 1
        mybus, nmybus_r = w & 1, (r >> 1) & 1
        acts.append('bus on' if buson != nbuson_r else 'bus off')
        acts.append('take control' if mybus == nmybus_r else 'give control')
        if w & 0x10:
            acts.append('init requested')
        return ' [' + ', '.join(acts) + ']'

    def _emit(self, ss, es, rw, reg, val):
        text = '%s %s [0x%02X] = 0x%02X: %s' % (rw, REGISTERS[reg], reg, val, _decode(reg, val))
        if rw == 'W' and reg == 1:
            text += self._action(val)
        self.put(ss, es, self.out_ann, [ANN_REG, [text, '%s %s' % (rw, REGISTERS[reg])]])
        self.put(ss, es, self.out_python, ('REG_READ' if rw == 'R' else 'REG_WRITE', (reg, val)))
        if rw == 'R' and reg == 1:
            self.last_control = val

    def _regs_for(self, start_reg, ai, count, rw):
        """Register of each byte: auto-increment reads wrap IE -> CONTROL -> ISTAT -> IE; writes stay at ISTAT."""
        regs = []
        r = start_reg
        for _ in range(count):
            regs.append(r)
            if ai:
                r = (r + 1) % 3 if rw == 'R' else min(r + 1, 2)
        return regs

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.ss_block = ss
            self.cmd = None
            self.data = []

        elif ptype == 'START REPEAT':
            if self.state == 'WAIT_DATA_W' and self.cmd is not None:
                self.ptr = self.cmd
            self.state = 'IDLE'
            self.ss_block = ss
            self.cmd = None
            self.data = []

        elif ptype == 'ADDRESS WRITE':
            self.state = 'WAIT_CMD' if pdata in ADDRS else 'IDLE'

        elif ptype == 'ADDRESS READ':
            if pdata not in ADDRS or self.ptr is None:
                self.state = 'IDLE'
                return
            self.cmd = self.ptr
            self.state = 'WAIT_DATA_R'

        elif ptype == 'DATA WRITE':
            if self.state == 'WAIT_CMD':
                reg, ai, valid = _cmd(pdata)
                if not valid:
                    self._warn(ss, es, 'Invalid command code 0x%02X (unused bits set or register 3)' % pdata)
                self.cmd = (reg, ai)
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
            if self.state == 'WAIT_STOP_W' and self.cmd is not None:
                regs = self._regs_for(self.cmd[0], self.cmd[1], len(self.data), 'W')
                for (bss, bes, val), reg in zip(self.data, regs):
                    if reg == 2:
                        self._warn(bss, bes, 'Write to ISTAT (read-only, not acknowledged)')
                        continue
                    self._emit(bss, bes, 'W', reg, val)
                self.ptr = self.cmd
            elif self.state == 'WAIT_DATA_W':
                self.ptr = self.cmd
            elif self.state == 'WAIT_STOP_R' and self.cmd is not None:
                regs = self._regs_for(self.cmd[0], self.cmd[1], len(self.data), 'R')
                for (bss, bes, val), reg in zip(self.data, regs):
                    self._emit(bss, bes, 'R', reg, val)
            self.state = 'IDLE'
            self.cmd = None
            self.data = []
