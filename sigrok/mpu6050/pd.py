import sigrokdecode as srd

ADDRS = {0x68, 0x69}

REGS = {
    0x19: 'SMPLRT_DIV',
    0x1A: 'CONFIG',
    0x1B: 'GYRO_CONFIG',
    0x1C: 'ACCEL_CONFIG',
    0x23: 'FIFO_EN',
    0x37: 'INT_PIN_CFG',
    0x38: 'INT_ENABLE',
    0x3A: 'INT_STATUS',
    0x3B: 'ACCEL_XOUT_H',
    0x3C: 'ACCEL_XOUT_L',
    0x3D: 'ACCEL_YOUT_H',
    0x3E: 'ACCEL_YOUT_L',
    0x3F: 'ACCEL_ZOUT_H',
    0x40: 'ACCEL_ZOUT_L',
    0x41: 'TEMP_OUT_H',
    0x42: 'TEMP_OUT_L',
    0x43: 'GYRO_XOUT_H',
    0x44: 'GYRO_XOUT_L',
    0x45: 'GYRO_YOUT_H',
    0x46: 'GYRO_YOUT_L',
    0x47: 'GYRO_ZOUT_H',
    0x48: 'GYRO_ZOUT_L',
    0x6A: 'USER_CTRL',
    0x6B: 'PWR_MGMT_1',
    0x6C: 'PWR_MGMT_2',
    0x72: 'FIFO_COUNTH',
    0x73: 'FIFO_COUNTL',
    0x74: 'FIFO_R_W',
    0x75: 'WHO_AM_I',
}

GYRO_FS = {0: '±250dps', 1: '±500dps', 2: '±1000dps', 3: '±2000dps'}
ACCEL_FS = {0: '±2g', 1: '±4g', 2: '±8g', 3: '±16g'}
DLPF = {0: '260/256Hz', 1: '184/188Hz', 2: '94/98Hz', 3: '44/42Hz',
        4: '21/20Hz', 5: '10/10Hz', 6: '5/5Hz'}
CLKSEL = {0: 'int 8MHz', 1: 'PLL gyroX', 2: 'PLL gyroY', 3: 'PLL gyroZ',
          4: 'PLL ext 32.768kHz', 5: 'PLL ext 19.2MHz', 7: 'stop clock'}

GYRO_SENS = {0: 131.0, 1: 65.5, 2: 32.8, 3: 16.4}
ACCEL_SENS = {0: 16384.0, 1: 8192.0, 2: 4096.0, 3: 2048.0}

ANN_WRITE   = 0
ANN_READ    = 1
ANN_WARNING = 2


def _signed16(hi, lo):
    v = (hi << 8) | lo
    if v >= 0x8000:
        v -= 0x10000
    return v


def _decode_gyro_config(raw):
    fs = (raw >> 3) & 0x03
    st = 'ST=' + ('XYZ' if (raw >> 5) else 'off')
    return 'GYRO_CONFIG 0x%02X: FS=%s %s' % (raw, GYRO_FS.get(fs, '?'), st)


def _decode_accel_config(raw):
    fs = (raw >> 3) & 0x03
    st = 'ST=' + ('XYZ' if (raw >> 5) else 'off')
    return 'ACCEL_CONFIG 0x%02X: FS=%s %s' % (raw, ACCEL_FS.get(fs, '?'), st)


def _decode_config(raw):
    dlpf = raw & 0x07
    ext_sync = (raw >> 3) & 0x07
    return 'CONFIG 0x%02X: DLPF=%s EXT_SYNC=%d' % (raw, DLPF.get(dlpf, '?'), ext_sync)


def _decode_pwr_mgmt_1(raw):
    reset = 'RESET' if (raw & 0x80) else ''
    sleep = 'SLEEP' if (raw & 0x40) else 'awake'
    cycle = 'CYCLE' if (raw & 0x20) else ''
    temp_dis = 'TEMP_DIS' if (raw & 0x08) else ''
    clksel = raw & 0x07
    parts = [p for p in [sleep, reset, cycle, temp_dis] if p]
    return 'PWR_MGMT_1 0x%02X: %s CLKSEL=%s' % (raw, ' '.join(parts), CLKSEL.get(clksel, '?'))


def _decode_pwr_mgmt_2(raw):
    axes = []
    if raw & 0x20: axes.append('XA')
    if raw & 0x10: axes.append('YA')
    if raw & 0x08: axes.append('ZA')
    if raw & 0x04: axes.append('XG')
    if raw & 0x02: axes.append('YG')
    if raw & 0x01: axes.append('ZG')
    wake = (raw >> 6) & 0x03
    standby = ','.join(axes) if axes else 'none'
    return 'PWR_MGMT_2 0x%02X: STBY=%s LP_WAKE=%d' % (raw, standby, wake)


