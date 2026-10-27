"""LSM303 sigrok protocol decoder.

Decodes the two I²C slaves of an STMicroelectronics LSM303 e-compass:

- accelerometer at 0x19, whose 8-bit sub-address carries the register in
  its 7 LSbs and the auto-increment enable in its MSb;
- magnetometer at 0x1E, whose pointer always auto-increments.

Annotates every named register and the key fields (ODR, full-scale range,
operating mode, FIFO mode, magnetometer gain / data rate / mode), assembles
the accelerometer output burst into *g* and the magnetometer burst (X, Z,
Y order) into µT, checks the magnetometer identity, and emits the
`odr_start` / `odr_ready` timing pair for the first-data-ready constraint.
"""

import sigrokdecode as srd

ACCEL_ADDR = 0x19
MAG_ADDR = 0x1E
ADDRS = {ACCEL_ADDR, MAG_ADDR}

ACCEL_REGS = {
    0x20: 'CTRL_REG1_A', 0x21: 'CTRL_REG2_A', 0x22: 'CTRL_REG3_A',
    0x23: 'CTRL_REG4_A', 0x24: 'CTRL_REG5_A', 0x25: 'CTRL_REG6_A',
    0x26: 'REFERENCE_A', 0x27: 'STATUS_REG_A',
    0x28: 'OUT_X_L_A', 0x29: 'OUT_X_H_A', 0x2A: 'OUT_Y_L_A',
    0x2B: 'OUT_Y_H_A', 0x2C: 'OUT_Z_L_A', 0x2D: 'OUT_Z_H_A',
    0x2E: 'FIFO_CTRL_REG_A', 0x2F: 'FIFO_SRC_REG_A',
    0x30: 'INT1_CFG_A', 0x31: 'INT1_SOURCE_A', 0x32: 'INT1_THS_A',
    0x33: 'INT1_DURATION_A', 0x34: 'INT2_CFG_A', 0x35: 'INT2_SOURCE_A',
    0x36: 'INT2_THS_A', 0x37: 'INT2_DURATION_A',
    0x38: 'CLICK_CFG_A', 0x39: 'CLICK_SRC_A', 0x3A: 'CLICK_THS_A',
    0x3B: 'TIME_LIMIT_A', 0x3C: 'TIME_LATENCY_A', 0x3D: 'TIME_WINDOW_A',
}
MAG_REGS = {
    0x00: 'CRA_REG_M', 0x01: 'CRB_REG_M', 0x02: 'MR_REG_M',
    0x03: 'OUT_X_H_M', 0x04: 'OUT_X_L_M', 0x05: 'OUT_Z_H_M',
    0x06: 'OUT_Z_L_M', 0x07: 'OUT_Y_H_M', 0x08: 'OUT_Y_L_M',
    0x09: 'SR_REG_M', 0x0A: 'IRA_REG_M', 0x0B: 'IRB_REG_M',
    0x0C: 'IRC_REG_M', 0x31: 'TEMP_OUT_H_M', 0x32: 'TEMP_OUT_L_M',
}

ODR_NAMES = {
    0x0: 'power-down', 0x1: '1 Hz', 0x2: '10 Hz', 0x3: '25 Hz',
    0x4: '50 Hz', 0x5: '100 Hz', 0x6: '200 Hz', 0x7: '400 Hz',
    0x8: '1.620 kHz', 0x9: '1.344/5.376 kHz',
}
FS_NAMES = {0: '±2 g', 1: '±4 g', 2: '±8 g', 3: '±16 g'}
FIFO_NAMES = {0: 'bypass', 1: 'FIFO', 2: 'stream', 3: 'trigger'}
MAG_RATE_NAMES = {0: '0.75 Hz', 1: '1.5 Hz', 2: '3 Hz', 3: '7.5 Hz',
                  4: '15 Hz', 5: '30 Hz', 6: '75 Hz', 7: '220 Hz'}
MAG_GAIN_NAMES = {1: '±1.3 Ga', 2: '±1.9 Ga', 3: '±2.5 Ga', 4: '±4.0 Ga',
                  5: '±4.7 Ga', 6: '±5.6 Ga', 7: '±8.1 Ga'}
MAG_GAINS = {0x20: (1100, 980), 0x40: (855, 760), 0x60: (670, 600),
             0x80: (450, 400), 0xA0: (400, 355), 0xC0: (330, 295),
             0xE0: (230, 205)}
SENSITIVITY_MG = {
    (0, 0): (16, 32, 64, 192), (1, 0): (4, 8, 16, 48), (0, 1): (1, 2, 4, 12),
}

ANN_WRITE = 0
ANN_READ = 1
ANN_FIELD = 2
ANN_WARNING = 3
ANN_ODR_START = 4
ANN_ODR_READY = 5


