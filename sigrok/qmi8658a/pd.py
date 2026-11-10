import math

import sigrokdecode as srd

ADDRS = {0x6A, 0x6B}

REGS = {
    0x00: 'WHO_AM_I', 0x01: 'REVISION_ID', 0x02: 'CTRL1', 0x03: 'CTRL2', 0x04: 'CTRL3',
    0x06: 'CTRL5', 0x08: 'CTRL7', 0x09: 'CTRL8', 0x0A: 'CTRL9',
    0x0B: 'CAL1_L', 0x0C: 'CAL1_H', 0x0D: 'CAL2_L', 0x0E: 'CAL2_H',
    0x0F: 'CAL3_L', 0x10: 'CAL3_H', 0x11: 'CAL4_L', 0x12: 'CAL4_H',
    0x13: 'FIFO_WTM_TH', 0x14: 'FIFO_CTRL', 0x15: 'FIFO_SMPL_CNT', 0x16: 'FIFO_STATUS',
    0x17: 'FIFO_DATA', 0x2D: 'STATUSINT', 0x2E: 'STATUS0', 0x2F: 'STATUS1',
    0x30: 'TIMESTAMP_LOW', 0x31: 'TIMESTAMP_MID', 0x32: 'TIMESTAMP_HIGH',
    0x33: 'TEMP_L', 0x34: 'TEMP_H',
    0x35: 'AX_L', 0x36: 'AX_H', 0x37: 'AY_L', 0x38: 'AY_H', 0x39: 'AZ_L', 0x3A: 'AZ_H',
    0x3B: 'GX_L', 0x3C: 'GX_H', 0x3D: 'GY_L', 0x3E: 'GY_H', 0x3F: 'GZ_L', 0x40: 'GZ_H',
    0x46: 'COD_STATUS', 0x49: 'dQW_L', 0x4A: 'dQW_H', 0x4B: 'dQX_L', 0x4D: 'RESET_STATUS',
    0x51: 'dVX_L', 0x52: 'dVX_H', 0x53: 'dVY_L', 0x54: 'dVY_H', 0x55: 'dVZ_L', 0x56: 'dVZ_H',
    0x59: 'TAP_STATUS', 0x5A: 'STEP_CNT_LOW', 0x5B: 'STEP_CNT_MIDL', 0x5C: 'STEP_CNT_HIGH',
    0x60: 'RESET',
}

CONFIG_REGS = {0x02, 0x03, 0x04, 0x06, 0x08, 0x09, 0x0A}   # one register per transaction

CTRL9_CMDS = {
    0x00: 'ACK', 0x04: 'RST_FIFO', 0x05: 'REQ_FIFO', 0x08: 'WRITE_WOM_SETTING',
    0x09: 'ACCEL_HOST_DELTA_OFFSET', 0x0A: 'GYRO_HOST_DELTA_OFFSET', 0x0C: 'CONFIGURE_TAP',
    0x0D: 'CONFIGURE_PEDOMETER', 0x0E: 'CONFIGURE_MOTION', 0x0F: 'RESET_PEDOMETER',
    0x10: 'COPY_USID', 0x11: 'SET_RPU', 0x12: 'AHB_CLOCK_GATING',
    0xA2: 'ON_DEMAND_CALIBRATION', 0xAA: 'APPLY_GYRO_GAINS',
}

ACCEL_FS = {0: '±2g', 1: '±4g', 2: '±8g', 3: '±16g'}
GYRO_FS = {0: '±16dps', 1: '±32dps', 2: '±64dps', 3: '±128dps', 4: '±256dps',
           5: '±512dps', 6: '±1024dps', 7: '±2048dps'}
ACCEL_SENS = {0: 16384.0, 1: 8192.0, 2: 4096.0, 3: 2048.0}
ODR_6DOF = {0: '7174.4Hz', 1: '3587.2Hz', 2: '1793.6Hz', 3: '896.8Hz', 4: '448.4Hz',
            5: '224.2Hz', 6: '112.1Hz', 7: '56.05Hz', 8: '28.025Hz'}
ODR_ACCEL = {3: '1000Hz', 4: '500Hz', 5: '250Hz', 6: '125Hz', 7: '62.5Hz', 8: '31.25Hz',
             12: '128Hz (low power)', 13: '21Hz (low power)', 14: '11Hz (low power)',
             15: '3Hz (low power)'}
LPF_MODE = {0: '2.66%', 1: '3.63%', 2: '5.39%', 3: '13.37%'}
FIFO_MODE = {0: 'bypass', 1: 'FIFO', 2: 'stream'}
FIFO_SIZE = {0: '16', 1: '32', 2: '64', 3: '128'}
TAP_AXIS = {0: 'none', 1: 'X', 2: 'Y', 3: 'Z'}

