import sigrokdecode as srd

ADDR = 0x77

REGS = {
    0xD0: 'id',
    0xE0: 'soft_reset',
    0xF4: 'ctrl_meas',
    0xF6: 'out_msb',
    0xF7: 'out_lsb',
    0xF8: 'out_xlsb',
}

CAL_REGS = {
    0xAA: ('AC1', 'int16'),
    0xAC: ('AC2', 'int16'),
    0xAE: ('AC3', 'int16'),
    0xB0: ('AC4', 'uint16'),
    0xB2: ('AC5', 'uint16'),
    0xB4: ('AC6', 'uint16'),
    0xB6: ('B1',  'int16'),
    0xB8: ('B2',  'int16'),
    0xBA: ('MB',  'int16'),
    0xBC: ('MC',  'int16'),
    0xBE: ('MD',  'int16'),
}
CAL_ORDER = [0xAA, 0xAC, 0xAE, 0xB0, 0xB2, 0xB4, 0xB6, 0xB8, 0xBA, 0xBC, 0xBE]

OSS_NAMES = {0: 'ULP (4.5 ms)', 1: 'Standard (7.5 ms)',
             2: 'High-Res (13.5 ms)', 3: 'Ultra High-Res (25.5 ms)'}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_CAL_READ  = 2
ANN_PTR_WRITE = 3
ANN_WARNING   = 4


def _s16(raw):
    return raw if raw < 0x8000 else raw - 0x10000


def _decode_ctrl_meas(raw):
    oss  = (raw >> 6) & 3
    sco  = (raw >> 5) & 1
    meas = raw & 0x1F
    if meas == 0x0E:
        meas_str = 'Temperature'
    elif meas == 0x14:
        meas_str = 'Pressure (oss=%d, %s)' % (oss, OSS_NAMES.get(oss, '?'))
    else:
        meas_str = 'meas=0x%02X' % meas
    sco_str = ' SCO=1(converting)' if sco else ''
    return 'ctrl_meas 0x%02X: %s%s' % (raw, meas_str, sco_str)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'bmp180'
    name = 'BMP180'
    longname = 'BMP180 barometric pressure sensor'
    desc = 'Decode BMP180 I2C pressure/temperature sensor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['bmp180']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('cal-read',  'Calibration read'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_CAL_READ, ANN_PTR_WRITE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.last_oss = 0   # track last pressure oss for ADC result annotation

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _reg_name(self, reg):
        if reg in REGS:
            return REGS[reg]
        if 0xAA <= reg <= 0xBF:
            base = reg & 0xFE
            name, _ = CAL_REGS.get(base, ('cal[0x%02X]' % reg, 'uint16'))
            return name
        return 'reg[0x%02X]' % reg

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

    def _finish_read(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es

        # Calibration burst read
        if 0xAA <= reg <= 0xBF and len(buf) >= 2:
            coeff_strs = []
            idx = 0
            cal_idx = CAL_ORDER.index(reg & 0xFE) if (reg & 0xFE) in CAL_ORDER else -1
            for i in range(0, len(buf) - 1, 2):
                if cal_idx < 0 or cal_idx >= len(CAL_ORDER):
                    break
                base = CAL_ORDER[cal_idx]
                name, typ = CAL_REGS[base]
                raw = (buf[i] << 8) | buf[i + 1]
                val = _s16(raw) if typ == 'int16' else raw
                coeff_strs.append('%s=%d' % (name, val))
                cal_idx += 1
            self.put(ss, es, self.out_ann,
                     [ANN_CAL_READ,
                      ['Calibration: %s' % ', '.join(coeff_strs),
                       'CAL']])
            return

        if reg == 0xD0:
            if len(buf) == 1:
                ok = ' ✓' if buf[0] == 0x55 else ' (expected 0x55!)'
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ,
                          ['Chip ID: 0x%02X%s' % (buf[0], ok), 'ID 0x%02X' % buf[0]]])
            return

        if reg == 0xF6 and len(buf) >= 2:
            if len(buf) == 3:
                up = ((buf[0] << 16) | (buf[1] << 8) | buf[2]) >> (8 - self.last_oss)
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ,
                          ['ADC pressure (oss=%d): UP=%d (raw)' % (self.last_oss, up),
                           'UP=%d' % up]])
            else:
                ut = (buf[0] << 8) | buf[1]
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ,
                          ['ADC temperature: UT=%d (raw)' % ut, 'UT=%d' % ut]])
            return

        # Generic
        hex_bytes = ' '.join('0x%02X' % b for b in buf)
        self.put(ss, es, self.out_ann,
                 [ANN_REG_READ,
                  ['Read %s: %s' % (self._reg_name(reg), hex_bytes),
                   'R 0x%02X' % reg]])

    def _finish_write(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        name = self._reg_name(reg)

        if not buf:
            self.put(ss, es, self.out_ann,
                     [ANN_PTR_WRITE,
                      ['Pointer → %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg]])
            return

        if reg == 0xF4 and len(buf) == 1:
            raw = buf[0]
            oss = (raw >> 6) & 3
            meas = raw & 0x1F
            if meas == 0x14:
                self.last_oss = oss
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_ctrl_meas(raw), 'ctrl 0x%02X' % raw]])
            return

        if reg == 0xE0 and len(buf) == 1:
            ok = ' (soft reset)' if buf[0] == 0xB6 else ' (unexpected 0x%02X)' % buf[0]
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['soft_reset ← 0x%02X%s' % (buf[0], ok), 'RESET']])
            return

        hex_bytes = ' '.join('0x%02X' % b for b in buf)
        self.put(ss, es, self.out_ann,
                 [ANN_REG_WRITE,
                  ['Write %s: %s' % (name, hex_bytes), 'W 0x%02X' % reg]])

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
            if pdata != ADDR:
                self.state = 'IDLE'
                return
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
