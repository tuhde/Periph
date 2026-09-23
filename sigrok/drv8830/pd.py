import sigrokdecode as srd

ADDRS = set(range(0x60, 0x69))

REG_CONTROL = 0x00
REG_FAULT = 0x01
REGISTERS = {REG_CONTROL: 'CONTROL', REG_FAULT: 'FAULT'}

VREF = 1.285
VSET_MIN = 6

FUNCTIONS = {0: 'Standby', 1: 'Forward', 2: 'Reverse', 3: 'Brake'}

ANN_DATA            = 0
ANN_STATUS          = 1
ANN_WARNING         = 2
# Named start/end pair for the register_write conformance check (see
# specs/motor/drv8830.md, "Timing Constraints" and "Sigrok Decoder", and
# specs/motor/drv8830_timing.conf). Additive: does not change the data/
# status/warning annotations PulseView's manual verification depends on.
ANN_WRITE_START     = 3
ANN_WRITE_DONE      = 4


def _decode_control(byte):
    vset = byte >> 2
    in1 = byte & 1
    in2 = (byte >> 1) & 1
    func = FUNCTIONS[byte & 0x03]
    if (byte & 0x03) in (0, 3):
        volts = 'unused in %s' % func
    elif vset < VSET_MIN:
        volts = 'reserved'
    else:
        volts = '%.2f V' % (VREF * vset / 16.0)
    return 'VSET=0x%02X (%s) IN1=%d IN2=%d -> %s' % (vset, volts, in1, in2, func)


def _decode_fault(byte):
    return ('FAULT=%d OCP=%d UVLO=%d OTS=%d ILIMIT=%d'
            % (byte & 1, (byte >> 1) & 1, (byte >> 2) & 1,
               (byte >> 3) & 1, (byte >> 4) & 1))


class Decoder(srd.Decoder):
    api_version = 3
    id = 'drv8830'
    name = 'DRV8830'
    longname = 'DRV8830 low-voltage motor driver with I2C interface'
    desc = 'Decode DRV8830 I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['drv8830']
    tags = ['IC']

    annotations = (
        ('data',                 'CONTROL register'),
        ('status',               'FAULT register'),
        ('warning',              'Warning'),
        ('register-write-start', 'Register write start'),
        ('register-write-done',  'Register write done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_WRITE_START, ANN_WRITE_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'     # IDLE | GET_REG | GET_DATA | READ | IGNORE
        self.reg = None          # register pointer, kept across transactions
        self.addr_ss = None
        self.addr_es = None
        self.wrote = False
        self.nbytes = 0

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, 'WARN']])

    def _emit(self, ss, es, direction, reg, byte):
        if reg not in REGISTERS:
            self._warn(ss, es, '%s of undefined register 0x%02X' % (direction, reg))
            return
        name = REGISTERS[reg]
        if reg == REG_CONTROL:
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['%s %s [0x%02X] %s' % (direction[0], name, byte, _decode_control(byte)),
                                 '%s CONTROL 0x%02X' % (direction[0], byte)]])
            if direction == 'Write' and (byte & 0x03) in (1, 2) and (byte >> 2) < VSET_MIN:
                self._warn(ss, es, 'Reserved VSET code 0x%02X with %s' % (byte >> 2, FUNCTIONS[byte & 0x03]))
        else:
            if direction == 'Write':
                text = 'CLEAR' if byte & 0x80 else 'no CLEAR (0x%02X)' % byte
                self.put(ss, es, self.out_ann,
                         [ANN_STATUS, ['W FAULT [0x%02X] %s' % (byte, text), 'W FAULT 0x%02X' % byte]])
            else:
                self.put(ss, es, self.out_ann,
                         [ANN_STATUS, ['R FAULT [0x%02X] %s' % (byte, _decode_fault(byte)),
                                       'R FAULT 0x%02X' % byte]])

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.wrote = False

        elif ptype == 'START REPEAT':
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata in ADDRS:
                self.state = 'GET_REG'
                self.addr_ss, self.addr_es = ss, es
                self.nbytes = 0
            else:
                self._warn(ss, es, 'Write to 0x%02X - outside 0x60-0x68, not a DRV8830' % pdata)
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            self.state = 'READ' if pdata in ADDRS else 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG':
                self.reg = pdata
                self.state = 'GET_DATA'
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Pointer -> %s (0x%02X)' % (REGISTERS.get(pdata, '?'), pdata),
                                     'PTR 0x%02X' % pdata]])
            elif self.state == 'GET_DATA':
                self.nbytes += 1
                if self.nbytes > 1:
                    self._warn(ss, es, 'Extra data byte - DRV8830 has no auto-increment')
                    return
                self._emit(ss, es, 'Write', self.reg, pdata)
                self.wrote = self.reg in REGISTERS

        elif ptype == 'DATA READ':
            if self.state == 'READ' and self.reg is not None:
                self._emit(ss, es, 'Read', self.reg, pdata)

        elif ptype == 'STOP':
            if self.wrote:
                # register_write: ADDRESS WRITE of a CONTROL/FAULT write
                # transaction through its STOP.
                self.put(self.addr_ss, self.addr_es, self.out_ann,
                         [ANN_WRITE_START, ['register_write_start', 'WR>']])
                self.put(ss, es, self.out_ann,
                         [ANN_WRITE_DONE, ['register_write_done', 'WR|']])
            self.state = 'IDLE'
            self.wrote = False
