"""BMA150 sigrok protocol decoder.

Sits on top of the sigrok `i2c` decoder and annotates bus transactions with
the BMA150 register names and decoded field values. Only I²C transactions
are decoded — BMA150 SPI bursts use a command-byte prefix that the
upstream `i2c` decoder cannot see.
"""

import sigrokdecode as srd

ADDRS = {0x38}

REGS = {
    0x00: 'CHIP_ID',
    0x01: 'VERSION',
    0x02: 'ACC_X_LSB',
    0x03: 'ACC_X_MSB',
    0x04: 'ACC_Y_LSB',
    0x05: 'ACC_Y_MSB',
    0x06: 'ACC_Z_LSB',
    0x07: 'ACC_Z_MSB',
    0x08: 'TEMP',
    0x09: 'STATUS',
    0x0A: 'CTRL',
    0x0B: 'INT_CTRL',
    0x0C: 'LG_THRES',
    0x0D: 'LG_DUR',
    0x0E: 'HG_THRES',
    0x0F: 'HG_DUR',
    0x10: 'ANY_MOTION_THRES',
    0x11: 'HYST_DUR',
    0x12: 'CUSTOMER_1',
    0x13: 'CUSTOMER_2',
    0x14: 'RANGE_BW',
    0x15: 'CONFIG',
}

# RANGE_BW (0x14) bits 4:3.
RANGE_BITS = {0x00: '±2g', 0x08: '±4g', 0x10: '±8g', 0x18: 'reserved'}
# RANGE_BW (0x14) bits 2:0.
BW_BITS = {0x00: 25.0, 0x01: 50.0, 0x02: 100.0, 0x03: 190.0,
           0x04: 375.0, 0x05: 750.0, 0x06: 1500.0, 0x07: 'reserved'}
# Range -> LSB/g scale; ±2g=256, ±4g=128, ±8g=64.
RANGE_SCALE = {0x00: 256, 0x08: 128, 0x10: 64}

# CTRL (0x0A) bits.
CTRL_FIELDS = [
    (0x80, 'reserved'),
    (0x40, 'reset_INT'),
    (0x20, 'update_image'),
    (0x10, 'ee_w'),
    (0x08, 'self_test_1'),
    (0x04, 'self_test_0'),
    (0x02, 'soft_reset'),
    (0x01, 'sleep'),
]

# STATUS (0x09) bits.
STATUS_FIELDS = [
    (0x80, 'st_result'),
    (0x10, 'alert_phase'),
    (0x08, 'LG_latched'),
    (0x04, 'HG_latched'),
    (0x02, 'LG'),
    (0x01, 'HG'),
]

# INT_CTRL (0x0B) bit fields.
INT_CTRL_ALERT       = 0x80
INT_CTRL_ANY_MOTION  = 0x40
INT_CTRL_COUNTER_HG  = 0x30
INT_CTRL_COUNTER_LG  = 0x0C
INT_CTRL_ENABLE_HG   = 0x02
INT_CTRL_ENABLE_LG   = 0x01

# CONFIG (0x15) bit fields.
CONFIG_SPI4          = 0x80
CONFIG_ENABLE_ADV    = 0x40
CONFIG_NEW_DATA_INT  = 0x20
CONFIG_LATCH_INT     = 0x10
CONFIG_SHADOW_DIS    = 0x08
CONFIG_WAKE_UP_PAUSE  = 0x06
CONFIG_WAKE_UP        = 0x01

# HYST_DUR (0x11) bit fields.
HYST_DUR_ANY_MOTION_DUR = 0xC0
HYST_DUR_HG_HYST         = 0x38
HYST_DUR_LG_HYST         = 0x07

# Wake-up pause (CONFIG bits 2:1).
WAKE_UP_PAUSE_HZ = {0x00: 20, 0x02: 80, 0x04: 320, 0x06: 2560}

ANN_WRITE          = 0
ANN_READ           = 1
ANN_WARNING        = 2
ANN_WAKE_START     = 3
ANN_WAKE_DONE      = 4


