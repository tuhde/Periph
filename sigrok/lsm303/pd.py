"""LSM303 (LSM303DLHC) sigrok protocol decoder.

Sits on top of the sigrok `i2c` decoder and annotates bus transactions
with the LSM303DLHC register names and decoded field values. The module
presents two independent I²C slaves: the accelerometer at 0x19 (8-bit
sub-address, MSb = auto-increment, repeated-start reads) and the
magnetometer at 0x1E (address pointer without an auto-increment bit).
Emits an `odr_start` / `odr_ready` timing-marker pair across the chip's
`first_data_ready` turn-on timing constraint (see
specs/accelerometer/lsm303_timing.conf).
"""

import sigrokdecode as srd

ACC_ADDR = 0x19
MAG_ADDR = 0x1E
ADDRS = {ACC_ADDR, MAG_ADDR}

ACCEL_REGS = {
    0x20: 'CTRL_REG1_A',
    0x21: 'CTRL_REG2_A',
    0x22: 'CTRL_REG3_A',
    0x23: 'CTRL_REG4_A',
    0x24: 'CTRL_REG5_A',
    0x25: 'CTRL_REG6_A',
    0x26: 'REFERENCE_A',
    0x27: 'STATUS_REG_A',
    0x28: 'OUT_X_L_A',
    0x29: 'OUT_X_H_A',
    0x2A: 'OUT_Y_L_A',
    0x2B: 'OUT_Y_H_A',
    0x2C: 'OUT_Z_L_A',
    0x2D: 'OUT_Z_H_A',
    0x2E: 'FIFO_CTRL_REG_A',
    0x2F: 'FIFO_SRC_REG_A',
    0x30: 'INT1_CFG_A',
    0x31: 'INT1_SOURCE_A',
    0x32: 'INT1_THS_A',
    0x33: 'INT1_DURATION_A',
    0x34: 'INT2_CFG_A',
    0x35: 'INT2_SOURCE_A',
    0x36: 'INT2_THS_A',
    0x37: 'INT2_DURATION_A',
    0x38: 'CLICK_CFG_A',
    0x39: 'CLICK_SRC_A',
    0x3A: 'CLICK_THS_A',
    0x3B: 'TIME_LIMIT_A',
    0x3C: 'TIME_LATENCY_A',
    0x3D: 'TIME_WINDOW_A',
}

MAG_REGS = {
    0x00: 'CRA_REG_M',
    0x01: 'CRB_REG_M',
    0x02: 'MR_REG_M',
    0x03: 'OUT_X_H_M',
    0x04: 'OUT_X_L_M',
    0x05: 'OUT_Z_H_M',
    0x06: 'OUT_Z_L_M',
    0x07: 'OUT_Y_H_M',
    0x08: 'OUT_Y_L_M',
    0x09: 'SR_REG_M',
    0x0A: 'IRA_REG_M',
    0x0B: 'IRB_REG_M',
    0x0C: 'IRC_REG_M',
    0x31: 'TEMP_OUT_H_M',
    0x32: 'TEMP_OUT_L_M',
}

# CTRL_REG1_A (0x20) bits 7:4.
ODR_BITS = {
    0x0: 'power-down',
    0x1: '1 Hz',
    0x2: '10 Hz',
    0x3: '25 Hz',
    0x4: '50 Hz',
    0x5: '100 Hz',
    0x6: '200 Hz',
    0x7: '400 Hz',
    0x8: '1.620 kHz',
    0x9: '1.344 kHz',
}

# CTRL_REG2_A (0x21) bits 7:6.
HPM_BITS = {0x0: 'normal (reset on REFERENCE_A read)', 0x1: 'reference signal',
            0x2: 'normal', 0x3: 'autoreset on interrupt event'}

# CTRL_REG4_A (0x23) bits 5:4.
FS_BITS = {0x0: '±2g', 0x1: '±4g', 0x2: '±8g', 0x3: '±16g'}

# FIFO_CTRL_REG_A (0x2E) bits 7:6.
FM_BITS = {0x0: 'bypass', 0x1: 'FIFO', 0x2: 'stream', 0x3: 'stream-to-FIFO'}

