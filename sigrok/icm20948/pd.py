import math

import sigrokdecode as srd

ICM_ADDRS = {0x68, 0x69}
AK_ADDR = 0x0C

REG_BANK_SEL = 0x7F
WHO_AM_I_VALUE = 0xEA

BANK0 = {
    0x00: 'WHO_AM_I', 0x03: 'USER_CTRL', 0x05: 'LP_CONFIG', 0x06: 'PWR_MGMT_1', 0x07: 'PWR_MGMT_2',
    0x0F: 'INT_PIN_CFG', 0x10: 'INT_ENABLE', 0x11: 'INT_ENABLE_1', 0x12: 'INT_ENABLE_2',
    0x13: 'INT_ENABLE_3', 0x17: 'I2C_MST_STATUS', 0x19: 'INT_STATUS', 0x1A: 'INT_STATUS_1',
    0x1B: 'INT_STATUS_2', 0x1C: 'INT_STATUS_3', 0x28: 'DELAY_TIMEH', 0x29: 'DELAY_TIMEL',
    0x2D: 'ACCEL_XOUT_H', 0x2E: 'ACCEL_XOUT_L', 0x2F: 'ACCEL_YOUT_H', 0x30: 'ACCEL_YOUT_L',
    0x31: 'ACCEL_ZOUT_H', 0x32: 'ACCEL_ZOUT_L', 0x33: 'GYRO_XOUT_H', 0x34: 'GYRO_XOUT_L',
    0x35: 'GYRO_YOUT_H', 0x36: 'GYRO_YOUT_L', 0x37: 'GYRO_ZOUT_H', 0x38: 'GYRO_ZOUT_L',
    0x39: 'TEMP_OUT_H', 0x3A: 'TEMP_OUT_L',
    0x66: 'FIFO_EN_1', 0x67: 'FIFO_EN_2', 0x68: 'FIFO_RST', 0x69: 'FIFO_MODE',
    0x70: 'FIFO_COUNTH', 0x71: 'FIFO_COUNTL', 0x72: 'FIFO_R_W', 0x74: 'DATA_RDY_STATUS',
    0x76: 'FIFO_CFG', 0x7F: 'REG_BANK_SEL',
}
for _i in range(24):
    BANK0[0x3B + _i] = 'EXT_SLV_SENS_DATA_%02d' % _i

BANK1 = {
    0x02: 'SELF_TEST_X_GYRO', 0x03: 'SELF_TEST_Y_GYRO', 0x04: 'SELF_TEST_Z_GYRO',
    0x0E: 'SELF_TEST_X_ACCEL', 0x0F: 'SELF_TEST_Y_ACCEL', 0x10: 'SELF_TEST_Z_ACCEL',
    0x14: 'XA_OFFS_H', 0x15: 'XA_OFFS_L', 0x17: 'YA_OFFS_H', 0x18: 'YA_OFFS_L',
    0x1A: 'ZA_OFFS_H', 0x1B: 'ZA_OFFS_L', 0x28: 'TIMEBASE_CORRECTION_PLL', 0x7F: 'REG_BANK_SEL',
}

BANK2 = {
    0x00: 'GYRO_SMPLRT_DIV', 0x01: 'GYRO_CONFIG_1', 0x02: 'GYRO_CONFIG_2',
    0x03: 'XG_OFFS_USRH', 0x04: 'XG_OFFS_USRL', 0x05: 'YG_OFFS_USRH', 0x06: 'YG_OFFS_USRL',
    0x07: 'ZG_OFFS_USRH', 0x08: 'ZG_OFFS_USRL', 0x09: 'ODR_ALIGN_EN',
    0x10: 'ACCEL_SMPLRT_DIV_1', 0x11: 'ACCEL_SMPLRT_DIV_2', 0x12: 'ACCEL_INTEL_CTRL',
    0x13: 'ACCEL_WOM_THR', 0x14: 'ACCEL_CONFIG', 0x15: 'ACCEL_CONFIG_2',
    0x52: 'FSYNC_CONFIG', 0x53: 'TEMP_CONFIG', 0x54: 'MOD_CTRL_USR', 0x7F: 'REG_BANK_SEL',
}