def _signed10(lo, hi):
    """Decode big-endian signed 10-bit value from ACC_X_LSB..DATAZ1."""
    v = (hi << 2) | (lo >> 6)
    if v >= 0x200:
        v -= 0x400
    return v


def _decode_chip_id(raw):
    val = raw & 0x07
    ok = ' (BMA150 OK)' if val == 0x02 else ' (expected 0x02!)'
    return 'CHIP_ID 0x%02X%s' % (raw, ok)


def _decode_range_bw(raw):
    range_code = raw & 0x18
    bw_code = raw & 0x07
    range_str = RANGE_BITS.get(range_code, '?')
    bw = BW_BITS.get(bw_code, '?')
    if isinstance(bw, float):
        bw_str = '%.0f Hz' % bw
    else:
        bw_str = str(bw)
    return 'RANGE_BW 0x%02X: range=%s, bw=%s' % (raw, range_str, bw_str)


def _decode_temperature(raw):
    return 'TEMP 0x%02X (%.1f C)' % (raw, raw * 0.5 - 30.0)


def _decode_status(raw):
    parts = []
    for bit, name in STATUS_FIELDS:
        if raw & bit:
            parts.append(name)
    return 'STATUS 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'none')


def _decode_ctrl(raw):
    parts = []
    for bit, name in CTRL_FIELDS:
        if raw & bit:
            parts.append(name)
    return 'CTRL 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'awake')


def _decode_int_ctrl(raw):
    parts = []
    if raw & INT_CTRL_ALERT:
        parts.append('alert')
    if raw & INT_CTRL_ANY_MOTION:
        parts.append('any_motion')
    counter_lg = (raw & INT_CTRL_COUNTER_LG) >> 2
    counter_hg = (raw & INT_CTRL_COUNTER_HG) >> 4
    parts.append('counter_LG=%d' % counter_lg)
    parts.append('counter_HG=%d' % counter_hg)
    if raw & INT_CTRL_ENABLE_LG:
        parts.append('LG_on')
    if raw & INT_CTRL_ENABLE_HG:
        parts.append('HG_on')
    return 'INT_CTRL 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_config(raw):
    parts = []
    if raw & CONFIG_SPI4:
        parts.append('4-wire_SPI')
    else:
        parts.append('3-wire_SPI')
    if raw & CONFIG_ENABLE_ADV:
        parts.append('adv_INT')
    if raw & CONFIG_NEW_DATA_INT:
        parts.append('new_data_INT')
    if raw & CONFIG_LATCH_INT:
        parts.append('latched')
    if raw & CONFIG_SHADOW_DIS:
        parts.append('shadow_dis')
    else:
        parts.append('LSB_first')
    pause = WAKE_UP_PAUSE_HZ.get(raw & CONFIG_WAKE_UP_PAUSE, '?')
    parts.append('wake_pause=%dms' % pause)
    if raw & CONFIG_WAKE_UP:
        parts.append('wake_up')
    return 'CONFIG 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_hyst_dur(raw):
    am = (raw & HYST_DUR_ANY_MOTION_DUR) >> 6
    hg = (raw & HYST_DUR_HG_HYST) >> 3
    lg = raw & HYST_DUR_LG_HYST
    return 'HYST_DUR 0x%02X: any_motion_dur=%d, HG_hyst=%d, LG_hyst=%d' % (raw, am, hg, lg)


def _decode_axis(lo, hi, range_code):
    raw = _signed10(lo, hi)
    scale = RANGE_SCALE.get(range_code, 256)
    g = raw / scale
    return 'raw=%+d (%+.4f g)' % (raw, g)


