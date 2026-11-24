import sigrokdecode as srd

ADDRS = {0x60}

REGS = {
    0x00: 'status', 0x01: 'out_p_msb', 0x02: 'out_p_csb', 0x03: 'out_p_lsb',
    0x04: 'out_t_msb', 0x05: 'out_t_lsb', 0x06: 'dr_status',
    0x07: 'out_p_delta_msb', 0x08: 'out_p_delta_csb', 0x09: 'out_p_delta_lsb',
    0x0A: 'out_t_delta_msb', 0x0B: 'out_t_delta_lsb', 0x0C: 'who_am_i',
    0x0D: 'f_status', 0x0E: 'f_data', 0x0F: 'f_setup', 0x10: 'time_dly',
    0x11: 'sysmod', 0x12: 'int_source', 0x13: 'pt_data_cfg',
    0x14: 'bar_in_msb', 0x15: 'bar_in_lsb', 0x16: 'p_tgt_msb', 0x17: 'p_tgt_lsb',
    0x18: 't_tgt', 0x19: 'p_wnd_msb', 0x1A: 'p_wnd_lsb', 0x1B: 't_wnd',
    0x1C: 'p_min_msb', 0x1D: 'p_min_csb', 0x1E: 'p_min_lsb',
    0x1F: 't_min_msb', 0x20: 't_min_lsb',
    0x21: 'p_max_msb', 0x22: 'p_max_csb', 0x23: 'p_max_lsb',
    0x24: 't_max_msb', 0x25: 't_max_lsb',
    0x26: 'ctrl_reg1', 0x27: 'ctrl_reg2', 0x28: 'ctrl_reg3', 0x29: 'ctrl_reg4',
    0x2A: 'ctrl_reg5', 0x2B: 'off_p', 0x2C: 'off_t', 0x2D: 'off_h',
}
READ_ONLY = set(range(0x00, 0x0F)) | {0x10, 0x11, 0x12}

REG_CTRL1 = 0x26
OSR_TIMES_MS = (6, 10, 18, 34, 66, 130, 258, 512)

ANN_REG_WRITE  = 0
ANN_REG_READ   = 1
ANN_BURST_READ = 2
ANN_PTR_WRITE  = 3
ANN_STATUS     = 4
# Named start/end pair for the conversion-time conformance check (see
# specs/pressure/mpl3115a2.md "Timing Constraints" and
# specs/pressure/mpl3115a2_timing.conf):
#   conversion_start = CTRL_REG1 write that sets OST.
#   conversion_done  = first following STATUS/DR_STATUS read with PTDR set.
ANN_CONVERSION_START = 5
ANN_CONVERSION_DONE  = 6
ANN_WARNING = 7

INT_BITS = ((7, 'DRDY'), (6, 'FIFO'), (5, 'PW'), (4, 'TW'), (3, 'PTH'), (2, 'TTH'), (1, 'PCHG'), (0, 'TCHG'))


def _s8(v):
    return v - 0x100 if v & 0x80 else v


def _s16(v):
    return v - 0x10000 if v & 0x8000 else v


def _pressure(b):
    return ((b[0] << 12) | (b[1] << 4) | (b[2] >> 4)) / 4.0


def _altitude(b):
    return _s16((b[0] << 8) | b[1]) + (b[2] >> 4) / 16.0


def _temperature(b):
    return _s8(b[0]) + (b[1] >> 4) / 16.0


def _flags(v, table):
    names = [name for bit, name in table if (v >> bit) & 1]
    return ', '.join(names) if names else 'none'


def _decode_dr_status(v):
    names = [(7, 'PTOW'), (6, 'POW'), (5, 'TOW'), (3, 'PTDR'), (2, 'PDR'), (1, 'TDR')]
    return 'DR_STATUS 0x%02X: %s' % (v, _flags(v, names))


def _decode_ctrl1(v):
    return 'CTRL_REG1 0x%02X: %s, OSR %d (%d ms), %s%s%s' % (
        v, 'altimeter' if v & 0x80 else 'barometer', 1 << ((v >> 3) & 7),
        OSR_TIMES_MS[(v >> 3) & 7], 'RST ' if v & 0x04 else '', 'OST ' if v & 0x02 else '',
        'ACTIVE' if v & 0x01 else 'STANDBY')


def _decode_ctrl2(v):
    return 'CTRL_REG2 0x%02X: LOAD_OUTPUT=%d, ALARM_SEL=%d, ST=%d (%d s)' % (
        v, (v >> 5) & 1, (v >> 4) & 1, v & 0x0F, 1 << (v & 0x0F))


