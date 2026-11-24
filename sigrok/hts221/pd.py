import sigrokdecode as srd

ADDRS = {0x5F}

REGS = {
    0x0F: 'who_am_i',
    0x10: 'av_conf',
    0x20: 'ctrl_reg1',
    0x21: 'ctrl_reg2',
    0x22: 'ctrl_reg3',
    0x27: 'status_reg',
    0x28: 'humidity_out_l',
    0x29: 'humidity_out_h',
    0x2A: 'temp_out_l',
    0x2B: 'temp_out_h',
}
CALIB_FIRST, CALIB_LAST = 0x30, 0x3F

AVGT = {0: 2, 1: 4, 2: 8, 3: 16, 4: 32, 5: 64, 6: 128, 7: 256}
AVGH = {0: 4, 1: 8, 2: 16, 3: 32, 4: 64, 5: 128, 6: 256, 7: 512}
ODR_NAMES = {0: 'one-shot', 1: '1 Hz', 2: '7 Hz', 3: '12.5 Hz'}
RESERVED_RANGES = ((0x00, 0x0E), (0x11, 0x1C), (0x23, 0x26), (0x2C, 0x2F))

ANN_REG_WRITE     = 0
ANN_REG_READ      = 1
ANN_BURST_READ    = 2
ANN_PTR_WRITE     = 3
ANN_WARNING       = 4
ANN_STATUS        = 5


def _s16(raw):
    return raw if raw < 0x8000 else raw - 0x10000


def _decode_av_conf(raw):
    return 'av_conf 0x%02X: avgt=%d samples, avgh=%d samples' % (
        raw, AVGT[(raw >> 3) & 7], AVGH[raw & 7])


def _decode_ctrl_reg1(raw):
    return 'ctrl_reg1 0x%02X: pd=%d, bdu=%d, odr=%s' % (
        raw, (raw >> 7) & 1, (raw >> 2) & 1, ODR_NAMES[raw & 3])


def _decode_ctrl_reg2(raw):
    return 'ctrl_reg2 0x%02X: boot=%d, heater=%d, one_shot=%d' % (
        raw, (raw >> 7) & 1, (raw >> 1) & 1, raw & 1)


def _decode_ctrl_reg3(raw):
    return 'ctrl_reg3 0x%02X: drdy_h_l=%s, pp_od=%s, drdy_en=%d' % (
        raw, 'active low' if raw & 0x80 else 'active high',
        'open drain' if raw & 0x40 else 'push-pull', (raw >> 2) & 1)


def _decode_status(raw):
    parts = []
    if raw & 0x02:
        parts.append('Humidity ready')
    if raw & 0x01:
        parts.append('Temperature ready')
    return 'status_reg 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'idle')