# INTx_CFG_A bits 7:6 combination.
AOI6D_BITS = {
    (0, 0): 'OR combination',
    (0, 1): '6-direction movement',
    (1, 0): 'AND combination',
    (1, 1): '6-direction position',
}

# CRA_REG_M (0x00) bits 4:2.
DO_BITS = {
    0x0: '0.75 Hz', 0x1: '1.5 Hz', 0x2: '3 Hz', 0x3: '7.5 Hz',
    0x4: '15 Hz', 0x5: '30 Hz', 0x6: '75 Hz', 0x7: '220 Hz',
}

# CRB_REG_M (0x01) bits 7:5: (full scale µT, X/Y gain LSB/gauss, Z gain LSB/gauss).
GN_TABLE = {
    1: (130, 1100, 980),
    2: (190, 855, 760),
    3: (250, 670, 600),
    4: (400, 450, 400),
    5: (470, 400, 355),
    6: (560, 330, 295),
    7: (810, 230, 205),
}

# MR_REG_M (0x02) bits 1:0.
MD_BITS = {0x0: 'continuous conversion', 0x1: 'single conversion',
           0x2: 'sleep', 0x3: 'sleep'}

# Accelerometer sensitivity (mg/LSB) per full-scale code, per operating mode.
MG_HIGHRES = {0: 1, 1: 2, 2: 4, 3: 12}
MG_NORMAL = {0: 4, 1: 8, 2: 16, 3: 48}
MG_LOWPOWER = {0: 16, 1: 32, 2: 64, 3: 192}

MAG_IDENTITY = (0x48, 0x34, 0x33)

ANN_WRITE   = 0
ANN_READ    = 1
ANN_STATUS  = 2
ANN_TIMING  = 3
ANN_WARNING = 4


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'identity' in m:
        return 'ID?'
    if 'address' in m:
        return 'ADDR?'
    if any(k in m for k in ('length', 'byte', 'short', 'missing', 'no data')):
        return 'LEN?'
    if any(k in m for k in ('unknown', 'unexpected', 'invalid', 'undefined',
                            'reserved', 'out of range', 'mismatch', 'rejected',
                            'read-only')):
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


def _signed16(hi, lo):
    v = (hi << 8) | lo
    if v >= 0x8000:
        v -= 0x10000
    return v


def _flag_dec(bits, raw):
    """Comma-joined names of the (mask, name) entries whose bit is set in raw."""
    parts = []
    for entry in bits:
        mask, on = entry[0], entry[1]
        if raw & mask:
            parts.append(on)
    return parts


def _decode_ctrl1(raw, lpen, hr):
    odr = (raw >> 4) & 0x0F
    odr_str = ODR_BITS.get(odr, '?')
    if odr == 0x9 and lpen:
        odr_str = '5.376 kHz'
    mode = 'low-power' if lpen else ('high-resolution' if hr else 'normal')
    parts = ['ODR=%s' % odr_str, 'mode=%s (LPen=%d HR=%d)' % (mode, lpen, hr)]
    for bit, name in ((0x04, 'Z_en'), (0x02, 'Y_en'), (0x01, 'X_en')):
        if raw & bit:
            parts.append(name)
    if not (raw & 0x07):
        parts.append('all axes disabled')
    return 'CTRL_REG1_A 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_ctrl2(raw):
    hpm = (raw >> 6) & 0x03
    hpcf = (raw >> 4) & 0x03
    parts = ['HPM=%d (%s)' % (hpm, HPM_BITS.get(hpm, '?')), 'HPCF=%d' % hpcf]
    parts += _flag_dec([(0x08, 'FDS'), (0x04, 'HPCLICK'),
                        (0x02, 'HPIS2'), (0x01, 'HPIS1')], raw)
    return 'CTRL_REG2_A 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_ctrl3(raw):
    parts = _flag_dec([(0x80, 'I1_CLICK'), (0x40, 'I1_AOI1'), (0x20, 'I1_AOI2'),
                       (0x10, 'I1_DRDY1'), (0x08, 'I1_DRDY2'), (0x04, 'I1_WTM'),
                       (0x02, 'I1_OVERRUN')], raw)
    return 'CTRL_REG3_A 0x%02X (routes to INT1): %s' % (
        raw, ', '.join(parts) if parts else 'none')


