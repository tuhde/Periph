import sigrokdecode as srd

ADDRS = {0x76, 0x77}

REGS = {
    0x00: 'res_heat_val',
    0x02: 'res_heat_range',
    0x04: 'range_sw_err',
    0x1D: 'meas_status_0',
    0x1F: 'press_msb',
    0x20: 'press_lsb',
    0x21: 'press_xlsb',
    0x22: 'temp_msb',
    0x23: 'temp_lsb',
    0x24: 'temp_xlsb',
    0x25: 'hum_msb',
    0x26: 'hum_lsb',
    0x2A: 'gas_r_msb',
    0x2B: 'gas_r_lsb',
    0x70: 'ctrl_gas_0',
    0x71: 'ctrl_gas_1',
    0x72: 'ctrl_hum',
    0x74: 'ctrl_meas',
    0x75: 'config',
    0xD0: 'id',
    0xE0: 'reset',
}

OSRS_NAMES = {
    0: 'skip', 1: 'x1', 2: 'x2', 3: 'x4', 4: 'x8',
    5: 'x16', 6: 'x16', 7: 'x16',
}
MODE_NAMES = {0: 'Sleep', 1: 'Forced', 2: 'Forced', 3: 'Sleep'}
FILTER_NAMES = {
    0: 'off', 1: '1', 2: '3', 3: '7',
    4: '15', 5: '31', 6: '63', 7: '127',
}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_CAL_READ  = 2
ANN_DATA_READ = 3
ANN_PTR_WRITE = 4
ANN_WARNING   = 5


def _s8(raw):
    return raw if raw < 0x80 else raw - 0x100


def _s16(raw):
    return raw if raw < 0x8000 else raw - 0x10000


def _decode_ctrl_meas(raw):
    osrs_t = (raw >> 5) & 7
    osrs_p = (raw >> 2) & 7
    mode   = raw & 3
    return ('ctrl_meas 0x%02X: osrs_t=%s osrs_p=%s mode=%s'
            % (raw, OSRS_NAMES[osrs_t], OSRS_NAMES[osrs_p], MODE_NAMES[mode]))


def _decode_ctrl_hum(raw):
    osrs_h = raw & 7
    return 'ctrl_hum 0x%02X: osrs_h=%s' % (raw, OSRS_NAMES[osrs_h])


def _decode_config(raw):
    filt = (raw >> 2) & 7
    spi3w = raw & 1
    return 'config 0x%02X: filter=%s spi3w_en=%d' % (raw, FILTER_NAMES[filt], spi3w)


def _decode_ctrl_gas_1(raw):
    run_gas = (raw >> 4) & 1
    nb_conv = raw & 0x0F
    return 'ctrl_gas_1 0x%02X: run_gas=%d nb_conv=%d' % (raw, run_gas, nb_conv)


def _decode_ctrl_gas_0(raw):
    heat_off = (raw >> 3) & 1
    return 'ctrl_gas_0 0x%02X: heat_off=%d' % (raw, heat_off)