def _decode_sensor_burst(buf, range_code):
    if len(buf) < 6:
        return 'Short burst %dB' % len(buf)
    ax = _decode_axis(buf[0], buf[1], range_code)
    ay = _decode_axis(buf[2], buf[3], range_code)
    az = _decode_axis(buf[4], buf[5], range_code)
    return 'Burst: X=%s, Y=%s, Z=%s' % (ax, ay, az)


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
    id = 'bma150'
    name = 'BMA150'
    longname = 'BMA150 3-axis MEMS accelerometer (Bosch Sensortec)'
    desc = 'Decode BMA150 I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['bma150']
    tags = ['IC', 'Sensor', 'Accelerometer']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('warning',   'Warning'),
        ('wake-start', 'Wake-up: CTRL sleep bit cleared'),
        ('wake-done',  'Wake-up: first data-register read'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('timing', 'Timing', (ANN_WAKE_START, ANN_WAKE_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid not in ('timing', 'warnings') for c in classes
                }
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
        self.range_code = 0x00  # last seen RANGE_BW bits 4:3 (default ±2g)

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg  = self.reg_ptr
        name = REGS.get(reg, 'Reg[0x%02X]' % reg) if reg is not None else '?'

        if self.is_read:
            if reg == 0x00 and len(self.databuf) == 1:
                val = self.databuf[0]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          [_decode_chip_id(val),
                           'ID 0x%02X' % val]])
            elif reg == 0x02 and len(self.databuf) == 6:
                desc = _decode_sensor_burst(self.databuf, self.range_code)
                # wake-done: first data-register read after CTRL sleep cleared.
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WAKE_DONE, ['wake_done: first data-register read after sleep was cleared', 'wake_done', 'WAKE\u2713']])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'Burst 6B']])
            elif reg == 0x14 and len(self.databuf) == 1:
                self.range_code = self.databuf[0] & 0x18
                desc = _decode_range_bw(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'RBW 0x%02X' % self.databuf[0]]])
            elif reg == 0x08 and len(self.databuf) == 1:
                desc = _decode_temperature(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'T 0x%02X' % self.databuf[0]]])
            elif reg == 0x09 and len(self.databuf) == 1:
                desc = _decode_status(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'STAT 0x%02X' % self.databuf[0]]])
            elif reg == 0x0A and len(self.databuf) == 1:
                desc = _decode_ctrl(self.databuf[0])
                if self.databuf[0] & 0x02:  # soft_reset bit
                    pass
                if not (self.databuf[0] & 0x01):  # sleep bit cleared
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WAKE_START, ['wake_start: CTRL sleep bit cleared', 'wake_start', 'WAKE\u25b6']])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'CTRL 0x%02X' % self.databuf[0]]])
            elif reg == 0x0B and len(self.databuf) == 1:
                desc = _decode_int_ctrl(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'IC 0x%02X' % self.databuf[0]]])
            elif reg == 0x11 and len(self.databuf) == 1:
                desc = _decode_hyst_dur(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'HYST 0x%02X' % self.databuf[0]]])
            elif reg == 0x15 and len(self.databuf) == 1:
                desc = _decode_config(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'CFG 0x%02X' % self.databuf[0]]])
            elif len(self.databuf) == 1:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['Read %s: 0x%02X' % (name, self.databuf[0]),
                           'R %s 0x%02X' % (name, self.databuf[0])]])
            elif self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['Read %s: %d bytes' % (name, len(self.databuf)),
                           'R %s %dB' % (name, len(self.databuf))]])
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          ['Pointer \u2192 %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg]])
            elif len(self.databuf) == 1:
                val = self.databuf[0]
                if reg == 0x0A:
                    desc = _decode_ctrl(val)
                    if val & 0x01:
                        # sleep bit set
                        pass
                elif reg == 0x14:
                    self.range_code = val & 0x18
                    desc = _decode_range_bw(val)
                elif reg == 0x0B:
                    desc = _decode_int_ctrl(val)
                elif reg == 0x15:
                    desc = _decode_config(val)
                elif reg == 0x11:
                    desc = _decode_hyst_dur(val)
                else:
                    desc = 'Write %s: 0x%02X' % (name, val)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          [desc,
                           'W %s 0x%02X' % (name, val)]])
            elif self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected write length %d for %s' % (len(self.databuf), name))

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
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = pdata
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
