import sigrokdecode as srd

ANN_DATAGRAM = 0
ANN_FIELD = 1
ANN_READ_REQUEST_END = 2
ANN_READ_REPLY_START = 3
ANN_WARNING = 4

REGISTERS = {
    0x00: 'GCONF', 0x01: 'GSTAT', 0x02: 'IFCNT', 0x03: 'NODECONF', 0x05: 'OTP_READ',
    0x06: 'IOIN', 0x07: 'FACTORY_CONF', 0x10: 'IHOLD_IRUN', 0x11: 'TPOWERDOWN',
    0x12: 'TSTEP', 0x13: 'TPWMTHRS', 0x22: 'VACTUAL', 0x6A: 'MSCNT', 0x6B: 'MSCURACT',
    0x6C: 'CHOPCONF', 0x6F: 'DRV_STATUS', 0x70: 'PWMCONF', 0x71: 'PWM_SCALE', 0x72: 'PWM_AUTO',
}

SYNC = 0x05
MASTER_ADDR = 0xFF


def _crc8(data):
    crc = 0
    for byte in data:
        for _ in range(8):
            if ((crc >> 7) ^ (byte & 1)):
                crc = ((crc << 1) ^ 0x07) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
            byte >>= 1
    return crc


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
    return 'CRC?' if 'CRC' in msg else ('SYNC?' if 'sync' in msg else 'WARN')


def decode_fields(reg, value):
    """Return a list of (long, short) text pairs for the bit fields of the
    registers this decoder details."""
    out = []
    if reg == 0x00:
        names = [(0, 'I_scale_analog'), (1, 'internal_Rsense'), (2, 'en_SpreadCycle'),
                 (3, 'shaft'), (4, 'index_otpw'), (5, 'index_step'), (6, 'pdn_disable'),
                 (7, 'mstep_reg_select'), (8, 'multistep_filt'), (9, 'test_mode')]
        for bit, name in names:
            out.append(('%s=%d' % (name, (value >> bit) & 1), name[:8]))
    elif reg == 0x01:
        for bit, name in ((0, 'reset'), (1, 'drv_err'), (2, 'uv_cp')):
            out.append(('%s=%d' % (name, (value >> bit) & 1), name))
    elif reg == 0x10:
        ih, ir, delay = _bits(value, 4, 0), _bits(value, 12, 8), _bits(value, 19, 16)
        out.append(('IHOLD=%d (%d/32)' % (ih, ih + 1), 'IHOLD'))
        out.append(('IRUN=%d (%d/32)' % (ir, ir + 1), 'IRUN'))
        out.append(('IHOLDDELAY=%d' % delay, 'DELAY'))
    elif reg == 0x22:
        out.append(('VACTUAL=%d' % _signed(value, 24), 'VACT'))
    elif reg == 0x6C:
        mres = _bits(value, 27, 24)
        micro = 256 >> mres if mres <= 8 else 1
        out.append(('MRES=%d (%d microsteps)' % (mres, micro), 'MRES'))
        out.append(('TOFF=%d%s' % (_bits(value, 3, 0), ' (driver disabled)' if _bits(value, 3, 0) == 0 else ''), 'TOFF'))
        out.append(('TBL=%d' % _bits(value, 16, 15), 'TBL'))
        out.append(('HSTRT=%d HEND=%d' % (_bits(value, 6, 4), _bits(value, 10, 7)), 'HYST'))
        out.append(('vsense=%d intpol=%d dedge=%d' % ((value >> 17) & 1, (value >> 28) & 1, (value >> 29) & 1), 'vsense'))
        out.append(('diss2g=%d diss2vs=%d' % ((value >> 30) & 1, (value >> 31) & 1), 'diss2'))
    elif reg == 0x6F:
        cs = _bits(value, 20, 16)
        out.append(('CS_ACTUAL=%d (%d/32)' % (cs, cs + 1), 'CS'))
        out.append(('stst=%d stealth=%d' % ((value >> 31) & 1, (value >> 30) & 1), 'stst'))
        out.append(('otpw=%d ot=%d t120=%d t143=%d t150=%d t157=%d' % (
            value & 1, (value >> 1) & 1, (value >> 8) & 1, (value >> 9) & 1, (value >> 10) & 1, (value >> 11) & 1), 'temp'))
        out.append(('s2ga=%d s2gb=%d s2vsa=%d s2vsb=%d' % (
            (value >> 2) & 1, (value >> 3) & 1, (value >> 4) & 1, (value >> 5) & 1), 'short'))
        out.append(('ola=%d olb=%d' % ((value >> 6) & 1, (value >> 7) & 1), 'open'))
    elif reg == 0x70:
        out.append(('PWM_OFS=%d PWM_GRAD=%d' % (_bits(value, 7, 0), _bits(value, 15, 8)), 'PWM'))
        out.append(('pwm_freq=%d autoscale=%d autograd=%d' % (_bits(value, 17, 16), (value >> 18) & 1, (value >> 19) & 1), 'pwmf'))
        out.append(('freewheel=%d PWM_REG=%d PWM_LIM=%d' % (_bits(value, 21, 20), _bits(value, 27, 24), _bits(value, 31, 28)), 'fw'))
    return out


