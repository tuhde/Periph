import sigrokdecode as srd

ADDRS = {0x76, 0x77}

REGS = {
    0x00: 'prs_b2', 0x01: 'prs_b1', 0x02: 'prs_b0', 0x03: 'tmp_b2', 0x04: 'tmp_b1', 0x05: 'tmp_b0',
    0x06: 'prs_cfg', 0x07: 'tmp_cfg', 0x08: 'meas_cfg', 0x09: 'cfg_reg', 0x0A: 'int_sts',
    0x0B: 'fifo_sts', 0x0C: 'reset', 0x0D: 'id',
}
for _i in range(18):
    REGS[0x10 + _i] = 'coef[%d]' % _i
READ_ONLY = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x0A, 0x0B, 0x0D} | set(range(0x10, 0x22))

# Scale factors kP / kT indexed by log2(oversampling) (datasheet Table 4)
SCALE = (524288, 1572864, 3670016, 7864320, 253952, 516096, 1040384, 2088960)
MODES = {0: 'idle', 1: 'pressure (command)', 2: 'temperature (command)', 3: 'reserved',
         4: 'reserved', 5: 'continuous pressure', 6: 'continuous temperature',
         7: 'continuous pressure and temperature'}

ANN_REG_WRITE  = 0
ANN_REG_READ   = 1
ANN_RESULT     = 2
ANN_COEF       = 3
ANN_PTR_WRITE  = 4
ANN_STATUS     = 5
ANN_WARNING    = 6


def _signed(v, bits):
    return v - (1 << bits) if v & (1 << (bits - 1)) else v


def _raw24(b):
    return _signed((b[0] << 16) | (b[1] << 8) | b[2], 24)


def _decode_coef(b):
    return {
        'c0': _signed((b[0] << 4) | (b[1] >> 4), 12),
        'c1': _signed(((b[1] & 0x0F) << 8) | b[2], 12),
        'c00': _signed((b[3] << 12) | (b[4] << 4) | (b[5] >> 4), 20),
        'c10': _signed(((b[5] & 0x0F) << 16) | (b[6] << 8) | b[7], 20),
        'c01': _signed((b[8] << 8) | b[9], 16),
        'c11': _signed((b[10] << 8) | b[11], 16),
        'c20': _signed((b[12] << 8) | b[13], 16),
        'c21': _signed((b[14] << 8) | b[15], 16),
        'c30': _signed((b[16] << 8) | b[17], 16),
    }


def _decode_prs_cfg(v):
    return 'PRS_CFG 0x%02X: rate %d/s, oversampling %dx' % (v, 1 << ((v >> 4) & 7), 1 << (v & 7))


def _decode_tmp_cfg(v):
    return 'TMP_CFG 0x%02X: %s sensor, rate %d/s, oversampling %dx' % (
        v, 'external (MEMS)' if v & 0x80 else 'internal (ASIC)', 1 << ((v >> 4) & 7), 1 << (v & 7))


def _decode_meas_cfg(v):
    flags = [n for bit, n in ((7, 'COEF_RDY'), (6, 'SENSOR_RDY'), (5, 'TMP_RDY'), (4, 'PRS_RDY')) if (v >> bit) & 1]
    return 'MEAS_CFG 0x%02X: %s; %s' % (v, MODES[v & 7], ', '.join(flags) if flags else 'no ready flags')


def _decode_cfg_reg(v):
    parts = ['INT active %s' % ('high' if v & 0x80 else 'low')]
    for bit, n in ((6, 'INT_FIFO'), (5, 'INT_PRS'), (4, 'INT_TMP'), (3, 'T_SHIFT'), (2, 'P_SHIFT'),
                   (1, 'FIFO_EN'), (0, 'SPI_3WIRE')):
        if (v >> bit) & 1:
            parts.append(n)
    return 'CFG_REG 0x%02X: %s' % (v, ', '.join(parts))


def _decode_int_sts(v):
    names = [n for bit, n in ((2, 'FIFO full'), (1, 'temperature ready'), (0, 'pressure ready')) if (v >> bit) & 1]
    return 'INT_STS 0x%02X: %s' % (v, ', '.join(names) if names else 'none')


def _decode_fifo_sts(v):
    return 'FIFO_STS 0x%02X: %s%s' % (v, 'empty' if v & 1 else 'not empty', ', full' if v & 2 else '')