def _signed16(high, low):
    value = ((high & 0xFF) << 8) | (low & 0xFF)
    return value - 0x10000 if value & 0x8000 else value


class Decoder(srd.Decoder):
    api_version = 3
    id = 'lsm303'
    name = 'LSM303'
    longname = 'STMicroelectronics LSM303 e-compass'
    desc = 'Decode LSM303 accelerometer and magnetometer I²C transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['lsm303']
    tags = ['IC', 'Sensor', 'Accelerometer', 'Magnetometer']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('field', 'Decoded field'),
        ('warning', 'Warning'),
        ('odr-start', 'odr_start: output data rate started'),
        ('odr-ready', 'odr_ready: first sample set ready'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_WRITE, ANN_READ, ANN_FIELD)),
        ('timing', 'Timing', (ANN_ODR_START, ANN_ODR_READY)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.auto_inc = False
        self.databuf = []
        self.ss_block = None
        self.odr_code = 0
        self.lpen = 0
        self.hr = 0
        self.fs_code = 0
        self.mag_gain = 0x20
        self.mag_rate = 4

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _ann(self, ss, es, cls, text):
        strs = [text] if isinstance(text, str) else list(text)
        self.put(ss, es, self.out_ann, [cls, strs])

    def _emit(self, ss, es, kind, value):
        """Emit the OUTPUT_PYTHON mirror of a data / status annotation."""
        self.put(ss, es, self.out_python, (kind, value))

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, 'WARN']])

    def _reg_name(self, reg):
        if self.addr == MAG_ADDR:
            return MAG_REGS.get(reg)
        return ACCEL_REGS.get(reg)

    def _decode_write(self, ss, es):
        name = self._reg_name(self.reg_ptr)
        if name is None:
            self._ann(ss, es, ANN_WRITE, ['0x%02X write' % self.reg_ptr,
                                          '0x%02X' % self.reg_ptr, '?'])
            return
        if self.addr == ACCEL_ADDR:
            self._ann(ss, es, ANN_WRITE, ['%s <- 0x%02X' % (name, self.databuf[0]),
                                          '%s' % name, name[:6]])
            self._emit(ss, es, 'WRITE', (self.reg_ptr, self.databuf[0]))
            self._decode_accel_write(self.reg_ptr, self.databuf[0])
        else:
            self._ann(ss, es, ANN_WRITE, ['%s <- 0x%02X' % (name, self.databuf[0]),
                                          '%s' % name, name[:6]])
            self._emit(ss, es, 'WRITE', (self.reg_ptr, self.databuf[0]))
            self._decode_mag_write(self.reg_ptr, self.databuf[0])

    def _decode_accel_write(self, reg, value):
        if reg == 0x20:
            self.odr_code = (value >> 4) & 0x0F
            self.lpen = (value >> 3) & 1
            self._ann(self.ss_block, self.es, ANN_FIELD,
                      ['ODR = %s' % ODR_NAMES.get(self.odr_code, '?'),
                       'ODR %s' % ODR_NAMES.get(self.odr_code, '?'), 'ODR'])
            if self.odr_code != 0:
                self._ann(self.ss_block, self.es, ANN_ODR_START,
                          ['odr_start: CTRL_REG1_A write with ODR=%s' %
                           ODR_NAMES.get(self.odr_code, '?'), 'odr_start', 'ODR▶'])
        elif reg == 0x23:
            self.fs_code = (value >> 4) & 0x03
            self.hr = (value >> 3) & 1
            self._ann(self.ss_block, self.es, ANN_FIELD,
                      ['FS = %s, %s' % (FS_NAMES.get(self.fs_code, '?'),
                                        'high-resolution' if self.hr else 'normal/low-power'),
                       'FS %s' % FS_NAMES.get(self.fs_code, '?'), 'FS'])
        elif reg == 0x2E:
            mode = (value >> 6) & 0x03
            self._ann(self.ss_block, self.es, ANN_FIELD,
                      ['FIFO mode = %s' % FIFO_NAMES.get(mode, '?'),
                       'FIFO %s' % FIFO_NAMES.get(mode, '?'), 'FIFO'])

    def _decode_mag_write(self, reg, value):
        if reg == 0x00:
            rate = (value >> 2) & 0x07
            self.mag_rate = rate
            self._ann(self.ss_block, self.es, ANN_FIELD,
                      ['DO = %s' % MAG_RATE_NAMES.get(rate, '?'),
                       'DO %s' % MAG_RATE_NAMES.get(rate, '?'), 'DO'])
        elif reg == 0x01:
            self.mag_gain = value & 0xE0
            gain = (value >> 5) & 0x07
            self._ann(self.ss_block, self.es, ANN_FIELD,
                      ['GN = %s' % MAG_GAIN_NAMES.get(gain, '?'),
                       'GN %s' % MAG_GAIN_NAMES.get(gain, '?'), 'GN'])
        elif reg == 0x02:
            mode = value & 0x03
            name = {0: 'continuous', 1: 'single', 3: 'sleep'}.get(mode, '?')
            self._ann(self.ss_block, self.es, ANN_FIELD,
                      ['MD = %s' % name, 'MD %s' % name, 'MD'])

    def _decode_read(self, ss, es):
        # Accelerometer output burst (0x28..0x2D with auto-increment).
        if self.addr == ACCEL_ADDR and self.reg_ptr == 0x28 and len(self.databuf) >= 6:
            raw = self.databuf
            shift = 8 if self.lpen and not self.hr else (6 if not self.hr else 4)
            sens = SENSITIVITY_MG.get((self.lpen, self.hr), (4, 8, 16, 48))[self.fs_code]
            vals = []
            for i in range(3):
                raw16 = _signed16(raw[i * 2 + 1], raw[i * 2])
                vals.append((raw16 >> shift) * sens / 1000.0)
            self._ann(ss, es, ANN_READ,
                      ['acceleration = (%.3f, %.3f, %.3f) g' % tuple(vals),
                       'a %.2f/%.2f/%.2f g' % tuple(vals), 'ACC'])
            self._emit(ss, es, 'ACCELERATION', tuple(vals))
            return
        # Magnetometer output burst (0x03..0x08).
        if self.addr == MAG_ADDR and self.reg_ptr == 0x03 and len(self.databuf) >= 6:
            raw = self.databuf
            xy, z = MAG_GAINS.get(self.mag_gain, (1100, 980))
            x = _signed16(raw[0], raw[1]) / xy * 100.0
            zz = _signed16(raw[2], raw[3]) / z * 100.0
            y = _signed16(raw[4], raw[5]) / xy * 100.0
            self._ann(ss, es, ANN_READ,
                      ['magnetic = (%.1f, %.1f, %.1f) µT' % (x, y, zz),
                       'm %.1f/%.1f/%.1f µT' % (x, y, zz), 'MAG'])
            self._emit(ss, es, 'MAGNETIC', (x, y, zz))
            return
        name = self._reg_name(self.reg_ptr)
        value = self.databuf[0] if self.databuf else 0
        if name is None:
            self._ann(ss, es, ANN_READ, ['0x%02X read' % self.reg_ptr,
                                         '0x%02X' % self.reg_ptr, '?'])
            return
        self._ann(ss, es, ANN_READ, ['%s = 0x%02X' % (name, value),
                                     '%s' % name, name[:6]])
        self._emit(ss, es, 'READ', (self.reg_ptr, value))
        if self.addr == ACCEL_ADDR:
            if self.reg_ptr == 0x27:
                if value & 0x08:
                    self._ann(ss, es, ANN_FIELD,
                              ['ZYXDA = 1, overrun ZYXOR = %d' % ((value >> 7) & 1),
                               'ZYXDA=1', 'DA'])
                    self._ann(ss, es, ANN_ODR_READY,
                              ['odr_ready: STATUS_REG_A read with ZYXDA=1',
                               'odr_ready', 'ODR✓'])
                else:
                    self._ann(ss, es, ANN_FIELD, ['ZYXDA = 0', 'ZYXDA=0', 'DA'])
        else:
            if self.reg_ptr in (0x0A, 0x0B, 0x0C):
                expected = {0x0A: 0x48, 0x0B: 0x34, 0x0C: 0x33}[self.reg_ptr]
                if value != expected:
                    self._warn(ss, es, 'identity byte 0x%02X != 0x%02X' % (value, expected))
            elif self.reg_ptr == 0x09:
                self._ann(ss, es, ANN_FIELD,
                          ['DRDY = %d, LOCK = %d' % (value & 1, (value >> 1) & 1),
                           'DRDY=%d LOCK=%d' % (value & 1, (value >> 1) & 1), 'SR'])

    def _finish_transaction(self):
        if self.state == 'IDLE' or self.addr is None:
            return
        ss = self.ss_block if self.ss_block is not None else self.ss
        es = self.es
        if self.is_read:
            self._decode_read(ss, es)
        elif self.databuf:
            self._decode_write(ss, es)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state in ('GET_DATA_WRITE', 'GET_REG'):
                pass
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
                self.reg_ptr = None
                self.auto_inc = False
            self.ss_block = ss
            self.state = 'GET_ADDR'
        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.databuf = []
            self.state = 'GET_DATA' if self.is_read else 'GET_REG'
        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG':
                self.reg_ptr = pdata
                if self.addr == ACCEL_ADDR:
                    self.auto_inc = bool(pdata & 0x80)
                    self.reg_ptr = pdata & 0x7F
                self.databuf = []
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)
        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA':
                self.databuf.append(pdata)
        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