class Decoder(srd.Decoder):
    api_version = 3
    id = 'tmc2208'
    name = 'TMC2208'
    longname = 'Trinamic TMC2202/TMC2208/TMC2224 UART'
    desc = 'Decode TMC220x single-wire UART write datagrams, read requests and read replies.'
    license = 'gplv2+'
    inputs = ['uart']
    outputs = ['tmc2208']
    tags = ['Motor', 'IC']

    annotations = (
        ('datagram', 'Datagram'),
        ('field', 'Decoded field'),
        ('read_request_end', 'read_request_end timing marker'),
        ('read_reply_start', 'read_reply_start timing marker'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('datagrams', 'Datagrams', (ANN_DATAGRAM,)),
        ('fields', 'Fields', (ANN_FIELD,)),
        ('timing', 'Timing', (ANN_READ_REQUEST_END, ANN_READ_REPLY_START)),
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
        self.buf = []
        self.spans = []
        self.last_host = None   # (bytes, es) of the previous host datagram
        self.pending_request_end = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def decode(self, ss, es, data):
        ptype, rxtx, pdata = data
        if ptype != 'DATA':
            return
        self._byte(pdata[0] & 0xFF, ss, es)

    def _frame_length(self):
        if len(self.buf) < 3:
            return None
        if self.buf[1] == MASTER_ADDR:
            return 8
        if self.buf[2] & 0x80:
            return 8
        return 4

    def _byte(self, byte, ss, es):
        if not self.buf:
            if byte != SYNC:
                self._warn(ss, es, 'Unexpected byte 0x%02X outside a datagram (sync 0x05 expected)' % byte)
                return
        self.buf.append(byte)
        self.spans.append((ss, es))
        length = self._frame_length()
        if length is not None and len(self.buf) == length:
            self._complete()

    def _complete(self):
        frame = bytes(self.buf)
        spans = self.spans
        self.buf, self.spans = [], []
        ss, es = spans[0][0], spans[-1][1]
        crc_ok = _crc8(frame[:-1]) == frame[-1]
        reg = frame[2] & 0x7F
        name = REGISTERS.get(reg, 'reg 0x%02X' % reg)

        if len(frame) == 8 and frame[1] == MASTER_ADDR:
            self._reply(frame, spans, ss, es, reg, name, crc_ok)
            return

        # Host datagram: skip a byte-identical immediate repeat (host echo).
        unit = max(1, spans[0][1] - spans[0][0])
        if self.last_host is not None and self.last_host[0] == frame and ss - self.last_host[1] <= 20 * unit:
            self.last_host = (frame, es)
            return
        self.last_host = (frame, es)

        if len(frame) == 8:
            value = int.from_bytes(frame[3:7], 'big')
            self.put(ss, es, self.out_ann, [ANN_DATAGRAM, [
                'Write %s = 0x%08X' % (name, value), 'W %s 0x%08X' % (name, value)]])
            self._fields(spans, reg, value)
            self.put(ss, es, self.out_python, ('REG_WRITE', (reg, value)))
            self.pending_request_end = None
        else:
            self.put(ss, es, self.out_ann, [ANN_DATAGRAM, [
                'Read request %s' % name, 'R? %s' % name]])
            self.pending_request_end = spans[-1]
            self.put(spans[-1][0], spans[-1][1], self.out_ann, [ANN_READ_REQUEST_END, [
                'read_request_end: last byte of the read request', 'read_request_end', '▶RQ']])
        if not crc_ok:
            self._warn(ss, es, 'CRC error in host datagram')

    def _reply(self, frame, spans, ss, es, reg, name, crc_ok):
        value = int.from_bytes(frame[3:7], 'big')
        if self.pending_request_end is not None:
            self.put(spans[0][0], spans[0][1], self.out_ann, [ANN_READ_REPLY_START, [
                'read_reply_start: first byte of the read reply', 'read_reply_start', '→RP']])
            self.pending_request_end = None
        self.put(ss, es, self.out_ann, [ANN_DATAGRAM, [
            'Reply %s = 0x%08X' % (name, value), 'R %s 0x%08X' % (name, value)]])
        self._fields(spans, reg, value)
        self.put(ss, es, self.out_python, ('REG_READ', (reg, value)))
        if not crc_ok:
            self._warn(ss, es, 'CRC error in reply datagram')

    def _fields(self, spans, reg, value):
        ss, es = spans[0][0], spans[-1][1]
        for long_text, short_text in decode_fields(reg, value):
            self.put(ss, es, self.out_ann, [ANN_FIELD, [long_text, short_text]])