def _is_reserved(reg):
    return any(lo <= reg <= hi for lo, hi in RESERVED_RANGES)


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'chip id' in m or 'device id' in m:
        return 'ID?'
    if 'address' in m:
        return 'ADDR?'
    if any(k in m for k in ('length', 'byte', 'short', 'missing', 'expected', 'no data', 'empty')):
        return 'LEN?'
    if any(k in m for k in ('unknown', 'unexpected', 'invalid', 'undefined', 'reserved', 'out of range')):
        return 'BAD?'
    return 'WARN'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); if a
    call site supplied fewer, derive the missing tier from the existing text
    (leading name token, <= 8 chars when it becomes the shortest tier) so a
    narrow PulseView zoom still has something to show."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings

    def lead(text):
        text = text.strip()
        n = 0
        while n < len(text) and (text[n].isalnum() or text[n] in '_\u2192'):
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
    id = 'hts221'
    name = 'HTS221'
    longname = 'HTS221 capacitive humidity and temperature sensor'
    desc = 'Decode HTS221 I2C humidity/temperature sensor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['hts221']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write',     'Register write'),
        ('reg-read',      'Register read'),
        ('burst-read',    'Multi-byte burst read'),
        ('ptr-write',     'Register pointer write'),
        ('warning',       'Warning'),
        ('status', 'Status flags'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_BURST_READ, ANN_PTR_WRITE)),
        ('status',   'Status',   (ANN_STATUS,)),
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
                self._mirror_python(ss, es)
        super().put(ss, es, out, data)

    def _mirror_python(self, ss, es):
        """OUTPUT_PYTHON mirror of each transaction-level data annotation:
        ('REG_READ' | 'REG_WRITE', (register, bytes)), or
        ('I2C_READ' | 'I2C_WRITE', (address, bytes)) for chips without a register pointer."""
        out_py = self.__dict__.get('out_python')
        if out_py is None or self.__dict__.get('_py_span') == (ss, es):
            return
        self._py_span = (ss, es)
        try:
            buf = bytes(b & 0xFF for b in getattr(self, 'databuf', None) or ())
        except TypeError:
            return
        rw = 'READ' if getattr(self, 'is_read', False) else 'WRITE'
        reg = getattr(self, 'reg_ptr', None)
        if reg is None:
            reg = getattr(self, 'reg_byte', None)
        if reg is not None:
            super().put(ss, es, out_py, ('REG_' + rw, (reg, buf)))
        else:
            super().put(ss, es, out_py, ('I2C_' + rw, (getattr(self, 'addr', None), buf)))

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.ss       = None
        self.es       = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _reg_name(self, reg):
        return REGS.get(reg, 'reg[0x%02X]' % reg)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        if reg is None:
            return
        if self.is_read:
            self._finish_read(reg)
        else:
            self._finish_write(reg)

    def _emit_status(self, flags, raw, ss, es):
        self.put(ss, es, self.out_ann,
                 [ANN_STATUS, ['status %s' % flags, flags, '0x%02X' % raw]])
        self.put(ss, es, self.out_python, ('STATUS', (raw, flags)))

    def _calibrate(self, buf):
        """Decode the 16-byte calibration block (registers 0x30-0x3F) and keep it."""
        t0 = ((buf[5] & 0x03) << 8) | buf[2]
        t1 = ((buf[5] & 0x0C) << 6) | buf[3]
        self.cal = {
            'h0': buf[0] / 2.0, 'h1': buf[1] / 2.0,
            't0': t0 / 8.0, 't1': t1 / 8.0,
            'h0o': _s16((buf[7] << 8) | buf[6]), 'h1o': _s16((buf[11] << 8) | buf[10]),
            't0o': _s16((buf[13] << 8) | buf[12]), 't1o': _s16((buf[15] << 8) | buf[14]),
        }

    def _finish_read(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        if not buf:
            return

        if reg == 0x0F:
            ok = buf[0] == 0xBC
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ, ['WHO_AM_I: 0x%02X (%s)' % (buf[0], 'HTS221' if ok else 'unexpected'),
                                      'ID 0x%02X' % buf[0]]])
            if not ok:
                self._warn(ss, es, 'Unexpected chip ID 0x%02X, expected 0xBC' % buf[0])
            return

        if reg == 0x27 and len(buf) == 1:
            flags = []
            if buf[0] & 0x02:
                flags.append('H_DA')
            if buf[0] & 0x01:
                flags.append('T_DA')
            self.put(ss, es, self.out_ann, [ANN_REG_READ, [_decode_status(buf[0]), 'status 0x%02X' % buf[0]]])
            self._emit_status(' '.join(flags) or 'idle', buf[0], ss, es)
            return

        if reg == CALIB_FIRST and len(buf) >= 16:
            self._calibrate(buf)
            c = self.cal
            self.put(ss, es, self.out_ann,
                     [ANN_BURST_READ,
                      ['Calibration: H0=%.1f %%rH H1=%.1f %%rH T0=%.3f C T1=%.3f C '
                       'H0_OUT=%d H1_OUT=%d T0_OUT=%d T1_OUT=%d' % (
                           c['h0'], c['h1'], c['t0'], c['t1'], c['h0o'], c['h1o'], c['t0o'], c['t1o']),
                       'CALIB']])
            return

        if reg == 0x28 and len(buf) >= 4:
            raw_h = _s16((buf[1] << 8) | buf[0])
            raw_t = _s16((buf[3] << 8) | buf[2])
            text = 'H_OUT=%d T_OUT=%d' % (raw_h, raw_t)
            short = 'H=%d T=%d' % (raw_h, raw_t)
            c = getattr(self, 'cal', None)
            if c and c['h1o'] != c['h0o'] and c['t1o'] != c['t0o']:
                rh = (raw_h - c['h0o']) * (c['h1'] - c['h0']) / (c['h1o'] - c['h0o']) + c['h0']
                rh = max(0.0, min(100.0, rh))
                tc = (raw_t - c['t0o']) * (c['t1'] - c['t0']) / (c['t1o'] - c['t0o']) + c['t0']
                text += ' -> %.1f %%rH, %.2f C' % (rh, tc)
                short = '%.1f%%rH %.2fC' % (rh, tc)
            self.put(ss, es, self.out_ann, [ANN_BURST_READ, [text, short]])
            return

        hex_bytes = ' '.join('0x%02X' % b for b in buf)
        self.put(ss, es, self.out_ann,
                 [ANN_REG_READ, ['Read %s: %s' % (self._reg_name(reg), hex_bytes), 'R 0x%02X' % reg]])

    def _finish_write(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        name = self._reg_name(reg)

        if not buf:
            self.put(ss, es, self.out_ann,
                     [ANN_PTR_WRITE, ['Pointer -> %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg]])
            return

        if _is_reserved(reg) or CALIB_FIRST <= reg <= CALIB_LAST:
            self._warn(ss, es, 'Write to reserved/calibration register 0x%02X (never write)' % reg)

        decoders = {0x10: _decode_av_conf, 0x20: _decode_ctrl_reg1,
                    0x21: _decode_ctrl_reg2, 0x22: _decode_ctrl_reg3}
        if reg in decoders and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [decoders[reg](buf[0]), '%s 0x%02X' % (name, buf[0])]])
            return

        hex_bytes = ' '.join('0x%02X' % b for b in buf)
        self.put(ss, es, self.out_ann,
                 [ANN_REG_WRITE, ['Write %s: %s' % (name, hex_bytes), 'W 0x%02X' % reg]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                # Pointer set followed by repeated-start read: preserve reg_ptr
                pass
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state   = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = byte & 0x7F
                self.databuf = []
                self.state   = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []