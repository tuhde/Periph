import sigrokdecode as srd

ANN_REQUEST = 0
ANN_REPLY = 1
ANN_FIELD = 2
ANN_DATAGRAM_START = 3
ANN_DATAGRAM_END = 4
ANN_WARNING = 5

REGISTERS = {
    0x00: 'GCONF', 0x01: 'GSTAT', 0x04: 'IOIN', 0x10: 'IHOLD_IRUN', 0x11: 'TPOWERDOWN',
    0x12: 'TSTEP', 0x13: 'TPWMTHRS', 0x14: 'TCOOLTHRS', 0x15: 'THIGH', 0x2D: 'XDIRECT',
    0x33: 'VDCMIN', 0x68: 'MSLUTSEL', 0x69: 'MSLUTSTART', 0x6A: 'MSCNT', 0x6B: 'MSCURACT',
    0x6C: 'CHOPCONF', 0x6D: 'COOLCONF', 0x6E: 'DCCTRL', 0x6F: 'DRV_STATUS', 0x70: 'PWMCONF',
    0x71: 'PWM_SCALE', 0x72: 'ENCM_CTRL', 0x73: 'LOST_STEPS',
}
for _i in range(8):
    REGISTERS[0x60 + _i] = 'MSLUT%d' % _i


def _signed(value, bits):
    value &= (1 << bits) - 1
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


def _bits(value, hi, lo):
    return (value >> lo) & ((1 << (hi - lo + 1)) - 1)


