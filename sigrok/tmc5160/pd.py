import sigrokdecode as srd

ANN_REQUEST = 0
ANN_REPLY = 1
ANN_FIELD = 2
ANN_DATAGRAM_START = 3
ANN_DATAGRAM_END = 4
ANN_READ_REQUEST_END = 5
ANN_READ_REPLY_START = 6
ANN_WARNING = 7

REGISTERS = {
    0x00: 'GCONF', 0x01: 'GSTAT', 0x02: 'IFCNT', 0x03: 'SLAVECONF', 0x04: 'IOIN/OUTPUT',
    0x05: 'X_COMPARE', 0x06: 'OTP_PROG', 0x07: 'OTP_READ', 0x08: 'FACTORY_CONF',
    0x09: 'SHORT_CONF', 0x0A: 'DRV_CONF', 0x0B: 'GLOBALSCALER', 0x0C: 'OFFSET_READ',
    0x10: 'IHOLD_IRUN', 0x11: 'TPOWERDOWN', 0x12: 'TSTEP', 0x13: 'TPWMTHRS', 0x14: 'TCOOLTHRS',
    0x15: 'THIGH', 0x20: 'RAMPMODE', 0x21: 'XACTUAL', 0x22: 'VACTUAL', 0x23: 'VSTART', 0x24: 'A1',
    0x25: 'V1', 0x26: 'AMAX', 0x27: 'VMAX', 0x28: 'DMAX', 0x2A: 'D1', 0x2B: 'VSTOP',
    0x2C: 'TZEROWAIT', 0x2D: 'XTARGET', 0x33: 'VDCMIN', 0x34: 'SW_MODE', 0x35: 'RAMP_STAT',
    0x36: 'XLATCH', 0x38: 'ENCMODE', 0x39: 'X_ENC', 0x3A: 'ENC_CONST', 0x3B: 'ENC_STATUS',
    0x3C: 'ENC_LATCH', 0x3D: 'ENC_DEVIATION', 0x68: 'MSLUTSEL', 0x69: 'MSLUTSTART',
    0x6A: 'MSCNT', 0x6B: 'MSCURACT', 0x6C: 'CHOPCONF', 0x6D: 'COOLCONF', 0x6E: 'DCCTRL',
    0x6F: 'DRV_STATUS', 0x70: 'PWMCONF', 0x71: 'PWM_SCALE', 0x72: 'PWM_AUTO', 0x73: 'LOST_STEPS',
}
for _i in range(8):
    REGISTERS[0x60 + _i] = 'MSLUT%d' % _i

SYNC = 0x05
MASTER_ADDR = 0xFF
SIGNED_POSITION = {0x21: 'XACTUAL', 0x2D: 'XTARGET', 0x36: 'XLATCH', 0x39: 'X_ENC', 0x3C: 'ENC_LATCH', 0x05: 'X_COMPARE'}


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
    if 'CRC' in msg:
        return 'CRC?'
    if 'sync' in msg:
        return 'SYNC?'
    return 'LEN?' if 'byte' in msg else 'WARN'


def decode_status(byte):
    """SPI_STATUS reply byte -> list of set flag names (MSB first)."""
    names = ('status_stop_r', 'status_stop_l', 'position_reached', 'velocity_reached', 'standstill',
             'sg2', 'driver_error', 'reset_flag')
    return [n for i, n in enumerate(names) if byte & (1 << (7 - i))]


def decode_fields(reg, value, ioin_read=True):
    """Return a list of (long, short) text pairs for the bit fields of the
    registers this decoder details."""
    out = []
    if reg == 0x00:
        names = [(0, 'recalibrate'), (1, 'faststandstill'), (2, 'en_pwm_mode'), (3, 'multistep_filt'),
                 (4, 'shaft'), (5, 'diag0_error'), (6, 'diag0_otpw'), (7, 'diag0_stall/step'),
                 (8, 'diag1_stall/dir'), (9, 'diag1_index'), (10, 'diag1_onstate'),
                 (11, 'diag1_steps_skipped'), (12, 'diag0_int_pushpull'), (13, 'diag1_poscomp_pushpull'),
                 (14, 'small_hysteresis'), (15, 'stop_enable'), (16, 'direct_mode'), (17, 'test_mode')]
        for bit, name in names:
            out.append(('%s=%d' % (name, (value >> bit) & 1), name[:8]))
    elif reg == 0x01:
        for bit, name in ((0, 'reset'), (1, 'drv_err'), (2, 'uv_cp')):
            out.append(('%s=%d' % (name, (value >> bit) & 1), name))
    elif reg == 0x03:
        out.append(('SLAVEADDR=%d SENDDELAY=%d' % (_bits(value, 7, 0), _bits(value, 11, 8)), 'SLAVECONF'))
    elif reg == 0x04 and ioin_read:
        for bit, name in ((0, 'REFL_STEP'), (1, 'REFR_DIR'), (2, 'ENCB_DCEN_CFG4'), (3, 'ENCA_DCIN_CFG5'),
                          (4, 'DRV_ENN'), (5, 'ENCN_DCO_CFG6'), (6, 'SD_MODE'), (7, 'SWCOMP_IN')):
            out.append(('%s=%d' % (name, (value >> bit) & 1), name[:8]))
        out.append(('VERSION=0x%02X' % _bits(value, 31, 24), 'VERSION'))
    elif reg == 0x09:
        out.append(('S2VS_LEVEL=%d S2G_LEVEL=%d' % (_bits(value, 3, 0), _bits(value, 11, 8)), 'SHORT'))
        out.append(('shortfilter=%d shortdelay=%d' % (_bits(value, 17, 16), (value >> 18) & 1), 'filter'))
    elif reg == 0x0A:
        out.append(('BBMTIME=%d BBMCLKS=%d' % (_bits(value, 4, 0), _bits(value, 11, 8)), 'BBM'))
        out.append(('OTSELECT=%d DRVSTRENGTH=%d FILT_ISENSE=%d' % (_bits(value, 17, 16), _bits(value, 19, 18), _bits(value, 21, 20)), 'DRV'))
    elif reg == 0x0B:
        out.append(('GLOBALSCALER=%d (%s)' % (value & 0xFF, 'full scale' if (value & 0xFF) == 0 else '%d/256' % (value & 0xFF)), 'GSCALER'))
    elif reg == 0x10:
        ih, ir, delay = _bits(value, 4, 0), _bits(value, 12, 8), _bits(value, 19, 16)
        out.append(('IHOLD=%d (%d/32)' % (ih, ih + 1), 'IHOLD'))
        out.append(('IRUN=%d (%d/32)' % (ir, ir + 1), 'IRUN'))
        out.append(('IHOLDDELAY=%d' % delay, 'DELAY'))
    elif reg == 0x20:
        out.append(('RAMPMODE=%d (%s)' % (value & 3, ('positioning', 'velocity +', 'velocity -', 'hold')[value & 3]), 'RAMPMODE'))
    elif reg in SIGNED_POSITION:
        name = SIGNED_POSITION[reg]
        out.append(('%s=%d (%.4f full steps at 256 usteps)' % (name, _signed(value, 32), _signed(value, 32) / 256.0), name))
    elif reg == 0x22:
        out.append(('VACTUAL=%d' % _signed(value, 24), 'VACT'))
    elif reg == 0x34:
        out.append(('stop_l=%d stop_r=%d pol_stop_l=%d pol_stop_r=%d swap_lr=%d' % (
            value & 1, (value >> 1) & 1, (value >> 2) & 1, (value >> 3) & 1, (value >> 4) & 1), 'SW_MODE'))
        out.append(('latch_l_active=%d latch_l_inactive=%d latch_r_active=%d latch_r_inactive=%d en_latch_encoder=%d' % (
            (value >> 5) & 1, (value >> 6) & 1, (value >> 7) & 1, (value >> 8) & 1, (value >> 9) & 1), 'latch'))
        out.append(('sg_stop=%d en_softstop=%d' % ((value >> 10) & 1, (value >> 11) & 1), 'sg_stop'))
    elif reg == 0x35:
        names = ('status_stop_l', 'status_stop_r', 'status_latch_l', 'status_latch_r', 'event_stop_l', 'event_stop_r',
                 'event_stop_sg', 'event_pos_reached', 'velocity_reached', 'position_reached', 'vzero',
                 't_zerowait_active', 'second_move', 'status_sg')
        for bit, name in enumerate(names):
            out.append(('%s=%d' % (name, (value >> bit) & 1), name[:8]))
    elif reg == 0x38:
        names = ('pol_a', 'pol_b', 'pol_n', 'ignore_ab', 'clr_cont', 'clr_once', 'pos_neg_edge0', 'pos_neg_edge1',
                 'clr_enc_x', 'latch_x_act', 'enc_sel_decimal')
        for bit, name in enumerate(names):
            out.append(('%s=%d' % (name, (value >> bit) & 1), name[:8]))
    elif reg == 0x3B:
        out.append(('n_event=%d deviation_warn=%d' % (value & 1, (value >> 1) & 1), 'ENC_STAT'))
    elif reg == 0x6A:
        out.append(('MSCNT=%d' % _bits(value, 9, 0), 'MSCNT'))
    elif reg == 0x6B:
        out.append(('CUR_A=%d CUR_B=%d' % (_signed(value >> 16, 9), _signed(value, 9)), 'MSCURACT'))
    elif reg == 0x6C:
        mres = _bits(value, 27, 24)
        micro = 256 >> mres if mres <= 8 else 1
        out.append(('MRES=%d (%d microsteps)' % (mres, micro), 'MRES'))
        out.append(('TOFF=%d%s' % (_bits(value, 3, 0), ' (driver disabled)' if _bits(value, 3, 0) == 0 else ''), 'TOFF'))
        out.append(('TBL=%d chm=%d' % (_bits(value, 16, 15), (value >> 14) & 1), 'TBL'))
        out.append(('HSTRT/TFD=%d HEND/OFFSET=%d TFD3=%d' % (_bits(value, 6, 4), _bits(value, 10, 7), (value >> 11) & 1), 'HYST'))
        out.append(('TPFD=%d vhighfs=%d vhighchm=%d' % (_bits(value, 23, 20), (value >> 18) & 1, (value >> 19) & 1), 'TPFD'))
        out.append(('intpol=%d dedge=%d diss2g=%d diss2vs=%d' % (
            (value >> 28) & 1, (value >> 29) & 1, (value >> 30) & 1, (value >> 31) & 1), 'intpol'))
    elif reg == 0x6D:
        out.append(('sgt=%d sfilt=%d' % (_signed(_bits(value, 22, 16), 7), (value >> 24) & 1), 'sgt'))
        semin = _bits(value, 3, 0)
        out.append(('semin=%d%s semax=%d' % (semin, ' (CoolStep off)' if semin == 0 else '', _bits(value, 11, 8)), 'semin'))
        out.append(('seup=%d sedn=%d seimin=%d' % (_bits(value, 6, 5), _bits(value, 14, 13), (value >> 15) & 1), 'seup'))
    elif reg == 0x6F:
        cs = _bits(value, 20, 16)
        out.append(('SG_RESULT=%d' % _bits(value, 9, 0), 'SG'))
        out.append(('CS_ACTUAL=%d (%d/32)' % (cs, cs + 1), 'CS'))
        out.append(('stst=%d stealth=%d fsactive=%d StallGuard=%d' % (
            (value >> 31) & 1, (value >> 14) & 1, (value >> 15) & 1, (value >> 24) & 1), 'stst'))
        out.append(('otpw=%d ot=%d' % ((value >> 26) & 1, (value >> 25) & 1), 'temp'))
        out.append(('s2ga=%d s2gb=%d s2vsa=%d s2vsb=%d' % (
            (value >> 27) & 1, (value >> 28) & 1, (value >> 12) & 1, (value >> 13) & 1), 'short'))
        out.append(('ola=%d olb=%d' % ((value >> 29) & 1, (value >> 30) & 1), 'open'))
    elif reg == 0x70:
        out.append(('PWM_OFS=%d PWM_GRAD=%d' % (_bits(value, 7, 0), _bits(value, 15, 8)), 'PWM'))
        out.append(('pwm_freq=%d autoscale=%d autograd=%d' % (_bits(value, 17, 16), (value >> 18) & 1, (value >> 19) & 1), 'pwmf'))
        out.append(('freewheel=%d PWM_REG=%d PWM_LIM=%d' % (_bits(value, 21, 20), _bits(value, 27, 24), _bits(value, 31, 28)), 'fw'))
    elif reg == 0x71:
        out.append(('PWM_SCALE_SUM=%d PWM_SCALE_AUTO=%d' % (value & 0xFF, _signed(value >> 16, 9)), 'PWMSCALE'))
    elif reg == 0x72:
        out.append(('PWM_OFS_AUTO=%d PWM_GRAD_AUTO=%d' % (value & 0xFF, _bits(value, 23, 16)), 'PWMAUTO'))
    return out


class Decoder(srd.Decoder):
    api_version = 3
    id = 'tmc5160'
    name = 'TMC5160'
    longname = 'Trinamic TMC5160 stepper controller/driver'
    desc = 'Decode TMC5160 40-bit SPI datagrams or single-wire UART datagrams.'
    license = 'gplv2+'
    inputs = ['spi', 'uart']
    outputs = ['tmc5160']
    tags = ['Motor', 'IC']

    annotations = (
        ('request', 'Host request'),
        ('reply', 'Chip reply'),
        ('field', 'Decoded field'),
        ('datagram_start', 'datagram_start timing marker'),
        ('datagram_end', 'datagram_end timing marker'),
        ('read_request_end', 'read_request_end timing marker'),
        ('read_reply_start', 'read_reply_start timing marker'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_REQUEST, ANN_REPLY)),
        ('fields', 'Fields', (ANN_FIELD,)),
        ('timing', 'Timing', (ANN_DATAGRAM_START, ANN_DATAGRAM_END, ANN_READ_REQUEST_END, ANN_READ_REPLY_START)),
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
        # SPI state
        self.mosi = []
        self.miso = []
        self.spans = []
        self.active = False
        self.prev = None   # ('read', reg) or ('write', value) of the previous datagram
        # UART state
        self.buf = []
        self.bspans = []
        self.last_host = None
        self.pending_request_end = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def decode(self, ss, es, data):
        if len(data) == 3:      # uart decoder: (ptype, rxtx, pdata)
            ptype, _rxtx, pdata = data
            if ptype == 'DATA':
                self._uart_byte(pdata[0] & 0xFF, ss, es)
            return
        ptype, pdata = data     # spi decoder: (ptype, pdata)
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
                self._spi_datagram()
            self.active = False

    # ---- SPI ----

    def _spi_datagram(self):
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
                pname = REGISTERS.get(self.prev[1], 'reg 0x%02X' % self.prev[1])
                self.put(self.spans[1][0], es, self.out_ann, [ANN_REPLY, [
                    'reply to %s read = 0x%08X' % (pname, reply), 'R %s' % pname]])
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
            for long_text, short_text in decode_fields(reg, value, ioin_read=False):
                self.put(ss, es, self.out_ann, [ANN_FIELD, [long_text, short_text]])
            self.put(ss, es, self.out_python, ('REG_WRITE', (reg, value)))
            self.prev = ('write', value)
        else:
            self.put(ss, es, self.out_ann, [ANN_REQUEST, ['Read request %s' % name, 'R? %s' % name]])
            self.prev = ('read', reg)

    # ---- UART ----

    def _frame_length(self):
        if len(self.buf) < 3:
            return None
        if self.buf[1] == MASTER_ADDR:
            return 8
        if self.buf[2] & 0x80:
            return 8
        return 4

    def _uart_byte(self, byte, ss, es):
        if not self.buf:
            if byte != SYNC:
                self._warn(ss, es, 'Unexpected byte 0x%02X outside a datagram (sync 0x05 expected)' % byte)
                return
        self.buf.append(byte)
        self.bspans.append((ss, es))
        length = self._frame_length()
        if length is not None and len(self.buf) == length:
            self._uart_complete()

    def _uart_complete(self):
        frame = bytes(self.buf)
        spans = self.bspans
        self.buf, self.bspans = [], []
        ss, es = spans[0][0], spans[-1][1]
        crc_ok = _crc8(frame[:-1]) == frame[-1]
        reg = frame[2] & 0x7F
        name = REGISTERS.get(reg, 'reg 0x%02X' % reg)

        if len(frame) == 8 and frame[1] == MASTER_ADDR:
            self._uart_reply(frame, spans, ss, es, reg, name, crc_ok)
            return

        # Host datagram: skip a byte-identical immediate repeat (host echo).
        unit = max(1, spans[0][1] - spans[0][0])
        if self.last_host is not None and self.last_host[0] == frame and ss - self.last_host[1] <= 20 * unit:
            self.last_host = (frame, es)
            return
        self.last_host = (frame, es)

        if len(frame) == 8:
            value = int.from_bytes(frame[3:7], 'big')
            self.put(ss, es, self.out_ann, [ANN_REQUEST, [
                'Write %s = 0x%08X' % (name, value), 'W %s 0x%08X' % (name, value)]])
            self._node(spans, frame[1])
            self._fields(spans, reg, value, ioin_read=False)
            self.put(ss, es, self.out_python, ('REG_WRITE', (reg, value)))
            self.pending_request_end = None
        else:
            self.put(ss, es, self.out_ann, [ANN_REQUEST, ['Read request %s' % name, 'R? %s' % name]])
            self._node(spans, frame[1])
            self.pending_request_end = spans[-1]
            self.put(spans[-1][0], spans[-1][1], self.out_ann, [ANN_READ_REQUEST_END, [
                'read_request_end: last byte of the read request', 'read_request_end', '▶RQ']])
        if not crc_ok:
            self._warn(ss, es, 'CRC error in host datagram')

    def _uart_reply(self, frame, spans, ss, es, reg, name, crc_ok):
        value = int.from_bytes(frame[3:7], 'big')
        if self.pending_request_end is not None:
            self.put(spans[0][0], spans[0][1], self.out_ann, [ANN_READ_REPLY_START, [
                'read_reply_start: first byte of the read reply', 'read_reply_start', '→RP']])
            self.pending_request_end = None
        self.put(ss, es, self.out_ann, [ANN_REPLY, [
            'Reply %s = 0x%08X' % (name, value), 'R %s 0x%08X' % (name, value)]])
        self._node(spans, frame[1])
        self._fields(spans, reg, value)
        self.put(ss, es, self.out_python, ('REG_READ', (reg, value)))
        if not crc_ok:
            self._warn(ss, es, 'CRC error in reply datagram')

    def _node(self, spans, address):
        text = 'node=0x%02X (master)' % address if address == MASTER_ADDR else 'node=%d' % address
        if address != MASTER_ADDR and address > 253:
            text += ' (invalid)'
        self.put(spans[1][0], spans[1][1], self.out_ann, [ANN_FIELD, [text, 'node']])

    def _fields(self, spans, reg, value, ioin_read=True):
        ss, es = spans[0][0], spans[-1][1]
        for long_text, short_text in decode_fields(reg, value, ioin_read):
            self.put(ss, es, self.out_ann, [ANN_FIELD, [long_text, short_text]])