def _decode_reset(v):
    parts = []
    if v & 0x80:
        parts.append('FIFO flush')
    if (v & 0x0F) == 0x09:
        parts.append('soft reset')
    return 'RESET 0x%02X: %s' % (v, ', '.join(parts) if parts else 'no action')


def _decode_one(reg, v):
    """Single-byte register decode shared by reads and writes; None if no field decode."""
    if reg == 0x06: return _decode_prs_cfg(v)
    if reg == 0x07: return _decode_tmp_cfg(v)
    if reg == 0x08: return _decode_meas_cfg(v)
    if reg == 0x09: return _decode_cfg_reg(v)
    if reg == 0x0A: return _decode_int_sts(v)
    if reg == 0x0B: return _decode_fifo_sts(v)
    if reg == 0x0C: return _decode_reset(v)
    if reg == 0x0D: return 'ID 0x%02X (%s)' % (v, 'SPL06-007, rev 0' if v == 0x10 else 'unexpected')
    return None


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'chip id' in m:
        return 'ID?'
    if 'read-only' in m:
        return 'RO?'
    if 'tmp_ext' in m:
        return 'EXT?'
    if 'shift' in m:
        return 'SHFT?'
    if 'ready' in m:
        return 'RDY?'
    return 'WARN'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); if a call site supplied
    fewer, derive the missing tier from the existing text so a narrow PulseView zoom still has
    something to show."""
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
    id = 'spl06'
    name = 'SPL06'
    longname = 'Goertek SPL06-007 barometric pressure and temperature sensor'
    desc = 'Decode SPL06-007 I2C register accesses, raw and compensated results, coefficients, configuration, FIFO and interrupt registers.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['spl06']
    tags = ['IC', 'Sensor', 'Pressure']

    annotations = (
        ('reg-write',  'Register write'),
        ('reg-read',   'Register read'),
        ('result',     'Measurement result'),
        ('coef',       'Calibration coefficients'),
        ('ptr-write',  'Register pointer write'),
        ('status',     'Status flags'),
        ('warning',    'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_RESULT, ANN_COEF, ANN_PTR_WRITE)),
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
        ('REG_READ' | 'REG_WRITE', (register, bytes))."""
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
        if reg is not None:
            super().put(ss, es, out_py, ('REG_' + rw, (reg, buf)))

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
        self.coef     = None      # decoded coefficient block, once seen
        self.p_os     = 0         # log2 of the pressure oversampling from the last PRS_CFG write
        self.t_os     = 0         # log2 of the temperature oversampling from the last TMP_CFG write
        self.cfg      = 0         # last CFG_REG write
        self.cfg_seen = False
        self.meas     = 0         # last MEAS_CFG value seen (written mode or read flags)
        self.traw     = None      # last raw temperature seen

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

    def _check_shift(self, ss, es):
        if not self.cfg_seen:
            return
        if self.p_os > 3 and not self.cfg & 0x04:
            self._warn(ss, es, 'Pressure oversampling %dx without P_SHIFT' % (1 << self.p_os))
        if self.t_os > 3 and not self.cfg & 0x08:
            self._warn(ss, es, 'Temperature oversampling %dx without T_SHIFT' % (1 << self.t_os))

    def _temperature(self, traw):
        return self.coef['c0'] * 0.5 + self.coef['c1'] * traw / SCALE[self.t_os]

    def _pressure(self, praw, traw):
        c = self.coef
        p = praw / SCALE[self.p_os]
        t = traw / SCALE[self.t_os]
        return c['c00'] + p * (c['c10'] + p * (c['c20'] + p * c['c30'])) + t * c['c01'] + t * p * (c['c11'] + p * c['c21'])

    def _result_texts(self, praw, traw):
        long_parts, short_parts = [], []
        if praw is not None:
            long_parts.append('pressure raw %d' % praw)
        if traw is not None:
            long_parts.append('temperature raw %d' % traw)
        if self.coef is not None:
            if traw is not None:
                t = self._temperature(traw)
                long_parts.append('%.2f C' % t)
                short_parts.append('T=%.2fC' % t)
            if praw is not None and (traw is not None or self.traw is not None):
                p = self._pressure(praw, traw if traw is not None else self.traw)
                long_parts.append('%.2f Pa' % p)
                short_parts.append('P=%.0fPa' % p)
        return ', '.join(long_parts), ' '.join(short_parts) or ('raw %d' % (praw if praw is not None else traw))

    def _finish_read(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        if not buf:
            return
        if reg == 0x10 and len(buf) >= 18:
            self.coef = _decode_coef(buf[:18])
            text = ', '.join('%s=%d' % (k, v) for k, v in self.coef.items())
            self.put(ss, es, self.out_ann, [ANN_COEF, ['Coefficients: ' + text, 'COEF c0=%d' % self.coef['c0'], 'COEF']])
            return
        if reg == 0x00 and len(buf) == 3 and self.cfg & 0x02:
            raw = _raw24(buf)
            if raw == -0x800000:
                self.put(ss, es, self.out_ann, [ANN_RESULT, ['FIFO entry: empty (0x800000)', 'FIFO empty', 'EMPTY']])
                return
            if raw & 1:
                long_t, short_t = self._result_texts(raw & ~1, None)
                kind = 'pressure'
            else:
                self.traw = raw
                long_t, short_t = self._result_texts(None, raw)
                kind = 'temperature'
            self.put(ss, es, self.out_ann, [ANN_RESULT, ['FIFO %s entry: %s' % (kind, long_t), short_t, 'FIFO']])
            return
        if reg in (0x00, 0x03) and len(buf) >= 3:
            praw = traw = None
            if reg == 0x00:
                praw = _raw24(buf[0:3])
                if len(buf) >= 6:
                    traw = _raw24(buf[3:6])
                if (self.meas & 0x07) == 0x01 and not self.meas & 0x10:
                    self._warn(ss, es, 'Pressure result read before PRS_RDY')
            else:
                traw = _raw24(buf[0:3])
                if (self.meas & 0x07) == 0x02 and not self.meas & 0x20:
                    self._warn(ss, es, 'Temperature result read before TMP_RDY')
            if traw is not None:
                self.traw = traw
            long_t, short_t = self._result_texts(praw, traw)
            self.put(ss, es, self.out_ann, [ANN_RESULT, ['Result: ' + long_t, short_t, 'RES']])
            return
        texts = []
        for i, v in enumerate(buf):
            r = reg + i
            t = _decode_one(r, v)
            texts.append(t if t is not None else '%s 0x%02X' % (self._reg_name(r), v))
            if r == 0x0D and v != 0x10:
                self._warn(ss, es, 'Unexpected chip ID: 0x%02X' % v)
            if r == 0x08:
                self.meas = (self.meas & 0x07) | (v & 0xF0)
        self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read ' + '; '.join(texts), 'R 0x%02X' % reg]])
        if reg in (0x08, 0x0A, 0x0B):
            self.put(ss, es, self.out_ann, [ANN_STATUS, [texts[0], '%s 0x%02X' % (self._reg_name(reg).upper(), buf[0])]])

    def _finish_write(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        if not buf:
            self.put(ss, es, self.out_ann,
                     [ANN_PTR_WRITE, ['Pointer -> %s (0x%02X)' % (self._reg_name(reg), reg), 'PTR 0x%02X' % reg]])
            return
        texts = []
        for i, v in enumerate(buf):
            r = reg + i
            if r in READ_ONLY:
                self._warn(ss, es, 'Write to read-only register %s' % self._reg_name(r))
            t = _decode_one(r, v)
            if r == 0x08:
                t = 'MEAS_CFG 0x%02X: start %s' % (v, MODES[v & 7])   # the ready flags are read-only
            texts.append(t if t is not None else '%s 0x%02X' % (self._reg_name(r), v))
            if r == 0x06:
                self.p_os = v & 7
                self._check_shift(ss, es)
            elif r == 0x07:
                self.t_os = v & 7
                if not v & 0x80:
                    self._warn(ss, es, 'TMP_CFG written with TMP_EXT = 0 (coefficients need the external sensor)')
                self._check_shift(ss, es)
            elif r == 0x08:
                self.meas = v & 0x07
            elif r == 0x09:
                self.cfg = v
                self.cfg_seen = True
                self._check_shift(ss, es)
        self.put(ss, es, self.out_ann, [ANN_REG_WRITE, ['Write ' + '; '.join(texts), 'W 0x%02X' % reg]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_DATA_WRITE' and not self.databuf:
                pass   # pointer write followed by a repeated-start read: keep reg_ptr
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = pdata
                self.databuf = []
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