def _with_short(strings):
    """Data/field annotations carry >= 3 tiers (long, medium, short); derive the
    missing tier from the leading name token (<= 8 chars)."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings
    text = strings[-1].strip()
    n = 0
    while n < len(text) and (text[n].isalnum() or text[n] == '_'):
        n += 1
    short = text[:min(n, 8)] or text[:8]
    return strings + [short] if short != strings[-1] else strings + [short[:4] or short]


def _warn_tag(msg):
    return 'LEN?' if 'byte' in msg else 'WARN'


def decode_status(byte):
    """SPI_STATUS reply byte -> list of set flag names."""
    flags = []
    for bit, name in ((0, 'reset_flag'), (1, 'driver_error'), (2, 'sg2'), (3, 'standstill')):
        if byte & (1 << bit):
            flags.append(name)
    return flags


def decode_fields(reg, value):
    """Return a list of (long, short) text pairs for the bit fields of the
    registers this decoder details."""
    out = []
    if reg == 0x00:
        names = [(0, 'I_scale_analog'), (1, 'internal_Rsense'), (2, 'en_pwm_mode'),
                 (3, 'enc_commutation'), (4, 'shaft'), (5, 'diag0_error'), (6, 'diag0_otpw'),
                 (7, 'diag0_stall'), (8, 'diag1_stall'), (9, 'diag1_index'),
                 (10, 'diag1_onstate'), (11, 'diag1_steps_skipped'), (12, 'diag0_pushpull'),
                 (13, 'diag1_pushpull'), (14, 'small_hysteresis'), (15, 'stop_enable'),
                 (16, 'direct_mode'), (17, 'test_mode')]
        for bit, name in names:
            out.append(('%s=%d' % (name, (value >> bit) & 1), name[:8]))
    elif reg == 0x01:
        for bit, name in ((0, 'reset'), (1, 'drv_err'), (2, 'uv_cp')):
            out.append(('%s=%d' % (name, (value >> bit) & 1), name))
    elif reg == 0x04:
        for bit, name in ((0, 'STEP'), (1, 'DIR'), (2, 'DCEN_CFG4'), (3, 'DCIN_CFG5'),
                          (4, 'DRV_ENN_CFG6'), (5, 'DCO')):
            out.append(('%s=%d' % (name, (value >> bit) & 1), name[:8]))
        out.append(('VERSION=0x%02X' % _bits(value, 31, 24), 'VERSION'))
    elif reg == 0x10:
        ih, ir, delay = _bits(value, 4, 0), _bits(value, 12, 8), _bits(value, 19, 16)
        out.append(('IHOLD=%d (%d/32)' % (ih, ih + 1), 'IHOLD'))
        out.append(('IRUN=%d (%d/32)' % (ir, ir + 1), 'IRUN'))
        out.append(('IHOLDDELAY=%d' % delay, 'DELAY'))
    elif reg == 0x2D:
        out.append(('coil A=%d coil B=%d' % (_signed(value, 9), _signed(value >> 16, 9)), 'XDIRECT'))
    elif reg == 0x6B:
        out.append(('CUR_A=%d CUR_B=%d' % (_signed(value >> 16, 9), _signed(value, 9)), 'MSCURACT'))
    elif reg == 0x6C:
        mres = _bits(value, 27, 24)
        micro = 256 >> mres if mres <= 8 else 1
        out.append(('MRES=%d (%d microsteps)' % (mres, micro), 'MRES'))
        out.append(('TOFF=%d%s' % (_bits(value, 3, 0), ' (driver disabled)' if _bits(value, 3, 0) == 0 else ''), 'TOFF'))
        out.append(('TBL=%d chm=%d' % (_bits(value, 16, 15), (value >> 14) & 1), 'TBL'))
        out.append(('HSTRT=%d HEND=%d' % (_bits(value, 6, 4), _bits(value, 10, 7)), 'HYST'))
        out.append(('vsense=%d intpol=%d dedge=%d' % ((value >> 17) & 1, (value >> 28) & 1, (value >> 29) & 1), 'vsense'))
        out.append(('SYNC=%d vhighfs=%d vhighchm=%d diss2g=%d' % (
            _bits(value, 23, 20), (value >> 18) & 1, (value >> 19) & 1, (value >> 30) & 1), 'SYNC'))
    elif reg == 0x6D:
        out.append(('sgt=%d sfilt=%d' % (_signed(_bits(value, 22, 16), 7), (value >> 24) & 1), 'sgt'))
        semin = _bits(value, 3, 0)
        out.append(('semin=%d%s semax=%d' % (semin, ' (CoolStep off)' if semin == 0 else '', _bits(value, 11, 8)), 'semin'))
        out.append(('seup=%d sedn=%d seimin=%d' % (_bits(value, 6, 5), _bits(value, 14, 13), (value >> 15) & 1), 'seup'))
    elif reg == 0x6F:
        cs = _bits(value, 20, 16)
        out.append(('SG_RESULT=%d' % _bits(value, 9, 0), 'SG'))
        out.append(('CS_ACTUAL=%d (%d/32)' % (cs, cs + 1), 'CS'))
        out.append(('stst=%d fsactive=%d StallGuard=%d' % ((value >> 31) & 1, (value >> 15) & 1, (value >> 24) & 1), 'stst'))
        out.append(('otpw=%d ot=%d' % ((value >> 26) & 1, (value >> 25) & 1), 'temp'))
        out.append(('s2ga=%d s2gb=%d ola=%d olb=%d' % (
            (value >> 27) & 1, (value >> 28) & 1, (value >> 29) & 1, (value >> 30) & 1), 'short'))
    elif reg == 0x70:
        out.append(('PWM_AMPL=%d PWM_GRAD=%d' % (_bits(value, 7, 0), _bits(value, 15, 8)), 'PWM'))
        out.append(('pwm_freq=%d autoscale=%d symmetric=%d' % (_bits(value, 17, 16), (value >> 18) & 1, (value >> 19) & 1), 'pwmf'))
        out.append(('freewheel=%d' % _bits(value, 21, 20), 'fw'))
    return out


class Decoder(srd.Decoder):
    api_version = 3
    id = 'tmc2130'
    name = 'TMC2130'
    longname = 'Trinamic TMC2130 stepper driver'
    desc = 'Decode TMC2130 40-bit SPI datagrams.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['tmc2130']
    tags = ['Motor', 'IC']

    annotations = (
        ('request', 'Host request'),
        ('reply', 'Chip reply'),
        ('field', 'Decoded field'),
        ('datagram_start', 'datagram_start timing marker'),
        ('datagram_end', 'datagram_end timing marker'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_REQUEST, ANN_REPLY)),
        ('fields', 'Fields', (ANN_FIELD,)),
        ('timing', 'Timing', (ANN_DATAGRAM_START, ANN_DATAGRAM_END)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid not in ('timing', 'warnings') for c in classes}
            if data[0] in tiered:
                data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.mosi = []
        self.miso = []
        self.spans = []
        self.active = False
        self.prev = None   # ('read', reg) or ('write', value) of the previous datagram

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        if ptype == 'CS_ASSERT':
            self.mosi, self.miso, self.spans = [], [], []
            self.active = True
            self.put(ss, es, self.out_ann, [ANN_DATAGRAM_START,
                                            ['datagram_start: CSN falling edge', 'datagram_start', '▶DG']])
        elif ptype == 'DATA':
            if not self.active:
                return
            mosi, miso = pdata
            self.mosi.append(mosi & 0xFF)
            self.miso.append(miso & 0xFF)
            self.spans.append((ss, es))
        elif ptype == 'CS_DEASSERT':
            if self.active:
                self.put(ss, es, self.out_ann, [ANN_DATAGRAM_END,
                                                ['datagram_end: CSN rising edge', 'datagram_end', 'DG◀']])
                self._datagram()
            self.active = False

    def _datagram(self):
        if not self.spans:
            return
        ss, es = self.spans[0][0], self.spans[-1][1]
        if len(self.mosi) != 5:
            self._warn(ss, es, 'Datagram has %d bytes (5 expected)' % len(self.mosi))
            self.prev = None
            return
        addr = self.mosi[0]
        reg = addr & 0x7F
        is_write = bool(addr & 0x80)
        value = int.from_bytes(bytes(self.mosi[1:5]), 'big')
        name = REGISTERS.get(reg, 'reg 0x%02X' % reg)
        reply = int.from_bytes(bytes(self.miso[1:5]), 'big')
        status = decode_status(self.miso[0])

        # The reply answers the previous datagram.
        if self.prev is not None:
            if self.prev[0] == 'read':
                what = 'reply to %s read = 0x%08X' % (REGISTERS.get(self.prev[1], 'reg 0x%02X' % self.prev[1]), reply)
                self.put(self.spans[1][0], es, self.out_ann, [ANN_REPLY, [what, 'R %s' % REGISTERS.get(self.prev[1], '?')]])
                for long_text, short_text in decode_fields(self.prev[1], reply):
                    self.put(self.spans[1][0], es, self.out_ann, [ANN_FIELD, [long_text, short_text]])
                self.put(self.spans[1][0], es, self.out_python, ('REG_READ', (self.prev[1], reply)))
            else:
                self.put(self.spans[1][0], es, self.out_ann, [ANN_REPLY, [
                    'mirror of write 0x%08X' % reply, 'mirror 0x%08X' % reply]])
                if reply != self.prev[1]:
                    self._warn(self.spans[1][0], es, 'Write mirror 0x%08X differs from written 0x%08X' % (reply, self.prev[1]))
        text = 'status: ' + (', '.join(status) if status else 'none')
        self.put(self.spans[0][0], self.spans[0][1], self.out_ann, [ANN_FIELD, [text, 'status']])

        if is_write:
            self.put(ss, es, self.out_ann, [ANN_REQUEST, ['Write %s = 0x%08X' % (name, value), 'W %s 0x%08X' % (name, value)]])
            for long_text, short_text in decode_fields(reg, value):
                self.put(ss, es, self.out_ann, [ANN_FIELD, [long_text, short_text]])
            self.put(ss, es, self.out_python, ('REG_WRITE', (reg, value)))
            self.prev = ('write', value)
        else:
            self.put(ss, es, self.out_ann, [ANN_REQUEST, ['Read request %s' % name, 'R? %s' % name]])
            self.prev = ('read', reg)