def _decode_meas_status(raw):
    new_data = (raw >> 7) & 1
    gas_meas = (raw >> 6) & 1
    measuring = (raw >> 5) & 1
    gas_idx = raw & 0x0F
    parts = []
    if new_data:
        parts.append('new_data')
    if gas_meas:
        parts.append('gas_measuring')
    if measuring:
        parts.append('measuring')
    parts.append('idx=%d' % gas_idx)
    detail = ', '.join(parts)
    return 'meas_status 0x%02X: %s' % (raw, detail)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'bme680'
    name = 'BME680'
    longname = 'BME680 4-in-1 environmental sensor'
    desc = 'Decode BME680 I2C temperature/pressure/humidity/gas sensor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['bme680']
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
        self.addr     = None
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
        if 0x50 <= reg <= 0x59:
            return 'idac_heat_%d' % (reg - 0x50)
        if 0x5A <= reg <= 0x63:
            return 'res_heat_%d' % (reg - 0x5A)
        if 0x64 <= reg <= 0x6D:
            return 'gas_wait_%d' % (reg - 0x64)
        if 0x8A <= reg <= 0xA0:
            return 'cal[0x%02X]' % reg
        if 0xE1 <= reg <= 0xEE:
            return 'cal[0x%02X]' % reg
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

        if reg == 0x8A and len(buf) >= 2:
            parts = []
            if len(buf) >= 3:
                par_t2 = _s16(buf[0] | (buf[1] << 8))
                parts.append('par_T2=%d' % par_t2)
            if len(buf) >= 3:
                par_t3 = _s8(buf[2])
                parts.append('par_T3=%d' % par_t3)
            if len(buf) >= 6:
                par_p1 = buf[4] | (buf[5] << 8)
                parts.append('par_P1=%d' % par_p1)
            if len(buf) >= 8:
                par_p2 = _s16(buf[6] | (buf[7] << 8))
                parts.append('par_P2=%d' % par_p2)
            self.put(ss, es, self.out_ann,
                     [ANN_CAL_READ,
                      ['Cal block1: %s' % ', '.join(parts),
                       'CAL1']])
            return

        if reg == 0xE1 and len(buf) >= 2:
            parts = []
            if len(buf) >= 2:
                par_h2 = (buf[0] << 4) | (buf[1] >> 4)
                par_h1 = (buf[2] << 4) | (buf[1] & 0x0F) if len(buf) >= 3 else 0
                parts.append('par_H2=%d' % par_h2)
                if len(buf) >= 3:
                    parts.append('par_H1=%d' % par_h1)
            if len(buf) >= 10:
                par_t1 = buf[8] | (buf[9] << 8)
                parts.append('par_T1=%d' % par_t1)
            self.put(ss, es, self.out_ann,
                     [ANN_CAL_READ,
                      ['Cal block2: %s' % ', '.join(parts),
                       'CAL2']])
            return

        if reg == 0x1F and len(buf) >= 13:
            press_adc = (buf[0] << 12) | (buf[1] << 4) | (buf[2] >> 4)
            temp_adc  = (buf[3] << 12) | (buf[4] << 4) | (buf[5] >> 4)
            hum_adc   = (buf[6] << 8) | buf[7]
            gas_adc   = (buf[11] << 2) | (buf[12] >> 6)
            gas_range = buf[12] & 0x0F
            gas_valid = (buf[12] >> 5) & 1
            heat_stab = (buf[12] >> 4) & 1
            self.put(ss, es, self.out_ann,
                     [ANN_DATA_READ,
                      ['ADC: P=%d T=%d H=%d G=%d range=%d valid=%d stab=%d'
                       % (press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab),
                        'P=%d T=%d H=%d G=%d' % (press_adc, temp_adc, hum_adc, gas_adc)]])
            return

        if reg == 0xD0 and len(buf) == 1:
            chip_id = buf[0]
            if chip_id == 0x61:
                label = 'BME680'
            else:
                label = 'unknown'
                self._warn(ss, es, 'Unexpected chip ID: 0x%02X' % chip_id)
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['Chip ID: 0x%02X (%s)' % (chip_id, label),
                        'ID 0x%02X' % chip_id]])
            return

        if reg == 0x1D and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      [_decode_meas_status(buf[0]), 'status 0x%02X' % buf[0]]])
            return

        if reg == 0x00 and len(buf) == 1:
            val = _s8(buf[0])
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['res_heat_val=%d' % val, 'rhv=%d' % val]])
            return

        if reg == 0x02 and len(buf) == 1:
            rhr = (buf[0] >> 4) & 0x03
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['res_heat_range=%d' % rhr, 'rhr=%d' % rhr]])
            return

        if reg == 0x04 and len(buf) == 1:
            rse = (buf[0] >> 4) & 0x0F
            rse_s = rse if rse < 8 else rse - 16
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['range_switching_error=%d' % rse_s, 'rse=%d' % rse_s]])
            return

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
                      ['Pointer -> %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg]])
            return

        if reg == 0x74 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_ctrl_meas(buf[0]), 'ctrl 0x%02X' % buf[0]]])
            return

        if reg == 0x72 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_ctrl_hum(buf[0]), 'hum 0x%02X' % buf[0]]])
            return

        if reg == 0x75 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_config(buf[0]), 'cfg 0x%02X' % buf[0]]])
            return

        if reg == 0x71 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_ctrl_gas_1(buf[0]), 'gas1 0x%02X' % buf[0]]])
            return

        if reg == 0x70 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_ctrl_gas_0(buf[0]), 'gas0 0x%02X' % buf[0]]])
            return

        if reg == 0xE0 and len(buf) == 1:
            if buf[0] == 0xB6:
                note = ' (soft reset)'
            else:
                note = ' (unexpected 0x%02X, expected 0xB6)' % buf[0]
                self._warn(ss, es, 'Unexpected reset value: 0x%02X' % buf[0])
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['reset <- 0x%02X%s' % (buf[0], note), 'RESET']])
            return

        if 0x5A <= reg <= 0x63 and len(buf) == 1:
            idx = reg - 0x5A
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['res_heat_%d <- 0x%02X' % (idx, buf[0]),
                       'rh_%d 0x%02X' % (idx, buf[0])]])
            return

        if 0x64 <= reg <= 0x6D and len(buf) == 1:
            idx = reg - 0x64
            mult = (buf[0] >> 6) & 3
            timer = buf[0] & 0x3F
            mult_names = ['x1', 'x4', 'x16', 'x64']
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['gas_wait_%d <- 0x%02X (%s, %d ms)' % (idx, buf[0], mult_names[mult], timer),
                       'gw_%d 0x%02X' % (idx, buf[0])]])
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