def _decode_ctrl3(v):
    return 'CTRL_REG3 0x%02X: INT1 active %s %s, INT2 active %s %s' % (
        v, 'high' if v & 0x20 else 'low', 'open-drain' if v & 0x10 else 'push-pull',
        'high' if v & 0x02 else 'low', 'open-drain' if v & 0x01 else 'push-pull')


def _decode_int_reg(name, v):
    return '%s 0x%02X: %s' % (name, v, _flags(v, INT_BITS))


def _decode_fifo_status(v):
    return 'F_STATUS 0x%02X: overflow=%d, watermark=%d, count=%d' % (v, (v >> 7) & 1, (v >> 6) & 1, v & 0x3F)


def _decode_fifo_setup(v):
    mode = {0: 'disabled', 1: 'circular', 2: 'stop when full', 3: 'reserved'}[(v >> 6) & 3]
    return 'F_SETUP 0x%02X: %s, watermark %d' % (v, mode, v & 0x3F)


def _decode_sysmod(v):
    return 'SYSMOD 0x%02X: %s' % (v, 'ACTIVE' if v & 1 else 'STANDBY')


def _decode_pt_cfg(v):
    return 'PT_DATA_CFG 0x%02X: %s' % (v, _flags(v, ((2, 'DREM'), (1, 'PDEFE'), (0, 'TDEFE'))))


