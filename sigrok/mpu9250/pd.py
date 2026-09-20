import sigrokdecode as srd

MPU9250_ADDRS = {0x68, 0x69}
AK8963_ADDR = 0x0C

MPU9250_REGS = {
    0x19: 'SMPLRT_DIV',
    0x1A: 'CONFIG',
    0x1B: 'GYRO_CONFIG',
    0x1C: 'ACCEL_CONFIG',
    0x1D: 'ACCEL_CONFIG2',
    0x1E: 'LP_ACCEL_ODR',
    0x1F: 'WOM_THR',
    0x23: 'FIFO_EN',
    0x37: 'INT_PIN_CFG',
    0x38: 'INT_ENABLE',
    0x3A: 'INT_STATUS',
    0x3B: 'ACCEL_XOUT_H',
    0x41: 'TEMP_OUT_H',
    0x43: 'GYRO_XOUT_H',
    0x6A: 'USER_CTRL',
    0x6B: 'PWR_MGMT_1',
    0x6C: 'PWR_MGMT_2',
    0x72: 'FIFO_COUNTH',
    0x73: 'FIFO_COUNTL',
    0x74: 'FIFO_R_W',
    0x75: 'WHO_AM_I',
}

AK8963_REGS = {
    0x00: 'WIA',
    0x02: 'ST1',
    0x03: 'HXL',
    0x04: 'HXH',
    0x05: 'HYL',
    0x06: 'HYH',
    0x07: 'HZL',
    0x08: 'HZH',
    0x09: 'ST2',
    0x0A: 'CNTL1',
    0x0B: 'CNTL2',
    0x10: 'ASAX',
    0x11: 'ASAY',
    0x12: 'ASAZ',
}

ANN_WRITE   = 0
ANN_READ    = 1
ANN_WARNING = 2

GYRO_FS_SEL = {0: '±250 dps', 1: '±500 dps', 2: '±1000 dps', 3: '±2000 dps'}
ACCEL_FS_SEL = {0: '±2g', 1: '±4g', 2: '±8g', 3: '±16g'}
CONFIG_DLPF = {0: '260/256 Hz', 1: '184/188 Hz', 2: '94/98 Hz', 3: '41/42 Hz', 4: '20/20 Hz', 5: '10/10 Hz', 6: '5/5 Hz'}
AK8963_MODE = {0: 'power-down', 1: 'single', 2: '8 Hz', 6: '100 Hz', 0xF: 'fuse ROM'}