BANK3 = {
    0x00: 'I2C_MST_ODR_CONFIG', 0x01: 'I2C_MST_CTRL', 0x02: 'I2C_MST_DELAY_CTRL',
    0x13: 'I2C_SLV4_ADDR', 0x14: 'I2C_SLV4_REG', 0x15: 'I2C_SLV4_CTRL', 0x16: 'I2C_SLV4_DO',
    0x17: 'I2C_SLV4_DI', 0x7F: 'REG_BANK_SEL',
}
for _n in range(4):
    _b = 0x03 + 4 * _n
    BANK3[_b] = 'I2C_SLV%d_ADDR' % _n
    BANK3[_b + 1] = 'I2C_SLV%d_REG' % _n
    BANK3[_b + 2] = 'I2C_SLV%d_CTRL' % _n
    BANK3[_b + 3] = 'I2C_SLV%d_DO' % _n

BANKS = (BANK0, BANK1, BANK2, BANK3)

AK_REGS = {
    0x01: 'WIA2', 0x10: 'ST1', 0x11: 'HXL', 0x12: 'HXH', 0x13: 'HYL', 0x14: 'HYH',
    0x15: 'HZL', 0x16: 'HZH', 0x17: 'TMPS', 0x18: 'ST2', 0x31: 'CNTL2', 0x32: 'CNTL3',
}

GYRO_FS = {0: '±250 dps', 1: '±500 dps', 2: '±1000 dps', 3: '±2000 dps'}
ACCEL_FS = {0: '±2 g', 1: '±4 g', 2: '±8 g', 3: '±16 g'}
GYRO_SENS = (131.0, 65.5, 32.8, 16.4)
ACCEL_SENS = (16384.0, 8192.0, 4096.0, 2048.0)
GYRO_DLPF = {0: '196.6 Hz', 1: '151.8 Hz', 2: '119.5 Hz', 3: '51.2 Hz', 4: '23.9 Hz',
             5: '11.6 Hz', 6: '5.7 Hz', 7: '361.4 Hz'}
ACCEL_DLPF = {0: '246.0 Hz', 1: '246.0 Hz', 2: '111.4 Hz', 3: '50.4 Hz', 4: '23.9 Hz',
              5: '11.5 Hz', 6: '5.7 Hz', 7: '473 Hz'}
MST_CLK_KHZ = {0: 370.29, 2: 370.29, 3: 432.00, 4: 370.29, 5: 370.29, 6: 345.60, 7: 345.60,
               8: 304.94, 9: 432.00, 10: 432.00, 11: 432.00, 12: 471.27, 13: 432.00, 14: 345.60,
               15: 345.60}
AK_MODE = {0x00: 'power-down', 0x01: 'single', 0x02: 'continuous 1 (10 Hz)',
           0x04: 'continuous 2 (20 Hz)', 0x06: 'continuous 3 (50 Hz)',
           0x08: 'continuous 4 (100 Hz)', 0x10: 'self-test'}

G = 9.80665
DEG_TO_RAD = math.pi / 180.0

ANN_WRITE = 0
ANN_READ = 1
ANN_WARNING = 2
ANN_POWERON_START = 3
ANN_POWERON_READY = 4


def _s16be(hi, lo):
    v = (hi << 8) | lo
    return v - 0x10000 if v >= 0x8000 else v


def _s16le(lo, hi):
    return _s16be(hi, lo)