def _decode_ctrl4(raw):
    fs = (raw >> 4) & 0x03
    parts = ['FS=%d (%s)' % (fs, FS_BITS.get(fs, '?'))]
    parts += _flag_dec([(0x80, 'BDU'), (0x40, 'BLE=1'), (0x08, 'HR')], raw)
    if raw & 0x06:
        parts.append('bits 2:1 not 0 (undefined!)')
    if raw & 0x01:
        parts.append('SIM=1 (unused on this I²C-only part)')
    return 'CTRL_REG4_A 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_ctrl5(raw):
    parts = _flag_dec([(0x80, 'BOOT'), (0x40, 'FIFO_EN'), (0x08, 'LIR_INT1'),
                       (0x04, 'D4D_INT1'), (0x02, 'LIR_INT2'), (0x01, 'D4D_INT2')], raw)
    return 'CTRL_REG5_A 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'none')


def _decode_ctrl6(raw):
    parts = _flag_dec([(0x80, 'I2_CLICKen'), (0x40, 'I2_INT1'), (0x20, 'I2_INT2'),
                       (0x10, 'BOOT_I1'), (0x04, 'P2_ACT')], raw)
    parts.append('INT polarity: %s (H_LACTIVE=%d)'
                 % ('active-low' if raw & 0x02 else 'active-high', raw & 0x02))
    return 'CTRL_REG6_A 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_status_a(raw):
    parts = _flag_dec([(0x80, 'ZYXOR'), (0x40, 'ZOR'), (0x20, 'YOR'), (0x10, 'XOR'),
                       (0x08, 'ZYXDA'), (0x04, 'ZDA'), (0x02, 'YDA'), (0x01, 'XDA')], raw)
    return 'STATUS_REG_A 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'no data')


def _decode_fifo_ctrl(raw):
    fm = (raw >> 6) & 0x03
    tr = (raw >> 5) & 0x01
    fth = raw & 0x1F
    return ('FIFO_CTRL_REG_A 0x%02X: FM=%d (%s), TR=INT%d, FTH=%d'
            % (raw, fm, FM_BITS.get(fm, '?'), 2 if tr else 1, fth))


def _decode_fifo_src(raw):
    parts = _flag_dec([(0x80, 'WTM'), (0x40, 'OVRN_FIFO'), (0x20, 'EMPTY')], raw)
    parts.append('FSS=%d unread samples' % (raw & 0x1F))
    return 'FIFO_SRC_REG_A 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_int_cfg(raw, num):
    aoi, sixd = (raw >> 7) & 1, (raw >> 6) & 1
    parts = ['AOI=%d/6D=%d (%s)' % (aoi, sixd, AOI6D_BITS.get((aoi, sixd), '?'))]
    parts += _flag_dec([(0x20, 'ZHIE'), (0x10, 'ZLIE'), (0x08, 'YHIE'),
                        (0x04, 'YLIE'), (0x02, 'XHIE'), (0x01, 'XLIE')], raw)
    return 'INT%d_CFG_A 0x%02X: %s' % (num, raw, ', '.join(parts))


def _decode_int_source(raw, num):
    parts = _flag_dec([(0x40, 'IA'), (0x20, 'ZH'), (0x10, 'ZL'), (0x08, 'YH'),
                       (0x04, 'YL'), (0x02, 'XH'), (0x01, 'XL')], raw)
    return 'INT%d_SOURCE_A 0x%02X: %s' % (num, raw, ', '.join(parts) if parts else 'no event')


def _decode_click_cfg(raw):
    parts = _flag_dec([(0x20, 'ZD'), (0x10, 'ZS'), (0x08, 'YD'),
                       (0x04, 'YS'), (0x02, 'XD'), (0x01, 'XS')], raw)
    return 'CLICK_CFG_A 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'no click enabled')


def _decode_click_src(raw):
    parts = _flag_dec([(0x40, 'IA'), (0x20, 'DCLICK'), (0x10, 'SCLICK'),
                       (0x08, 'Sign=negative'), (0x04, 'Z'), (0x02, 'Y'), (0x01, 'X')], raw)
    return 'CLICK_SRC_A 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'no click')


def _decode_click_ths(raw):
    return 'CLICK_THS_A 0x%02X: threshold %d (1 LSb = FS/128)' % (raw, raw & 0x7F)


