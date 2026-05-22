import sigrokdecode as srd

ADDRS = {0x76, 0x77}

REGS = {
    0xD0: 'id',
    0xE0: 'reset',
    0xF3: 'status',
    0xF4: 'ctrl_meas',
    0xF5: 'config',
    0xF7: 'press_msb',
    0xF8: 'press_lsb',
    0xF9: 'press_xlsb',
    0xFA: 'temp_msb',
    0xFB: 'temp_lsb',
    0xFC: 'temp_xlsb',
}

# Calibration NVM: start address → (name, type)
CAL_REGS = {
    0x88: ('dig_T1', 'uint16'),
    0x8A: ('dig_T2', 'int16'),
    0x8C: ('dig_T3', 'int16'),
    0x8E: ('dig_P1', 'uint16'),
    0x90: ('dig_P2', 'int16'),
    0x92: ('dig_P3', 'int16'),
    0x94: ('dig_P4', 'int16'),
    0x96: ('dig_P5', 'int16'),
    0x98: ('dig_P6', 'int16'),
    0x9A: ('dig_P7', 'int16'),
    0x9C: ('dig_P8', 'int16'),
    0x9E: ('dig_P9', 'int16'),
}
CAL_ORDER = [
    0x88, 0x8A, 0x8C,
    0x8E, 0x90, 0x92, 0x94, 0x96, 0x98, 0x9A, 0x9C, 0x9E,
]

OSRS_NAMES = {
    0: 'skip', 1: '×1', 2: '×2', 3: '×4', 4: '×8',
    5: '×16', 6: '×16', 7: '×16',
}
MODE_NAMES = {0: 'Sleep', 1: 'Forced', 2: 'Forced', 3: 'Normal'}
T_SB_NAMES = {
    0: '0.5 ms', 1: '62.5 ms', 2: '125 ms', 3: '250 ms',
    4: '500 ms', 5: '1000 ms', 6: '2000 ms', 7: '4000 ms',
}
FILTER_NAMES = {
    0: 'off', 1: '×2', 2: '×4', 3: '×8',
    4: '×16', 5: '×16', 6: '×16', 7: '×16',
}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_CAL_READ  = 2
ANN_DATA_READ = 3
ANN_PTR_WRITE = 4
ANN_WARNING   = 5


def _s16(raw):
    return raw if raw < 0x8000 else raw - 0x10000


def _decode_ctrl_meas(raw):
    osrs_t = (raw >> 5) & 7
    osrs_p = (raw >> 2) & 7
    mode   = raw & 3
    return ('ctrl_meas 0x%02X: osrs_t=%s osrs_p=%s mode=%s'
            % (raw, OSRS_NAMES[osrs_t], OSRS_NAMES[osrs_p], MODE_NAMES[mode]))


def _decode_config(raw):
    t_sb     = (raw >> 5) & 7
    filt     = (raw >> 2) & 7
    spi3w    = raw & 1
    return ('config 0x%02X: t_sb=%s filter=%s spi3w_en=%d'
            % (raw, T_SB_NAMES[t_sb], FILTER_NAMES[filt], spi3w))


def _decode_status(raw):
    measuring = (raw >> 3) & 1
    im_update = raw & 1
    parts = []
    if measuring:
        parts.append('measuring')
    if im_update:
        parts.append('im_update')
    detail = ', '.join(parts) if parts else 'idle'
    return 'status 0x%02X: %s' % (raw, detail)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'bmp280'
    name = 'BMP280'
    longname = 'BMP280 barometric pressure sensor'
    desc = 'Decode BMP280 I2C pressure/temperature sensor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['bmp280']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('cal-read',  'Calibration read'),
        ('data-read', 'ADC data read'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_CAL_READ,
                                  ANN_DATA_READ, ANN_PTR_WRITE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None       # accepted chip address (0x76 or 0x77)
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.ss       = None
        self.es       = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _reg_name(self, reg):
        if reg in REGS:
            return REGS[reg]
        if 0x88 <= reg <= 0x9F:
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

        # Calibration NVM burst read: 0x88–0x9F, at least 2 bytes
        if 0x88 <= reg <= 0x9F and len(buf) >= 2:
            coeff_strs = []
            # Find starting index in CAL_ORDER
            base_reg = reg & 0xFE
            if base_reg in CAL_ORDER:
                cal_idx = CAL_ORDER.index(base_reg)
            else:
                cal_idx = -1
            for i in range(0, len(buf) - 1, 2):
                if cal_idx < 0 or cal_idx >= len(CAL_ORDER):
                    break
                base = CAL_ORDER[cal_idx]
                name, typ = CAL_REGS[base]
                raw = (buf[i + 1] << 8) | buf[i]   # little-endian
                val = _s16(raw) if typ == 'int16' else raw
                coeff_strs.append('%s=%d' % (name, val))
                cal_idx += 1
            self.put(ss, es, self.out_ann,
                     [ANN_CAL_READ,
                      ['Calibration: %s' % ', '.join(coeff_strs),
                       'CAL']])
            return

        # ADC data burst: 6 bytes from 0xF7
        if reg == 0xF7 and len(buf) >= 6:
            adc_p = (buf[0] << 12) | (buf[1] << 4) | (buf[2] >> 4)
            adc_t = (buf[3] << 12) | (buf[4] << 4) | (buf[5] >> 4)
            self.put(ss, es, self.out_ann,
                     [ANN_DATA_READ,
                      ['ADC: adc_P=%d adc_T=%d (raw)' % (adc_p, adc_t),
                       'P=%d T=%d' % (adc_p, adc_t)]])
            return

        if reg == 0xD0 and len(buf) == 1:
            chip_id = buf[0]
            if chip_id == 0x58:
                label = 'BMP280'
            elif chip_id == 0x60:
                label = 'BME280'
            elif chip_id == 0x50:
                label = 'BMP388'
            else:
                label = 'unknown'
                self._warn(ss, es, 'Unexpected chip ID: 0x%02X' % chip_id)
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['Chip ID: 0x%02X (%s)' % (chip_id, label),
                       'ID 0x%02X' % chip_id]])
            return

        if reg == 0xF3 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      [_decode_status(buf[0]), 'status 0x%02X' % buf[0]]])
            return

        # Generic read
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
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_ctrl_meas(buf[0]), 'ctrl 0x%02X' % buf[0]]])
            return

        if reg == 0xF5 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_config(buf[0]), 'cfg 0x%02X' % buf[0]]])
            return

        if reg == 0xE0 and len(buf) == 1:
            if buf[0] == 0xB6:
                note = ' (soft reset)'
            else:
                note = ' (unexpected 0x%02X, expected 0xB6)' % buf[0]
                self._warn(ss, es, 'Unexpected reset value: 0x%02X' % buf[0])
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['reset ← 0x%02X%s' % (buf[0], note), 'RESET']])
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
                # Pointer set followed by repeated-start read: preserve reg_ptr
                pass
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
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
