import sigrokdecode as srd

ADDRS = {0x28, 0x29}

REGS = {
    0x00: 'CHIP_ID', 0x01: 'ACC_ID', 0x02: 'MAG_ID', 0x03: 'GYR_ID',
    0x04: 'SW_REV_ID_LSB', 0x05: 'SW_REV_ID_MSB', 0x06: 'BL_REV_ID', 0x07: 'PAGE_ID',
    0x08: 'ACC_DATA_X_LSB', 0x0E: 'MAG_DATA_X_LSB', 0x14: 'GYR_DATA_X_LSB',
    0x1A: 'EUL_HEADING_LSB', 0x20: 'QUA_DATA_W_LSB', 0x28: 'LIA_DATA_X_LSB', 0x2E: 'GRV_DATA_X_LSB',
    0x34: 'TEMP', 0x35: 'CALIB_STAT', 0x36: 'ST_RESULT', 0x37: 'INT_STA', 0x38: 'SYS_CLK_STATUS',
    0x39: 'SYS_STATUS', 0x3A: 'SYS_ERR', 0x3B: 'UNIT_SEL', 0x3D: 'OPR_MODE', 0x3E: 'PWR_MODE',
    0x3F: 'SYS_TRIGGER', 0x40: 'TEMP_SOURCE', 0x41: 'AXIS_MAP_CONFIG', 0x42: 'AXIS_MAP_SIGN',
    0x55: 'ACC_OFFSET_X_LSB', 0x5B: 'MAG_OFFSET_X_LSB', 0x61: 'GYR_OFFSET_X_LSB',
    0x67: 'ACC_RADIUS_LSB', 0x69: 'MAG_RADIUS_LSB',
}

# Page 1 registers that differ from page 0 (PAGE_ID = 1)
REGS_P1 = {0x0F: 'INT_MSK', 0x10: 'INT_EN', 0x50: 'UNIQUE_ID'}

MODES = {0: 'CONFIGMODE', 1: 'ACCONLY', 2: 'MAGONLY', 3: 'GYROONLY', 4: 'ACCMAG', 5: 'ACCGYRO',
         6: 'MAGGYRO', 7: 'AMG', 8: 'IMU', 9: 'COMPASS', 10: 'M4G', 11: 'NDOF_FMC_OFF', 12: 'NDOF'}
PWR = {0: 'normal', 1: 'low power', 2: 'suspend', 3: 'invalid'}
SYS_STATUS = {0: 'idle', 1: 'system error', 2: 'initializing peripherals', 3: 'system initialization',
              4: 'executing self-test', 5: 'fusion running', 6: 'running without fusion'}
SYS_ERR = {0: 'none', 1: 'peripheral init error', 2: 'system init error', 3: 'self-test failed',
           4: 'register value out of range', 5: 'register address out of range', 6: 'register write error',
           7: 'low-power mode not available', 8: 'accelerometer power mode not available',
           9: 'fusion configuration error', 10: 'sensor configuration error'}
INT_BITS = ['ACC_BSX_DRDY', 'MAG_DRDY', 'GYRO_AM', 'GYR_HIGH_RATE', 'GYR_DRDY', 'ACC_HIGH_G', 'ACC_AM', 'ACC_NM']

ANN_WRITE   = 0
ANN_READ    = 1
ANN_WARNING = 2


def _signed16(lsb, msb):
    v = (msb << 8) | lsb
    return v - 0x10000 if v >= 0x8000 else v


def _unit_state(unit_sel):
    """Return (accel_text, accel_div, gyro_text, gyro_div, euler_text, euler_div, mag_div) for UNIT_SEL."""
    acc = ('mg', 1.0) if unit_sel & 0x01 else ('m/s^2', 100.0)
    gyr = ('rad/s', 900.0) if unit_sel & 0x02 else ('dps', 16.0)
    eul = ('rad', 900.0) if unit_sel & 0x04 else ('deg', 16.0)
    return acc, gyr, eul


def _fmt3(vals, div, unit):
    return '(%s) %s' % (', '.join('%+.2f' % (v / div) for v in vals), unit)