def _decode_one(reg, v):
    """Single-byte register decode shared by reads and writes; None if no field decode."""
    if reg in (0x00, 0x06): return _decode_dr_status(v)
    if reg == 0x26: return _decode_ctrl1(v)
    if reg == 0x27: return _decode_ctrl2(v)
    if reg == 0x28: return _decode_ctrl3(v)
    if reg == 0x29: return _decode_int_reg('CTRL_REG4 (enable)', v)
    if reg == 0x2A: return _decode_int_reg('CTRL_REG5 (route 1=INT1)', v)
    if reg == 0x12: return _decode_int_reg('INT_SOURCE', v)
    if reg == 0x0D: return _decode_fifo_status(v)
    if reg == 0x0F: return _decode_fifo_setup(v)
    if reg == 0x11: return _decode_sysmod(v)
    if reg == 0x13: return _decode_pt_cfg(v)
    if reg == 0x0C: return 'WHO_AM_I 0x%02X (%s)' % (v, 'MPL3115A2' if v == 0xC4 else 'unexpected')
    if reg == 0x2B: return 'OFF_P %d Pa' % (_s8(v) * 4)
    if reg == 0x2C: return 'OFF_T %.4f C' % (_s8(v) * 0.0625)
    if reg == 0x2D: return 'OFF_H %d m' % _s8(v)
    if reg == 0x18: return 'T_TGT %d C' % _s8(v)
    if reg == 0x1B: return 'T_WND %d C' % v
    return None


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
    id = 'mpl3115a2'
    name = 'MPL3115A2'
    longname = 'NXP MPL3115A2 barometric pressure, altitude and temperature sensor'
    desc = 'Decode MPL3115A2 I2C register accesses, pressure/altitude/temperature bursts, configuration, FIFO and interrupt registers.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mpl3115a2']
    tags = ['IC', 'Sensor', 'Pressure']

    annotations = (
        ('reg-write',   'Register write'),
        ('reg-read',    'Register read'),
        ('burst-read',  'Multi-byte burst read'),
        ('ptr-write',   'Register pointer write'),
        ('status',      'Status flags'),
        ('conversion-start', 'Conversion: start'),
        ('conversion-done',  'Conversion: done'),
        ('warning',     'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_BURST_READ, ANN_PTR_WRITE)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_CONVERSION_START, ANN_CONVERSION_DONE)),
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
        self.alt      = False     # ALT bit of the last CTRL_REG1 write seen
        self.active   = False     # SBYB of the last CTRL_REG1 write / SYSMOD read seen

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

    def _value_text(self, b):
        """Converted pressure/altitude text for three data bytes."""
        if self.alt:
            return 'altitude %.4f m' % _altitude(b)
        return 'pressure %.2f Pa' % _pressure(b)

    def _finish_read(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        if not buf:
            return
        # Pressure/altitude (+ temperature) burst, optionally preceded by STATUS.
        base = reg
        data = buf
        prefix = ''
        if reg == 0x00 and len(buf) >= 4:
            prefix = _decode_dr_status(buf[0]) + '; '
            base, data = 0x01, buf[1:]
        if base == 0x01 and len(data) >= 3:
            text = self._value_text(data[0:3])
            short = text
            if len(data) >= 5:
                text += ', temperature %.4f C' % _temperature(data[3:5])
                short = text.replace('pressure ', 'P=').replace('altitude ', 'h=').replace(', temperature ', ' T=')
            self.put(ss, es, self.out_ann, [ANN_BURST_READ, [prefix + 'Data: ' + text, short]])
            if prefix:
                self._conversion_done_check(buf[0], ss, es)
            return
        if base == 0x04 and len(data) >= 2:
            self.put(ss, es, self.out_ann,
                     [ANN_BURST_READ, ['Temperature: %.4f C' % _temperature(data[0:2]), 'T=%.2fC' % _temperature(data[0:2])]])
            return
        if reg == 0x07 and len(buf) >= 5:
            if self.alt:
                dv = 'delta altitude %.4f m' % _altitude(buf[0:3])
            else:
                p = _pressure(buf[0:3])
                dv = 'delta pressure %.2f Pa' % (p - 524288.0 if p >= 262144.0 else p)
            self.put(ss, es, self.out_ann,
                     [ANN_BURST_READ, ['Delta: %s, delta temperature %.4f C' % (dv, _temperature(buf[3:5])), 'delta']])
            return
        if reg == 0x0E and len(buf) >= 5:
            n = len(buf) // 5
            parts = []
            for i in range(n):
                s = buf[5 * i:5 * i + 5]
                parts.append('%s, %.2f C' % (self._value_text(s[0:3]), _temperature(s[3:5])))
            self.put(ss, es, self.out_ann, [ANN_BURST_READ, ['FIFO data (%d): ' % n + '; '.join(parts), 'FIFO x%d' % n]])
            return
        if reg == 0x1C and len(buf) >= 3:
            txt = 'P_MIN %s' % self._value_text(buf[0:3]).split(' ', 1)[1]
            if len(buf) >= 5:
                txt += ', T_MIN %.4f C' % _temperature(buf[3:5])
            self.put(ss, es, self.out_ann, [ANN_BURST_READ, [txt, 'MIN']])
            return
        if reg == 0x21 and len(buf) >= 3:
            txt = 'P_MAX %s' % self._value_text(buf[0:3]).split(' ', 1)[1]
            if len(buf) >= 5:
                txt += ', T_MAX %.4f C' % _temperature(buf[3:5])
            self.put(ss, es, self.out_ann, [ANN_BURST_READ, [txt, 'MAX']])
            return
        if reg == 0x14 and len(buf) >= 2:
            self.put(ss, es, self.out_ann,
                     [ANN_BURST_READ, ['BAR_IN %d Pa' % (((buf[0] << 8) | buf[1]) * 2), 'BAR_IN']])
            return
        # Per-register decode with auto-increment.
        texts = []
        for i, v in enumerate(buf):
            r = reg + i
            t = _decode_one(r, v)
            texts.append(t if t is not None else '%s 0x%02X' % (self._reg_name(r), v))
            if r == 0x0C and v != 0xC4:
                self._warn(ss, es, 'Unexpected chip ID: 0x%02X' % v)
            if r == 0x11:
                self.active = bool(v & 1)
        label = 'Read ' + '; '.join(texts)
        cls = ANN_REG_READ
        self.put(ss, es, self.out_ann, [cls, [label, 'R 0x%02X' % reg]])
        if reg in (0x00, 0x06):
            self._conversion_done_check(buf[0], ss, es)
            self.put(ss, es, self.out_ann, [ANN_STATUS, [_decode_dr_status(buf[0]), 'status 0x%02X' % buf[0]]])

    def _conversion_done_check(self, status, ss, es):
        if status & 0x08:
            self.put(ss, es, self.out_ann,
                     [ANN_CONVERSION_DONE, ['conversion_done: DR_STATUS shows PTDR=1', 'conversion_done', 'C\u2713']])

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
            texts.append(t if t is not None else '%s 0x%02X' % (self._reg_name(r), v))
            if r == REG_CTRL1:
                if self.active and (v & 0xFC) != self._last_c1:
                    self._warn(ss, es, 'CTRL_REG1 changed (other than SBYB/OST) while ACTIVE')
                self._last_c1 = v & 0xFC
                self.alt = bool(v & 0x80)
                self.active = bool(v & 0x01)
                if v & 0x02:
                    self.put(ss, es, self.out_ann,
                             [ANN_CONVERSION_START, ['conversion_start: CTRL_REG1 written with OST', 'conversion_start', 'C\u25b6']])
        if len(buf) >= 2 and reg == 0x14:
            texts = ['BAR_IN %d Pa' % (((buf[0] << 8) | buf[1]) * 2)]
        self.put(ss, es, self.out_ann, [ANN_REG_WRITE, ['Write ' + '; '.join(texts), 'W 0x%02X' % reg]])

    _last_c1 = 0

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