def _decode_cra(raw, temp_en):
    do = (raw >> 2) & 0x07
    parts = ['DO=%d (%s)' % (do, DO_BITS.get(do, '?')), 'TEMP_EN=%d' % temp_en]
    if raw & 0x63:
        parts.append('bits 6:5/1:0 not 0 (undefined!)')
    return 'CRA_REG_M 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_crb(raw):
    gn = (raw >> 5) & 0x07
    if gn in GN_TABLE:
        fs, gxy, gz = GN_TABLE[gn]
        return ('CRB_REG_M 0x%02X: GN=%d (±%d µT, X/Y %d, Z %d LSb/gauss)'
                % (raw, gn, fs, gxy, gz))
    return 'CRB_REG_M 0x%02X: GN=%d (undefined!)' % (raw, gn)


def _decode_mr(raw):
    md = raw & 0x03
    parts = ['MD=%d (%s)' % (md, MD_BITS.get(md, '?'))]
    if raw & 0xFC:
        parts.append('bits 7:2 not 0 (undefined!)')
    return 'MR_REG_M 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_sr_m(raw):
    parts = _flag_dec([(0x02, 'LOCK=1'), (0x01, 'DRDY=1')], raw)
    return 'SR_REG_M 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'LOCK=0, DRDY=0')


def _decode_id_m(raws):
    ok = ' (OK)' if tuple(raws) == MAG_IDENTITY else ' (expected 0x48 0x34 0x33!)'
    vals = ' 0x'.join('%02X' % v for v in raws)
    try:
        s = ''.join(chr(b) for b in raws)
    except ValueError:
        s = '?'
    return 'Identity 0x%s = "%s"%s' % (vals, s, ok)


def _decode_accel_burst(buf, shift, mg):
    parts = []
    for name, i in (('X', 0), ('Y', 2), ('Z', 4)):
        raw = _signed16(buf[i + 1], buf[i])
        counts = raw >> shift
        parts.append('%s=%+d raw (%+.3f g)' % (name, raw, counts * mg / 1000.0))
    return 'Burst: %s' % ', '.join(parts)


def _decode_mag_axis(raw, gain):
    if raw <= -2048:
        return 'OVERFLOW'
    return '%+.2f µT' % (raw / gain * 100.0)


def _decode_mag_burst(buf, gn):
    raw_x = _signed16(buf[0], buf[1])
    raw_z = _signed16(buf[2], buf[3])
    raw_y = _signed16(buf[4], buf[5])
    if gn not in GN_TABLE:
        return 'Burst: raw X=%+d, Z=%+d, Y=%+d (undefined gain)' % (raw_x, raw_z, raw_y)
    gxy, gz = GN_TABLE[gn][1], GN_TABLE[gn][2]
    return 'Burst: X=%s, Z=%s, Y=%s' % (_decode_mag_axis(raw_x, gxy),
                                        _decode_mag_axis(raw_z, gz),
                                        _decode_mag_axis(raw_y, gxy))