def _decode_vector(reg, data, unit_sel):
    acc, gyr, eul = _unit_state(unit_sel)
    n = len(data)
    def s16(i):
        return _signed16(data[2 * i], data[2 * i + 1])
    out = []
    pos = reg
    i = 0
    while i + 1 < n:
        if 0x08 <= pos <= 0x0D and i + 5 < n + 0 and pos == 0x08:
            out.append('ACC ' + _fmt3([s16(i // 2), s16(i // 2 + 1), s16(i // 2 + 2)], acc[1], acc[0]))
            step = 6
        elif pos == 0x0E:
            out.append('MAG ' + _fmt3([s16(i // 2), s16(i // 2 + 1), s16(i // 2 + 2)], 16.0, 'uT'))
            step = 6
        elif pos == 0x14:
            out.append('GYR ' + _fmt3([s16(i // 2), s16(i // 2 + 1), s16(i // 2 + 2)], gyr[1], gyr[0]))
            step = 6
        elif pos == 0x1A:
            out.append('EUL (heading, roll, pitch) ' + _fmt3([s16(i // 2), s16(i // 2 + 1), s16(i // 2 + 2)], eul[1], eul[0]))
            step = 6
        elif pos == 0x20 and i + 7 < n:
            out.append('QUA (w, x, y, z) (%s)' % ', '.join('%+.4f' % (s16(i // 2 + k) / 16384.0) for k in range(4)))
            step = 8
        elif pos == 0x28:
            out.append('LIA ' + _fmt3([s16(i // 2), s16(i // 2 + 1), s16(i // 2 + 2)], acc[1], acc[0]))
            step = 6
        elif pos == 0x2E:
            out.append('GRV ' + _fmt3([s16(i // 2), s16(i // 2 + 1), s16(i // 2 + 2)], acc[1], acc[0]))
            step = 6
        else:
            break
        i += step
        pos += step
    if pos == 0x34 and i < n:
        t = data[i] - 256 if data[i] & 0x80 else data[i]
        out.append('TEMP %d' % t)
    return ' | '.join(out) if out else None


def _decode_bits(raw, names):
    flags = [names[b] for b in range(8) if raw & (1 << b)]
    return ','.join(flags) if flags else 'none'


def _decode_reg(reg, val, page):
    if page == 1 and reg in (0x0F, 0x10):
        return '%s 0x%02X: %s' % (REGS_P1[reg], val, _decode_bits(val, INT_BITS))
    if reg == 0x3D:
        return 'OPR_MODE 0x%02X: %s' % (val, MODES.get(val & 0x0F, '?'))
    if reg == 0x3E:
        return 'PWR_MODE 0x%02X: %s' % (val, PWR.get(val & 0x03, '?'))
    if reg == 0x3B:
        return 'UNIT_SEL 0x%02X: acc=%s gyr=%s eul=%s temp=%s orientation=%s' % (
            val, 'mg' if val & 1 else 'm/s^2', 'rad/s' if val & 2 else 'dps', 'rad' if val & 4 else 'deg',
            'degF' if val & 0x10 else 'degC', 'Android' if val & 0x80 else 'Windows')
    if reg == 0x3F:
        parts = []
        if val & 0x80: parts.append('CLK_SEL=ext')
        if val & 0x40: parts.append('RST_INT')
        if val & 0x20: parts.append('RST_SYS')
        if val & 0x01: parts.append('SELF_TEST')
        return 'SYS_TRIGGER 0x%02X: %s' % (val, ','.join(parts) if parts else 'none')
    if reg == 0x35:
        return 'CALIB_STAT 0x%02X: sys=%d gyr=%d acc=%d mag=%d' % (val, val >> 6, (val >> 4) & 3, (val >> 2) & 3, val & 3)
    if reg == 0x37:
        return 'INT_STA 0x%02X: %s' % (val, _decode_bits(val, INT_BITS))
    if reg == 0x36:
        return 'ST_RESULT 0x%02X: acc=%s mag=%s gyr=%s mcu=%s' % (
            val, 'ok' if val & 1 else 'FAIL', 'ok' if val & 2 else 'FAIL', 'ok' if val & 4 else 'FAIL', 'ok' if val & 8 else 'FAIL')
    if reg == 0x39:
        return 'SYS_STATUS %d: %s' % (val, SYS_STATUS.get(val, '?'))
    if reg == 0x3A:
        return 'SYS_ERR %d: %s' % (val, SYS_ERR.get(val, '?'))
    if reg == 0x40:
        return 'TEMP_SOURCE %d: %s' % (val, 'gyroscope' if val & 1 else 'accelerometer')
    if reg == 0x41:
        return 'AXIS_MAP_CONFIG 0x%02X: X<-%s Y<-%s Z<-%s' % (val, 'XYZ?'[val & 3], 'XYZ?'[(val >> 2) & 3], 'XYZ?'[(val >> 4) & 3])
    if reg == 0x42:
        return 'AXIS_MAP_SIGN 0x%02X: X%s Y%s Z%s' % (val, '-' if val & 4 else '+', '-' if val & 2 else '+', '-' if val & 1 else '+')
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
    id = 'bno055'
    name = 'BNO055'
    longname = 'Bosch BNO055 9-axis absolute-orientation sensor'
    desc = 'Decode BNO055 I2C register transactions (orientation, calibration, modes, interrupts).'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['bno055']
    tags = ['IC', 'Sensor', 'IMU']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid != 'warnings' for c in classes}
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

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.page     = 0
        self.unit_sel = 0x80   # reset value; updated by UNIT_SEL writes seen in the capture

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _name(self, reg):
        if reg is None:
            return '?'
        if self.page == 1 and reg in REGS_P1:
            return REGS_P1[reg]
        return REGS.get(reg, 'Reg[0x%02X]' % reg)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        name = self._name(reg)
        buf = self.databuf

        if self.is_read:
            if not buf:
                return
            if reg == 0x00 and len(buf) == 1:
                ok = ' (BNO055 \u2713)' if buf[0] == 0xA0 else ' (expected 0xA0!)'
                text = ['CHIP_ID 0x%02X%s' % (buf[0], ok), 'ID 0x%02X' % buf[0]]
            elif reg == 0x00 and len(buf) >= 7:
                text = ['IDs chip=0x%02X acc=0x%02X mag=0x%02X gyr=0x%02X SW=%d.%d BL=%d' % (
                    buf[0], buf[1], buf[2], buf[3], buf[5], buf[4], buf[6]), 'IDs']
            elif self.page == 1 and reg == 0x50:
                text = ['UNIQUE_ID %s' % ''.join('%02X' % b for b in buf), 'UID']
            elif reg in (0x55, 0x5B, 0x61, 0x67, 0x69) and len(buf) >= 6:
                text = ['Calibration %s: %s' % (name, ' '.join('%02X' % b for b in buf)), 'CAL %dB' % len(buf)]
            else:
                vec = _decode_vector(reg, buf, self.unit_sel) if len(buf) > 1 or reg == 0x34 else None
                one = _decode_reg(reg, buf[0], self.page) if len(buf) == 1 and reg != 0x34 else None
                if vec:
                    text = [vec, 'Burst %dB' % len(buf)]
                elif one:
                    text = [one, 'R %s 0x%02X' % (name, buf[0])]
                elif len(buf) == 2 and reg == 0x39:
                    text = ['SYS_STATUS %d (%s), SYS_ERR %d (%s)' % (
                        buf[0], SYS_STATUS.get(buf[0], '?'), buf[1], SYS_ERR.get(buf[1], '?')), 'STATUS']
                elif len(buf) == 1:
                    text = ['Read %s: 0x%02X' % (name, buf[0]), 'R %s 0x%02X' % (name, buf[0])]
                else:
                    text = ['Read %s: %d bytes' % (name, len(buf)), 'R %s %dB' % (name, len(buf))]
            self.put(self.ss_block, self.es, self.out_ann, [ANN_READ, text])
        else:
            if reg is None:
                return
            if not buf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE, ['Pointer \u2192 %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg]])
                return
            if reg == 0x07:
                self.page = buf[0] & 1
            if reg == 0x3B:
                self.unit_sel = buf[0]
            if len(buf) == 1:
                desc = _decode_reg(reg, buf[0], self.page) or ('PAGE_ID %d' % (buf[0] & 1) if reg == 0x07 else 'Write %s: 0x%02X' % (name, buf[0]))
                self.put(self.ss_block, self.es, self.out_ann, [ANN_WRITE, [desc, 'W %s 0x%02X' % (name, buf[0])]])
            elif reg in (0x55, 0x5B, 0x61, 0x67, 0x69):
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE, ['Calibration %s: %s' % (name, ' '.join('%02X' % b for b in buf)), 'CAL %dB' % len(buf)]])
            else:
                self._warn(self.ss_block, self.es, 'Unexpected write length %d for %s' % (len(buf), name))

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass
            else:
                self._finish_transaction()
                self.databuf  = []
                self.is_read  = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = addr
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state   = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = byte
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