POWERON_START = 'poweron_start'
POWERON_READY = 'poweron_ready'


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mpu9250'
    name = 'MPU-9250'
    longname = 'MPU-9250 9-axis MotionTracking device'
    desc = 'Decode MPU-9250 I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mpu9250']
    tags = ['IC', 'IMU', 'Sensor']

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
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.databuf = []
        self.ss_block = None
        self.ak8963_active = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

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
            if pdata not in MPU9250_ADDRS and pdata != AK8963_ADDR:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.ak8963_active = (pdata == AK8963_ADDR)
            self.state = 'GET_DATA_READ' if self.is_read else 'GET_REG_PTR'

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

    def _finish_transaction(self):
        if self.state in ('GET_DATA_WRITE', 'GET_DATA_READ') and self.reg_ptr is not None:
            reg = self.reg_ptr
            name = AK8963_REGS.get(reg, MPU9250_REGS.get(reg, 'UNKNOWN'))
            if self.is_read:
                self._annotate_read(name, reg)
            else:
                self._annotate_write(name, reg)
        elif self.state == 'GET_REG_PTR':
            pass

    def _annotate_write(self, name, reg):
        if len(self.databuf) == 1:
            val = self.databuf[0]
            ann_text = self._format_write(name, reg, val)
            self.put(self.ss_block, self.es, self.out_ann, [ANN_WRITE, [ann_text, ann_text, self._short_write(name, val)]])
            self.put(self.ss_block, self.es, self.out_python, [('write', name, reg, val)])

            if reg == 0x6B and val & 0x80:
                self.put(self.ss_block, self.es, self.out_ann, [ANN_WRITE, [POWERON_START, 'PWR', '▶']])
                self.put(self.ss_block, self.es, self.out_python, [('timing', POWERON_START)])
            elif reg == 0x6B and (val & 0x80) == 0 and self.addr in MPU9250_ADDRS:
                self.put(self.ss_block, self.es, self.out_ann, [ANN_WRITE, [POWERON_READY, 'PWR', '●']])
                self.put(self.ss_block, self.es, self.out_python, [('timing', POWERON_READY)])
        else:
            self.put(self.ss_block, self.es, self.out_ann, [ANN_WARNING, [f'Unexpected write length {len(self.databuf)} for {name}', f'WR_LEN {len(self.databuf)}']])

    def _annotate_read(self, name, reg):
        n = len(self.databuf)
        if n >= 1:
            if name in ('ACCEL_XOUT_H', 'GYRO_XOUT_H') and n >= 6:
                ax = (self.databuf[0] << 8) | self.databuf[1]
                ay = (self.databuf[2] << 8) | self.databuf[3]
                az = (self.databuf[4] << 8) | self.databuf[5]
                if ax >= 0x8000: ax -= 0x10000
                if ay >= 0x8000: ay -= 0x10000
                if az >= 0x8000: az -= 0x10000
                if name == 'ACCEL_XOUT_H':
                    ann_text = f'{name}: {ax} {ay} {az} (raw)'
                    ann_short = f'A {ax} {ay} {az}'
                    self.put(self.ss_block, self.es, self.out_python, [('read', name, ax, ay, az)])
                else:
                    ann_text = f'{name}: {ax} {ay} {az} (raw)'
                    ann_short = f'G {ax} {ay} {az}'
                    self.put(self.ss_block, self.es, self.out_python, [('read', name, ax, ay, az)])
            elif name == 'TEMP_OUT_H' and n >= 2:
                raw = (self.databuf[0] << 8) | self.databuf[1]
                if raw >= 0x8000: raw -= 0x10000
                temp = raw / 333.87 + 21.0
                ann_text = f'{name}: {raw} -> {temp:.2f} °C'
                ann_short = f'T {temp:.1f}°C'
                self.put(self.ss_block, self.es, self.out_python, [('read', name, temp)])
            elif name in ('HXL',) and n >= 7:
                mx = ((self.databuf[1] << 8) | self.databuf[0])
                my = ((self.databuf[3] << 8) | self.databuf[2])
                mz = ((self.databuf[5] << 8) | self.databuf[4])
                if mx >= 0x8000: mx -= 0x10000
                if my >= 0x8000: my -= 0x10000
                if mz >= 0x8000: mz -= 0x10000
                ann_text = f'{name}-ST2: {mx} {my} {mz} (raw)'
                ann_short = f'M {mx} {my} {mz}'
                self.put(self.ss_block, self.es, self.out_python, [('read', name, mx, my, mz)])
            elif name == 'WHO_AM_I' and n >= 1:
                val = self.databuf[0]
                ann_text = f'{name}: 0x{val:02X}'
                ann_short = f'ID 0x{val:02X}'
                self.put(self.ss_block, self.es, self.out_python, [('read', name, val)])
            else:
                val = (self.databuf[0] << 8) | self.databuf[1] if n >= 2 else self.databuf[0]
                ann_text = f'{name}: 0x{val:02X}' if n == 1 else f'{name}: 0x{val:04X}'
                ann_short = f'0x{val:02X}' if n == 1 else f'0x{val:04X}'
                self.put(self.ss_block, self.es, self.out_python, [('read', name, val)])

            self.put(self.ss_block, self.es, self.out_ann, [ANN_READ, [ann_text, ann_text, ann_short]])
        else:
            self.put(self.ss_block, self.es, self.out_ann, [ANN_WARNING, [f'Empty read for {name}', 'RD_EMPTY']])

    def _format_write(self, name, reg, val):
        if name == 'GYRO_CONFIG':
            fs = (val >> 3) & 0x03
            return f'{name}: FS={GYRO_FS_SEL.get(fs, "?")}, FCHOICE_B={val & 0x03}'
        elif name == 'ACCEL_CONFIG':
            fs = (val >> 3) & 0x03
            return f'{name}: FS={ACCEL_FS_SEL.get(fs, "?")}'
        elif name == 'CONFIG':
            return f'{name}: DLPF_CFG={val & 0x07} ({CONFIG_DLPF.get(val & 0x07, "?")})'
        elif name == 'ACCEL_CONFIG2':
            return f'{name}: A_DLPFCFG={val & 0x07}'
        elif name == 'PWR_MGMT_1':
            bits = []
            if val & 0x80: bits.append('RESET')
            if val & 0x40: bits.append('SLEEP')
            if val & 0x20: bits.append('CYCLE')
            if val & 0x10: bits.append('GYRO_STBY')
            if val & 0x08: bits.append('PD_PTAT')
            clk = val & 0x07
            return f'{name}: {", ".join(bits) or "none"}, CLKSEL={clk}'
        elif name == 'INT_PIN_CFG':
            bits = []
            if val & 0x20: bits.append('BYPASS_EN')
            return f'{name}: {", ".join(bits) or "none"}'
        elif name == 'AK8963_CNTL1':
            bit = '16-bit' if val & 0x10 else '14-bit'
            mode = val & 0x0F
            return f'{name}: {bit}, MODE={AK8963_MODE.get(mode, mode)}'
        else:
            return f'{name}: 0x{val:02X}'

    def _short_write(self, name, val):
        if name == 'GYRO_CONFIG':
            fs = (val >> 3) & 0x03
            return f'G FS={GYRO_FS_SEL.get(fs, "?")[1:4]}'
        elif name == 'ACCEL_CONFIG':
            fs = (val >> 3) & 0x03
            return f'A FS={ACCEL_FS_SEL.get(fs, "?")}'
        elif name == 'CONFIG':
            return f'DLPF={val & 0x07}'
        elif name == 'PWR_MGMT_1':
            if val & 0x80: return 'RST'
            if val & 0x40: return 'SLP'
            return f'PWR 0x{val:02X}'
        elif name == 'AK8963_CNTL1':
            return f'AK {AK8963_MODE.get(val & 0x0F, val & 0x0F)}'
        else:
            return f'0x{val:02X}'