def _decode_fifo_en(raw):
    sources = []
    if raw & 0x80: sources.append('TEMP')
    if raw & 0x40: sources.append('XG')
    if raw & 0x20: sources.append('YG')
    if raw & 0x10: sources.append('ZG')
    if raw & 0x08: sources.append('ACCEL')
    if raw & 0x04: sources.append('SLV2')
    if raw & 0x02: sources.append('SLV1')
    if raw & 0x01: sources.append('SLV0')
    return 'FIFO_EN 0x%02X: %s' % (raw, ','.join(sources) if sources else 'none')


def _decode_user_ctrl(raw):
    parts = []
    if raw & 0x40: parts.append('FIFO_EN')
    if raw & 0x20: parts.append('I2C_MST_EN')
    if raw & 0x10: parts.append('I2C_IF_DIS')
    if raw & 0x04: parts.append('FIFO_RST')
    if raw & 0x02: parts.append('I2C_MST_RST')
    if raw & 0x01: parts.append('SIG_COND_RST')
    return 'USER_CTRL 0x%02X: %s' % (raw, ','.join(parts) if parts else 'none')


def _decode_int_status(raw):
    flags = []
    if raw & 0x01: flags.append('DATA_RDY')
    if raw & 0x02: flags.append('I2C_MST_INT')
    if raw & 0x10: flags.append('FIFO_OFLOW')
    return 'INT_STATUS 0x%02X: %s' % (raw, ','.join(flags) if flags else 'none')


def _decode_burst_sensor(data):
    if len(data) < 14:
        return 'Sensor burst: %d bytes (expected 14)' % len(data)
    ax = _signed16(data[0], data[1])
    ay = _signed16(data[2], data[3])
    az = _signed16(data[4], data[5])
    temp_raw = _signed16(data[6], data[7])
    gx = _signed16(data[8], data[9])
    gy = _signed16(data[10], data[11])
    gz = _signed16(data[12], data[13])
    temp_c = temp_raw / 340.0 + 36.53
    return ('Sensor: accel=(%+d,%+d,%+d) temp=%.1f°C gyro=(%+d,%+d,%+d)' %
            (ax, ay, az, temp_c, gx, gy, gz))


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mpu6050'
    name = 'MPU6050'
    longname = 'MPU6050 6-axis MotionTracking device'
    desc = 'Decode MPU6050 I2C accelerometer/gyroscope register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mpu6050']
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

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg  = self.reg_ptr
        name = REGS.get(reg, 'Reg[0x%02X]' % reg) if reg is not None else '?'

        if self.is_read:
            if reg == 0x75 and len(self.databuf) == 1:
                val = self.databuf[0]
                ok = ' (MPU6050 ✓)' if val == 0x68 else ' (expected 0x68!)'
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['WHO_AM_I 0x%02X%s' % (val, ok),
                           'ID 0x%02X' % val]])
            elif reg == 0x3B and len(self.databuf) >= 14:
                desc = _decode_burst_sensor(self.databuf)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          [desc,
                           'Burst %dB' % len(self.databuf)]])
            elif reg == 0x3A and len(self.databuf) == 1:
                desc = _decode_int_status(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'INT 0x%02X' % self.databuf[0]]])
            elif len(self.databuf) == 1:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['Read %s: 0x%02X' % (name, self.databuf[0]),
                           'R %s 0x%02X' % (name, self.databuf[0])]])
            elif len(self.databuf) == 2:
                raw = (self.databuf[0] << 8) | self.databuf[1]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['Read %s: 0x%04X' % (name, raw),
                           'R %s 0x%04X' % (name, raw)]])
            elif self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['Read %s: %d bytes' % (name, len(self.databuf)),
                           'R %s %dB' % (name, len(self.databuf))]])
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          ['Pointer → %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg]])
            elif len(self.databuf) == 1:
                val = self.databuf[0]
                if reg == 0x1B:
                    desc = _decode_gyro_config(val)
                elif reg == 0x1C:
                    desc = _decode_accel_config(val)
                elif reg == 0x1A:
                    desc = _decode_config(val)
                elif reg == 0x6B:
                    desc = _decode_pwr_mgmt_1(val)
                elif reg == 0x6C:
                    desc = _decode_pwr_mgmt_2(val)
                elif reg == 0x23:
                    desc = _decode_fifo_en(val)
                elif reg == 0x6A:
                    desc = _decode_user_ctrl(val)
                elif reg == 0x19:
                    desc = 'SMPLRT_DIV 0x%02X: rate=1kHz/%d' % (val, val + 1)
                else:
                    desc = 'Write %s: 0x%02X' % (name, val)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          [desc,
                           'W %s 0x%02X' % (name, val)]])
            elif len(self.databuf) == 2:
                raw = (self.databuf[0] << 8) | self.databuf[1]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          ['Write %s: 0x%04X' % (name, raw),
                           'W %s 0x%04X' % (name, raw)]])
            else:
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