def _flags(val, table):
    parts = [name for bit, name in table if val & bit]
    return ', '.join(parts) if parts else 'none'


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'who_am_i' in m or 'chip id' in m:
        return 'ID?'
    if any(k in m for k in ('length', 'byte', 'short', 'missing', 'expected', 'no data', 'empty')):
        return 'LEN?'
    if any(k in m for k in ('unknown', 'unexpected', 'invalid', 'undefined', 'reserved')):
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
    id = 'icm20948'
    name = 'ICM-20948'
    longname = 'ICM-20948 9-axis MotionTracking device'
    desc = 'Decode ICM-20948 I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['icm20948']
    tags = ['IC', 'IMU', 'Sensor']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('warning', 'Warning'),
        ('poweron-start', 'Power-on: DEVICE_RESET written'),
        ('poweron-ready', 'Power-on: first register read after reset'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_WRITE, ANN_READ)),
        ('timing', 'Timing', (ANN_POWERON_START, ANN_POWERON_READY)),
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
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.databuf = []
        self.ss_block = None
        self.bank = 0
        self.accel_fs = 0
        self.gyro_fs = 0
        self.reset_seen = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    # -- naming ---------------------------------------------------------

    def _name(self, reg, bank=None):
        if self.addr == AK_ADDR:
            return AK_REGS.get(reg, 'AK[0x%02X]' % reg)
        b = self.bank if bank is None else bank
        return BANKS[b].get(reg, 'Reg[0x%02X]' % reg)

    def _label(self, reg):
        """Register name with the bank number, e.g. ``B2:GYRO_CONFIG_1``."""
        if self.addr == AK_ADDR:
            return self._name(reg)
        return 'B%d:%s' % (self.bank, self._name(reg))

    # -- field decoding -------------------------------------------------

    def _decode_value(self, reg, val):
        """Decode one register byte of the current bank into a description."""
        if self.addr == AK_ADDR:
            if reg == 0x31:
                return 'CNTL2 0x%02X: mode %s' % (val, AK_MODE.get(val & 0x1F, '?'))
            if reg == 0x32:
                return 'CNTL3 0x%02X: %s' % (val, 'SRST' if val & 1 else 'none')
            if reg == 0x10:
                return 'ST1 0x%02X: %s' % (val, _flags(val, ((0x02, 'DOR'), (0x01, 'DRDY'))))
            if reg == 0x18:
                return 'ST2 0x%02X: %s' % (val, _flags(val, ((0x08, 'HOFL'),)))
            return None
        name = self._name(reg)
        b = self.bank
        if reg == REG_BANK_SEL:
            return 'REG_BANK_SEL 0x%02X: bank %d' % (val, (val >> 4) & 3)
        if b == 0:
            if reg == 0x06:
                return 'PWR_MGMT_1 0x%02X: %s, CLKSEL=%d' % (val, _flags(val, (
                    (0x80, 'DEVICE_RESET'), (0x40, 'SLEEP'), (0x20, 'LP_EN'), (0x08, 'TEMP_DIS'))), val & 7)
            if reg == 0x07:
                return 'PWR_MGMT_2 0x%02X: accel %s, gyro %s' % (
                    val, 'off' if (val >> 3) & 7 == 7 else 'on', 'off' if val & 7 == 7 else 'on')
            if reg == 0x03:
                return 'USER_CTRL 0x%02X: %s' % (val, _flags(val, (
                    (0x80, 'DMP_EN'), (0x40, 'FIFO_EN'), (0x20, 'I2C_MST_EN'), (0x10, 'I2C_IF_DIS'),
                    (0x08, 'DMP_RST'), (0x04, 'SRAM_RST'), (0x02, 'I2C_MST_RST'))))
            if reg == 0x05:
                return 'LP_CONFIG 0x%02X: %s' % (val, _flags(val, (
                    (0x40, 'I2C_MST_CYCLE'), (0x20, 'ACCEL_CYCLE'), (0x10, 'GYRO_CYCLE'))))
            if reg == 0x0F:
                return 'INT_PIN_CFG 0x%02X: %s' % (val, _flags(val, (
                    (0x80, 'INT1_ACTL'), (0x40, 'INT1_OPEN'), (0x20, 'INT1_LATCH_EN'),
                    (0x10, 'INT_ANYRD_2CLEAR'), (0x08, 'ACTL_FSYNC'), (0x04, 'FSYNC_INT_MODE_EN'),
                    (0x02, 'BYPASS_EN'))))
            if reg == 0x10:
                return 'INT_ENABLE 0x%02X: %s' % (val, _flags(val, (
                    (0x80, 'REG_WOF_EN'), (0x08, 'WOM_INT_EN'), (0x04, 'PLL_RDY_EN'),
                    (0x02, 'DMP_INT1_EN'), (0x01, 'I2C_MST_INT_EN'))))
            if reg == 0x11:
                return 'INT_ENABLE_1 0x%02X: %s' % (val, _flags(val, ((0x01, 'RAW_DATA_0_RDY_EN'),)))
            if reg == 0x19:
                return 'INT_STATUS 0x%02X: %s' % (val, _flags(val, (
                    (0x08, 'WOM_INT'), (0x04, 'PLL_RDY_INT'), (0x02, 'DMP_INT1'), (0x01, 'I2C_MST_INT'))))
            if reg == 0x1A:
                return 'INT_STATUS_1 0x%02X: %s' % (val, _flags(val, ((0x01, 'RAW_DATA_0_RDY_INT'),)))
            if reg == 0x17:
                return 'I2C_MST_STATUS 0x%02X: %s' % (val, _flags(val, (
                    (0x80, 'PASS_THROUGH'), (0x40, 'SLV4_DONE'), (0x20, 'LOST_ARB'), (0x10, 'SLV4_NACK'),
                    (0x08, 'SLV3_NACK'), (0x04, 'SLV2_NACK'), (0x02, 'SLV1_NACK'), (0x01, 'SLV0_NACK'))))
            if reg == 0x00:
                return 'WHO_AM_I 0x%02X%s' % (val, ' (ICM-20948 OK)' if val == WHO_AM_I_VALUE
                                               else ' (expected 0xEA!)')
            if reg == 0x69:
                return 'FIFO_MODE 0x%02X: %s' % (val, 'snapshot' if val & 1 else 'stream')
        elif b == 2:
            if reg == 0x01:
                return 'GYRO_CONFIG_1 0x%02X: FS=%s, DLPF=%d (%s), filter %s' % (
                    val, GYRO_FS[(val >> 1) & 3], (val >> 3) & 7, GYRO_DLPF[(val >> 3) & 7],
                    'on' if val & 1 else 'bypassed')
            if reg == 0x14:
                return 'ACCEL_CONFIG 0x%02X: FS=%s, DLPF=%d (%s), filter %s' % (
                    val, ACCEL_FS[(val >> 1) & 3], (val >> 3) & 7, ACCEL_DLPF[(val >> 3) & 7],
                    'on' if val & 1 else 'bypassed')
            if reg == 0x00:
                return 'GYRO_SMPLRT_DIV %d: ODR %.1f Hz' % (val, 1125.0 / (1 + val))
            if reg == 0x10:
                return 'ACCEL_SMPLRT_DIV_1 0x%02X: div[11:8]=%d' % (val, val & 0x0F)
            if reg == 0x11:
                return 'ACCEL_SMPLRT_DIV_2 %d: div[7:0]' % val
            if reg == 0x12:
                return 'ACCEL_INTEL_CTRL 0x%02X: %s' % (val, _flags(val, (
                    (0x02, 'ACCEL_INTEL_EN'), (0x01, 'ACCEL_INTEL_MODE_INT'))))
            if reg == 0x13:
                return 'ACCEL_WOM_THR %d: %d mg' % (val, val * 4)
            if reg == 0x02:
                return 'GYRO_CONFIG_2 0x%02X: %s, AVG=%d' % (val, _flags(val, (
                    (0x20, 'XGYRO_CTEN'), (0x10, 'YGYRO_CTEN'), (0x08, 'ZGYRO_CTEN'))), val & 7)
            if reg == 0x15:
                return 'ACCEL_CONFIG_2 0x%02X: %s, DEC3_CFG=%d' % (val, _flags(val, (
                    (0x10, 'AX_ST_EN'), (0x08, 'AY_ST_EN'), (0x04, 'AZ_ST_EN'))), val & 3)
        elif b == 3:
            if reg == 0x01:
                return 'I2C_MST_CTRL 0x%02X: CLK=%d (%.1f kHz)%s' % (
                    val, val & 0x0F, MST_CLK_KHZ.get(val & 0x0F, 0.0), ', MULT_MST_EN' if val & 0x80 else '')
            if name.endswith('_ADDR'):
                return '%s 0x%02X: %s, address 0x%02X' % (name, val, 'read' if val & 0x80 else 'write', val & 0x7F)
            if name.endswith('_CTRL') and name.startswith('I2C_SLV'):
                if name.startswith('I2C_SLV4'):
                    return '%s 0x%02X: %s' % (name, val, _flags(val, ((0x80, 'EN'), (0x40, 'INT_EN'), (0x20, 'REG_DIS'))))
                return '%s 0x%02X: %s, LENG=%d' % (name, val, _flags(val, (
                    (0x80, 'EN'), (0x40, 'BYTE_SW'), (0x20, 'REG_DIS'), (0x10, 'GRP'))), val & 0x0F)
        return None

    def _track(self, reg, val):
        """Update the cached bank / full-scale state from a written or read byte."""
        if self.addr == AK_ADDR:
            return
        if reg == REG_BANK_SEL:
            self.bank = (val >> 4) & 3
        elif self.bank == 2 and reg == 0x14:
            self.accel_fs = (val >> 1) & 3
        elif self.bank == 2 and reg == 0x01:
            self.gyro_fs = (val >> 1) & 3

    # -- transactions ---------------------------------------------------

    def _emit(self, cls, long_text, short_text):
        self.put(self.ss_block, self.es, self.out_ann, [cls, [long_text, short_text]])

    def _annotate_write(self, reg):
        if not self.databuf:
            self._emit(ANN_WRITE, 'Pointer → %s (0x%02X)' % (self._label(reg), reg), 'PTR 0x%02X' % reg)
            return
        label = self._label(reg)
        desc = []
        marker = False
        for i, val in enumerate(self.databuf):
            r = reg + i
            text = self._decode_value(r, val)
            desc.append(text if text else 'Write %s: 0x%02X' % (self._name(r), val))
            if (self.addr in ICM_ADDRS and self.bank == 0 and r == 0x06 and val & 0x80):
                marker = True
            self._track(r, val)
        if len(self.databuf) == 1:
            self._emit(ANN_WRITE, '[%s] %s' % (label, desc[0]), 'W %s 0x%02X' % (self._name(reg), self.databuf[0]))
        else:
            self._emit(ANN_WRITE, '[%s] Write %d bytes: %s' % (label, len(self.databuf), '; '.join(desc)),
                       'W %s %dB' % (self._name(reg), len(self.databuf)))
        if marker:
            self.reset_seen = True
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_POWERON_START, ['poweron_start: PWR_MGMT_1 written with DEVICE_RESET set',
                                          'poweron_start', '▶RST']])

    def _burst_motion(self, reg, buf):
        """Decode accel / gyro / temperature reads of bank 0; None if the read is not one."""
        asens = ACCEL_SENS[self.accel_fs]
        gsens = GYRO_SENS[self.gyro_fs]
        if reg == 0x2D and len(buf) >= 6:
            a = [_s16be(buf[2 * i], buf[2 * i + 1]) / asens * G for i in range(3)]
            text = 'accel %+.3f %+.3f %+.3f m/s²' % tuple(a)
            if len(buf) >= 14:
                g = [_s16be(buf[6 + 2 * i], buf[7 + 2 * i]) / gsens * DEG_TO_RAD for i in range(3)]
                t = _s16be(buf[12], buf[13]) / 333.87 + 21.0
                text += '; gyro %+.4f %+.4f %+.4f rad/s; temp %.2f °C' % (g[0], g[1], g[2], t)
            return text, 'A/G/T'
        if reg == 0x33 and len(buf) >= 6:
            g = [_s16be(buf[2 * i], buf[2 * i + 1]) / gsens * DEG_TO_RAD for i in range(3)]
            return 'gyro %+.4f %+.4f %+.4f rad/s' % tuple(g), 'G'
        if reg == 0x39 and len(buf) >= 2:
            return 'temp %.2f °C' % (_s16be(buf[0], buf[1]) / 333.87 + 21.0), 'T'
        return None

    def _burst_mag(self, buf):
        if len(buf) < 9:
            return None
        m = [_s16le(buf[1 + 2 * i], buf[2 + 2 * i]) * 0.15 for i in range(3)]
        flags = []
        if buf[0] & 1:
            flags.append('DRDY')
        if buf[8] & 0x08:
            flags.append('HOFL')
        return 'mag %+.1f %+.1f %+.1f µT (%s)' % (m[0], m[1], m[2], ', '.join(flags) or 'none'), 'M'

    def _annotate_read(self, reg):
        buf = self.databuf
        if not buf:
            self._warn(self.ss_block, self.es, 'Empty read for %s' % self._name(reg))
            return
        label = self._label(reg)
        for i, val in enumerate(buf):
            self._track(reg + i, val)
        # First register read after the reset write ends the power-on window.
        if self.reset_seen and self.addr in ICM_ADDRS:
            self.reset_seen = False
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_POWERON_READY, ['poweron_ready: first register read after DEVICE_RESET',
                                          'poweron_ready', '●RDY']])
        decoded = None
        if self.addr in ICM_ADDRS and self.bank == 0:
            if 0x2D <= reg <= 0x3A:
                decoded = self._burst_motion(reg, buf)
            elif reg == 0x3B:
                decoded = self._burst_mag(buf)
        if decoded:
            self._emit(ANN_READ, '[%s] Burst %dB: %s' % (label, len(buf), decoded[0]),
                       '%s %dB' % (decoded[1], len(buf)))
        elif len(buf) == 1:
            text = self._decode_value(reg, buf[0])
            if reg == 0x00 and self.bank == 0 and self.addr in ICM_ADDRS and buf[0] != WHO_AM_I_VALUE:
                self._warn(self.ss_block, self.es,
                           'WHO_AM_I mismatch: expected 0x%02X, got 0x%02X' % (WHO_AM_I_VALUE, buf[0]))
            self._emit(ANN_READ, '[%s] %s' % (label, text if text else 'Read %s: 0x%02X' % (self._name(reg), buf[0])),
                       'R %s 0x%02X' % (self._name(reg), buf[0]))
        else:
            self._emit(ANN_READ, '[%s] Read %d bytes: %s' % (label, len(buf), ' '.join('%02X' % b for b in buf[:16])),
                       'R %s %dB' % (self._name(reg), len(buf)))

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        if reg is None:
            return
        if self.is_read:
            self._annotate_read(reg)
        else:
            self._annotate_write(reg)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ICM_ADDRS and pdata != AK_ADDR:
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