G = 9.80665

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_SAMPLE    = 2
ANN_PTR_WRITE = 3
ANN_WARNING   = 4
# Conformance-check annotations (specs/testing_framework.md, "Conformance
# Implementation"); text is the literal check name specs/imu/qmi8658a_timing.conf
# matches against.
ANN_RESET_RECOVERY_START = 5
ANN_RESET_RECOVERY_DONE  = 6
ANN_GYRO_STARTUP_START   = 7
ANN_GYRO_STARTUP_DONE    = 8


def _bits(raw, names):
    """Names of the set bits, MSB first; names maps bit -> name."""
    return [names[b] for b in sorted(names, reverse=True) if raw & (1 << b)]


def _flags(raw, names):
    return '|'.join(_bits(raw, names)) or 'none'


def _s16(lo, hi):
    v = (hi << 8) | lo
    return v - 0x10000 if v & 0x8000 else v


class Decoder(srd.Decoder):
    api_version = 3
    id = 'qmi8658a'
    name = 'QMI8658A'
    longname = 'QST QMI8658A 6-axis IMU'
    desc = 'Decode QMI8658A I2C/SPI register transactions.'
    license = 'gplv2+'
    inputs = ['i2c', 'spi']
    outputs = ['qmi8658a']
    tags = ['IC', 'Sensor', 'IMU']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('sample',    'Sensor sample'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
        ('reset-recovery-start', 'reset_recovery_start: RESET written'),
        ('reset-recovery-done',  'reset_recovery_done: next transaction after reset'),
        ('gyro-startup-start',   'gyro_startup_start: CTRL7 gEN set'),
        ('gyro-startup-done',    'gyro_startup_done: first sensor-data read'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_SAMPLE, ANN_PTR_WRITE)),
        ('timing',   'Timing',   (ANN_RESET_RECOVERY_START, ANN_RESET_RECOVERY_DONE,
                                  ANN_GYRO_STARTUP_START, ANN_GYRO_STARTUP_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.databuf = []
        self.ss_block = None
        self.spi_cmd = None
        self.spi_mosi = []
        self.spi_miso = []
        self.spi_active = False
        # tracked chip state
        self.addr_ai = False
        self.accel_fs = 0
        self.gyro_fs = 4
        self.ctrl7 = 0
        self.reset_pending = False
        self.gyro_pending = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    # -- output helpers ---------------------------------------------------

    def _put(self, ss, es, cls, strings):
        self.put(ss, es, self.out_ann, [cls, strings])

    def _warn(self, ss, es, msg, tag='WARN'):
        self._put(ss, es, ANN_WARNING, [msg, tag])

    # -- register decoding ------------------------------------------------

    def _odr_name(self, code):
        if self.ctrl7 & 0x02:
            return ODR_6DOF.get(code, '?')
        return ODR_ACCEL.get(code, ODR_6DOF.get(code, '?'))

    def _decode_reg(self, reg, raw):
        if reg == 0x00:
            return '0x%02X %s' % (raw, 'OK' if raw == 0x05 else 'unexpected (expected 0x05)')
        if reg == 0x02:
            parts = ['4-wire SPI' if not raw & 0x80 else '3-wire SPI',
                     'ADDR_AI' if raw & 0x40 else 'ADDR_AI off',
                     'BE' if raw & 0x20 else 'little-endian']
            parts += _bits(raw, {4: 'INT2_EN', 3: 'INT1_EN', 2: 'FIFO_INT->INT1', 0: 'SensorDisable'})
            return ' '.join(parts)
        if reg in (0x03, 0x04):
            fs = (raw >> 4) & 7
            names = ACCEL_FS if reg == 0x03 else GYRO_FS
            st = ' self-test' if raw & 0x80 else ''
            return '%s %s%s' % (names.get(fs, '?'), self._odr_name(raw & 0x0F), st)
        if reg == 0x06:
            return 'gyro LPF %s %s, accel LPF %s %s' % (
                'on' if raw & 0x10 else 'off', LPF_MODE[(raw >> 5) & 3],
                'on' if raw & 0x01 else 'off', LPF_MODE[(raw >> 1) & 3])
        if reg == 0x08:
            return _flags(raw, {7: 'SyncSample', 5: 'DRDY_DIS', 4: 'gSN', 1: 'gEN', 0: 'aEN'})
        if reg == 0x09:
            return _flags(raw, {7: 'HANDSHAKE_STATUS', 6: 'ACTIVITY->INT1', 4: 'Pedo_EN',
                                3: 'SigMotion_EN', 2: 'NoMotion_EN', 1: 'AnyMotion_EN', 0: 'Tap_EN'})
        if reg == 0x0A:
            return 'CTRL_CMD_' + CTRL9_CMDS.get(raw, '0x%02X' % raw)
        if reg == 0x14:
            return '%s, %s samples%s' % (FIFO_MODE.get(raw & 3, '?'), FIFO_SIZE[(raw >> 2) & 3],
                                          ', RD_MODE' if raw & 0x80 else '')
        if reg == 0x16:
            cnt = (raw & 3) << 8
            return '%s count_msb=%d' % (_flags(raw, {7: 'FULL', 6: 'WTM', 5: 'OVFLOW', 4: 'NOT_EMPTY'}), cnt >> 8)
        if reg == 0x2D:
            return _flags(raw, {7: 'CmdDone', 1: 'Locked', 0: 'Avail'})
        if reg == 0x2E:
            return _flags(raw, {1: 'gDA', 0: 'aDA'})
        if reg == 0x2F:
            return _flags(raw, {7: 'SigMotion', 6: 'NoMotion', 5: 'AnyMotion', 4: 'Pedometer',
                                2: 'WoM', 1: 'Tap'})
        if reg == 0x46:
            return 'success' if raw == 0 else _flags(raw, {7: 'X_Limit_L_Fail', 6: 'X_Limit_H_Fail',
                                                           5: 'Y_Limit_L_Fail', 4: 'Y_Limit_H_Fail',
                                                           3: 'Accel_Check', 2: 'Startup_Failed',
                                                           1: 'Gyro_Enabled', 0: 'COD_Failed'})
        if reg == 0x4D:
            return '0x%02X%s' % (raw, ' reset OK' if raw == 0x80 else '')
        if reg == 0x59:
            return '%s tap, axis %s, %s' % ({0: 'no', 1: 'single', 2: 'double'}.get(raw & 3, '?'),
                                            TAP_AXIS[(raw >> 4) & 3], 'negative' if raw & 0x80 else 'positive')
        if reg == 0x60:
            return 'soft reset' if raw == 0xB0 else '0x%02X' % raw
        return '0x%02X' % raw

    def _track_write(self, reg, raw):
        if reg == 0x02:
            self.addr_ai = bool(raw & 0x40)
        elif reg == 0x03:
            self.accel_fs = (raw >> 4) & 7
        elif reg == 0x04:
            self.gyro_fs = (raw >> 4) & 7
        elif reg == 0x08:
            self.ctrl7 = raw

    def _sample_text(self, reg, data):
        """Computed values for a read that covers sensor-data registers."""
        end = reg + len(data)
        parts = []
        if reg <= 0x33 and end >= 0x35:
            t = _s16(data[0x33 - reg], data[0x34 - reg]) / 256.0
            parts.append('T=%.2f°C' % t)
        sens = ACCEL_SENS.get(self.accel_fs, 16384.0)
        if reg <= 0x35 and end >= 0x3B:
            o = 0x35 - reg
            a = [_s16(data[o + 2 * i], data[o + 2 * i + 1]) / sens * G for i in range(3)]
            parts.append('a=(%.3f, %.3f, %.3f) m/s²' % tuple(a))
        gs = float(2048 >> self.gyro_fs)
        if reg <= 0x3B and end >= 0x41:
            o = 0x3B - reg
            g = [_s16(data[o + 2 * i], data[o + 2 * i + 1]) / gs * math.pi / 180.0 for i in range(3)]
            parts.append('g=(%.4f, %.4f, %.4f) rad/s' % tuple(g))
        return parts

    # -- transaction level -------------------------------------------------

    def _mirror_python(self, ss, es, reg, is_read, data):
        super().put(ss, es, self.out_python,
                    ('REG_READ' if is_read else 'REG_WRITE', (reg, bytes(b & 0xFF for b in data))))

    def _transaction(self, ss, es, is_read, reg, data):
        name = REGS.get(reg, 'Reg[0x%02X]' % reg)

        # reset_recovery_done: the first transaction after the RESET write.
        if self.reset_pending:
            self.reset_pending = False
            self._put(ss, es, ANN_RESET_RECOVERY_DONE,
                      ['reset_recovery_done: next transaction after reset', 'reset_recovery_done', 'RR✓'])

        if is_read:
            if not data:
                return
            self._mirror_python(ss, es, reg, True, data)
            # gyro_startup_done: first read starting in the sensor-data window.
            if self.gyro_pending and 0x33 <= reg <= 0x40:
                self.gyro_pending = False
                self._put(ss, es, ANN_GYRO_STARTUP_DONE,
                          ['gyro_startup_done: first sensor-data read', 'gyro_startup_done', 'GS✓'])
            if len(data) > 1 and not self.addr_ai and reg != 0x17:
                self._warn(ss, es, 'Burst read of %d bytes at 0x%02X with ADDR_AI = 0 returns one register repeatedly' %
                           (len(data), reg), 'AI?')
            if 0x33 <= reg <= 0x40 and len(data) >= 2:
                parts = self._sample_text(reg, data)
                self._put(ss, es, ANN_SAMPLE,
                          ['Read %s..: %s' % (name, ', '.join(parts)), ', '.join(parts), 'S'])
                return
            if reg == 0x30 and len(data) >= 3:
                v = data[0] | (data[1] << 8) | (data[2] << 16)
                self._put(ss, es, ANN_REG_READ, ['Read TIMESTAMP: %d' % v, 'TS %d' % v, 'TS'])
                return
            if reg == 0x5A and len(data) >= 3:
                v = data[0] | (data[1] << 8) | (data[2] << 16)
                self._put(ss, es, ANN_REG_READ, ['Read STEP_CNT: %d steps' % v, 'STEPS %d' % v, 'ST'])
                return
            if reg == 0x00:
                self._put(ss, es, ANN_REG_READ,
                          ['Read WHO_AM_I: %s' % self._decode_reg(0x00, data[0]), 'R WHO_AM_I 0x%02X' % data[0], 'ID'])
                if data[0] != 0x05:
                    self._warn(ss, es, 'WHO_AM_I 0x%02X does not match 0x05' % data[0], 'ID?')
                return
            if len(data) == 1:
                self._put(ss, es, ANN_REG_READ,
                          ['Read %s: %s' % (name, self._decode_reg(reg, data[0])),
                           'R %s 0x%02X' % (name, data[0]), 'R'])
            else:
                self._put(ss, es, ANN_REG_READ,
                          ['Read %s: %d bytes %s' % (name, len(data), ' '.join('%02X' % b for b in data)),
                           'R %s [%d]' % (name, len(data)), 'R'])
            return

        # write
        if not data:
            self._put(ss, es, ANN_PTR_WRITE, ['Pointer → %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg, 'PTR'])
            return
        self._mirror_python(ss, es, reg, False, data)
        if reg in CONFIG_REGS and len(data) > 1:
            self._warn(ss, es, 'Write of %d bytes to configuration register %s: burst writes are not supported' %
                       (len(data), name), 'LEN?')
        raw = data[0]
        self._put(ss, es, ANN_REG_WRITE,
                  ['Write %s: %s' % (name, self._decode_reg(reg, raw)), 'W %s 0x%02X' % (name, raw), 'W'])
        if reg == 0x60 and raw == 0xB0:
            self.reset_pending = True
            self._put(ss, es, ANN_RESET_RECOVERY_START,
                      ['reset_recovery_start: RESET written (0xB0)', 'reset_recovery_start', 'RR▶'])
        if reg == 0x08 and (raw & 0x02) and not (self.ctrl7 & 0x02):
            self.gyro_pending = True
            self._put(ss, es, ANN_GYRO_STARTUP_START,
                      ['gyro_startup_start: CTRL7 gEN set', 'gyro_startup_start', 'GS▶'])
        elif reg == 0x08 and not (raw & 0x02):
            self.gyro_pending = False
        self._track_write(reg, raw)

    # -- I2C / SPI front ends ----------------------------------------------

    def _finish_i2c(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        if self.reg_ptr is None:
            return
        self._transaction(self.ss_block, self.es, self.is_read, self.reg_ptr, list(self.databuf))

    def _decode_i2c(self, ss, es, ptype, pdata):
        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass
            else:
                self._finish_i2c()
                self.databuf = []
                self.is_read = False
                self.reg_ptr = None if ptype == 'START' else self.reg_ptr
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
            self._finish_i2c()
            self.state = 'IDLE'
            self.databuf = []

    def _finish_spi(self, es):
        if not self.spi_active or self.spi_cmd is None:
            return
        cmd = self.spi_cmd
        is_read = bool(cmd & 0x80)
        reg = cmd & 0x7F
        data = self.spi_miso if is_read else self.spi_mosi
        self._transaction(self.ss_block, es, is_read, reg, list(data))
        self.spi_active = False
        self.spi_cmd = None

    def _decode_spi(self, ss, es, data):
        ptype = data[0]
        if ptype == 'CS-CHANGE':
            _old, new = data[1], data[2]
            if new == 0:                       # chip select asserted (active low)
                self.spi_active = True
                self.spi_cmd = None
                self.spi_mosi = []
                self.spi_miso = []
                self.ss_block = ss
            else:
                self._finish_spi(es)
        elif ptype == 'DATA' and self.spi_active:
            mosi, miso = data[1], data[2]
            if self.spi_cmd is None:
                self.spi_cmd = mosi
                self.es = es
            else:
                self.spi_mosi.append(mosi)
                self.spi_miso.append(miso)
                self.es = es

    def decode(self, ss, es, data):
        self.ss, self.es = ss, es
        if data[0] in ('CS-CHANGE', 'DATA') and len(data) == 3:
            self._decode_spi(ss, es, data)
        else:
            ptype, pdata = data
            self._decode_i2c(ss, es, ptype, pdata)