def _decode_temp_m(buf):
    raw = _signed16(buf[0], buf[1]) >> 4
    return 'TEMP_OUT_M: %+.1f °C (relative)' % (raw / 8.0)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'lsm303'
    name = 'LSM303'
    longname = 'LSM303DLHC e-compass (STMicroelectronics)'
    desc = 'Decode LSM303 accelerometer (0x19) and magnetometer (0x1E) I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['lsm303']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('status',    'Status flags'),
        ('timing',    'Timing marker'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_TIMING,)),
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
        """OUTPUT_PYTHON mirror of each transaction-level data/status
        annotation: ('REG_READ' | 'REG_WRITE', (register, bytes))."""
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
            super().put(ss, es, out_py, ('I2C_' + rw, (getattr(self, 'addr', None), buf)))
        else:
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
        self.autoinc  = False
        # Last-seen accelerometer configuration (chip reset values:
        # ±2 g, normal mode).
        self.fs_code  = 0x0
        self.lpen     = 0
        self.hr       = 0
        # Last-seen magnetometer configuration (default GN=001, ±1.3 gauss).
        self.gn       = 1
        self.temp_en  = 0
        # Armed by an odr_start marker, cleared by odr_ready.
        self.await_odr_ready = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _reg_name(self, slave, reg):
        table = ACCEL_REGS if slave == ACC_ADDR else MAG_REGS
        return table.get(reg, 'Reg[0x%02X]' % reg)

    def _shift_mg(self):
        """(right-shift bits, mg/LSB) at the last-seen range and mode."""
        if self.lpen:
            cfg, shift = MG_LOWPOWER, 8
        elif self.hr:
            cfg, shift = MG_HIGHRES, 4
        else:
            cfg, shift = MG_NORMAL, 6
        return shift, cfg.get(self.fs_code, cfg[0])

    def _mag_next(self, reg):
        if reg == 0x08:
            return 0x03
        if reg >= 0x0C:
            return 0x00
        return reg + 1

    def _seq_regs(self, slave, start, count):
        """Register addresses for `count` consecutive bytes from `start`."""
        if slave == MAG_ADDR:
            regs = []
            reg = start
            for _ in range(count):
                regs.append(reg)
                reg = self._mag_next(reg)
        elif self.autoinc:
            regs = [(start + n) & 0x7F for n in range(count)]
        else:
            regs = [start] * count
        return regs

    def _emit_timing(self, ss, es, marker):
        if marker == 'odr_start':
            text = ['first_data_ready: CTRL_REG1_A (0x20) written with ODR != 0000 (odr_start)',
                    'odr_start', '→ODR']
        else:
            text = ['first_data_ready: first STATUS_REG_A (0x27) read with ZYXDA=1 (odr_ready)',
                    'odr_ready', 'ODR✓']
        self.put(ss, es, self.out_ann, [ANN_TIMING, text])

    def _accel_read_finish(self):
        buf = self.databuf
        regs = self._seq_regs(ACC_ADDR, self.reg_ptr & 0x7F, len(buf))
        reg0 = regs[0]
        shift, mg = self._shift_mg()

        if reg0 == 0x27 and len(buf) == 1:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ, [_decode_status_a(buf[0]), 'STAT 0x%02X' % buf[0]]])
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_STATUS,
                      ['new XYZ data ready (ZYXDA=1)' if buf[0] & 0x08
                       else 'no new data (ZYXDA=0)',
                       'ZYXDA=%d' % (1 if buf[0] & 0x08 else 0),
                       '0x%02X' % buf[0]]])
            self.put(self.ss_block, self.es, self.out_python, ('STATUS', (0x27, buf[0])))
            if buf[0] & 0x08 and self.await_odr_ready:
                self._emit_timing(self.ss_block, self.es, 'odr_ready')
            self.await_odr_ready = False
            return True

        if reg0 == 0x28 and len(buf) == 6:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ, [_decode_accel_burst(buf, shift, mg), 'Burst 6B']])
            self.put(self.ss_block, self.es, self.out_python,
                     ('MEASUREMENT', (0x28, bytes(b & 0xFF for b in buf[:6]))))
            return True

        if reg0 == 0x2F and len(buf) == 1:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ, [_decode_fifo_src(buf[0]), 'FSRC 0x%02X' % buf[0]]])
            if buf[0] & 0x20:
                state = 'FIFO empty'
            elif buf[0] & 0x40:
                state = 'FIFO overrun'
            elif buf[0] & 0x80:
                state = 'FIFO watermark reached'
            else:
                state = 'FIFO filling'
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_STATUS, [state, 'FSS=%d' % (buf[0] & 0x1F), '0x%02X' % buf[0]]])
            self.put(self.ss_block, self.es, self.out_python, ('STATUS', (0x2F, buf[0])))
            return True

        if reg0 in (0x31, 0x35) and len(buf) == 1:
            num = 1 if reg0 == 0x31 else 2
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ, [_decode_int_source(buf[0], num), 'ISRC%d 0x%02X' % (num, buf[0])]])
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_STATUS,
                      [('INT%d event active (IA=1)' % num) if buf[0] & 0x40
                       else 'no INT%d event (IA=0)' % num,
                       'IA=%d' % (1 if buf[0] & 0x40 else 0),
                       '0x%02X' % buf[0]]])
            self.put(self.ss_block, self.es, self.out_python, ('STATUS', (reg0, buf[0])))
            return True

        if reg0 == 0x39 and len(buf) == 1:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ, [_decode_click_src(buf[0]), 'CLKSRC 0x%02X' % buf[0]]])
            if buf[0] & 0x40:
                state = 'click active (IA=1, %s)'
                state = state % ('double' if buf[0] & 0x20 else 'single')
            else:
                state = 'no click (IA=0)'
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_STATUS, [state, 'IA=%d' % (1 if buf[0] & 0x40 else 0),
                                   '0x%02X' % buf[0]]])
            self.put(self.ss_block, self.es, self.out_python, ('STATUS', (0x39, buf[0])))
            return True

        decoded = {
            0x20: (lambda v: _decode_ctrl1(v, self.lpen, self.hr), 'C1'),
            0x21: (_decode_ctrl2, 'C2'),
            0x22: (_decode_ctrl3, 'C3'),
            0x23: (_decode_ctrl4, 'C4'),
            0x24: (_decode_ctrl5, 'C5'),
            0x25: (_decode_ctrl6, 'C6'),
            0x2E: (_decode_fifo_ctrl, 'FCTR'),
            0x30: (lambda v: _decode_int_cfg(v, 1), 'ICFG1'),
            0x34: (lambda v: _decode_int_cfg(v, 2), 'ICFG2'),
            0x38: (_decode_click_cfg, 'CLCFG'),
            0x3A: (_decode_click_ths, 'CTHS'),
        }
        if len(buf) == 1 and reg0 in decoded:
            fn, short = decoded[reg0]
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ, [fn(buf[0]), '%s 0x%02X' % (short, buf[0])]])
            if reg0 == 0x23:
                self.fs_code = (buf[0] >> 4) & 0x03
                self.hr = (buf[0] >> 3) & 0x01
            return True

        if len(buf) == 1:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ,
                      ['Read %s: 0x%02X' % (self._reg_name(ACC_ADDR, reg0), buf[0]),
                       'R %s' % self._reg_name(ACC_ADDR, reg0)]])
            return True
        return False

    def _mag_read_finish(self):
        buf = self.databuf
        regs = self._seq_regs(MAG_ADDR, self.reg_ptr, len(buf))
        reg0 = regs[0]

        if reg0 == 0x03 and len(buf) == 6:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ, [_decode_mag_burst(buf, self.gn), 'Burst 6B']])
            self.put(self.ss_block, self.es, self.out_python,
                     ('MEASUREMENT', (0x03, bytes(b & 0xFF for b in buf[:6]))))
            return True

        if reg0 == 0x0A and len(buf) == 3:
            if (buf[0], buf[1], buf[2]) != MAG_IDENTITY:
                self._warn(self.ss_block, self.es,
                           'Identity mismatch: 0x%02X 0x%02X 0x%02X, expected 0x48 0x34 0x33'
                           % (buf[0], buf[1], buf[2]))
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ, [_decode_id_m(buf[:3]), 'ID 0x%02X' % buf[0]]])
            self.put(self.ss_block, self.es, self.out_python,
                     ('IDENTITY', (0x0A, bytes(b & 0xFF for b in buf[:3]))))
            return True

        if reg0 == 0x31 and len(buf) == 2:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ, [_decode_temp_m(buf[:2]), 'TEMP']])
            self.put(self.ss_block, self.es, self.out_python,
                     ('MEASUREMENT', (0x31, bytes(b & 0xFF for b in buf[:2]))))
            return True

        if reg0 == 0x09 and len(buf) == 1:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ, [_decode_sr_m(buf[0]), 'SR 0x%02X' % buf[0]]])
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_STATUS,
                      [('DRDY=1 (new data)' if buf[0] & 0x01 else 'DRDY=0'),
                       'LOCK=%d' % (1 if buf[0] & 0x02 else 0),
                       '0x%02X' % buf[0]]])
            self.put(self.ss_block, self.es, self.out_python, ('STATUS', (0x09, buf[0])))
            return True

        if len(buf) == 1:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ,
                      ['Read %s: 0x%02X' % (self._reg_name(MAG_ADDR, reg0), buf[0]),
                       'R %s' % self._reg_name(MAG_ADDR, reg0)]])
            return True
        return False

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        slave = self.addr
        if slave not in ADDRS:
            return
        if self.reg_ptr is None:
            if self.databuf:
                self._warn(self.ss_block, self.es,
                           'Read %d byte(s) with no register pointer set'
                           % len(self.databuf))
            return

        if self.is_read:
            if not self.databuf:
                return
            handled = (self._accel_read_finish() if slave == ACC_ADDR
                       else self._mag_read_finish())
            if not handled:
                name = self._reg_name(slave, self.reg_ptr & 0x7F)
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d for %s' % (len(self.databuf), name))
            return

        # Write direction.
        raw_sub = self.reg_ptr
        sub = raw_sub & 0x7F if slave == ACC_ADDR else raw_sub
        name = self._reg_name(slave, sub)

        if not self.databuf:
            auto = ' (auto-increment)' if (slave == ACC_ADDR and raw_sub & 0x80) else ''
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_WRITE,
                      ['Sub-address → %s (0x%02X)%s' % (name, sub, auto),
                       'PTR 0x%02X' % raw_sub]])
            return

        if len(self.databuf) == 1:
            val = self.databuf[0]
            desc = None
            if slave == ACC_ADDR:
                if 0x20 <= sub <= 0x26 or 0x2E <= sub <= 0x3D:
                    if sub == 0x20:
                        self.lpen = (val >> 3) & 0x01
                        if self.lpen and self.hr:
                            self._warn(self.ss_block, self.es,
                                       'LPen=1 with HR=1: mode combination is undefined')
                        if (val >> 4) & 0x0F:
                            self._emit_timing(self.ss_block, self.es, 'odr_start')
                            self.await_odr_ready = True
                        desc = _decode_ctrl1(val, self.lpen, self.hr)
                    elif sub == 0x23 and val & 0x06:
                        self._warn(self.ss_block, self.es,
                                   'CTRL_REG4_A bits 2:1 must be 0 (got 0x%02X)' % val)
                    if sub == 0x23:
                        self.fs_code = (val >> 4) & 0x03
                        self.hr = (val >> 3) & 0x01
                    fns = {
                        0x21: _decode_ctrl2, 0x22: _decode_ctrl3, 0x23: _decode_ctrl4,
                        0x24: _decode_ctrl5, 0x25: _decode_ctrl6, 0x26: None,
                        0x2E: _decode_fifo_ctrl,
                        0x30: lambda v: _decode_int_cfg(v, 1),
                        0x34: lambda v: _decode_int_cfg(v, 2),
                        0x38: _decode_click_cfg,
                        0x3A: _decode_click_ths,
                    }
                    if sub in fns and fns[sub] is not None:
                        desc = fns[sub](val)
                elif sub < 0x20 or sub > 0x3D:
                    self._warn(self.ss_block, self.es,
                               'Write to reserved accelerometer register 0x%02X' % sub)
            else:
                if sub == 0x00:
                    self.temp_en = (val >> 7) & 0x01
                    desc = _decode_cra(val, self.temp_en)
                elif sub == 0x01:
                    self.gn = (val >> 5) & 0x07
                    desc = _decode_crb(val)
                elif sub == 0x02:
                    desc = _decode_mr(val)
                else:
                    self._warn(self.ss_block, self.es,
                               'Read-only or invalid magnetometer register 0x%02X (write ignored)'
                               % sub)
            if desc is None:
                desc = 'Write %s: 0x%02X' % (name, val)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_WRITE, [desc, 'W %s 0x%02X' % (name, val)]])
            return

        if len(self.databuf) <= 8:
            note = ' (auto-increment)' if slave == ACC_ADDR and raw_sub & 0x80 else ''
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_WRITE,
                      ['Write %s: %d bytes%s' % (name, len(self.databuf), note),
                       'W %s %dB' % (name, len(self.databuf))]])
        else:
            self._warn(self.ss_block, self.es,
                       'Unexpected write length %d for %s' % (len(self.databuf), name))

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass  # pointer already set; keep databuf and state
            elif ptype == 'START REPEAT' and self.state == 'GET_DATA_WRITE' and not self.databuf:
                self._finish_transaction()  # pointer-only write; keep reg_ptr
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
            self.autoinc = False
            self.databuf = []
            if self.is_read:
                self.state   = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = byte
                self.databuf = []
                if self.__dict__.get('addr') == ACC_ADDR and byte & 0x80:
                    self.autoinc = True
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
