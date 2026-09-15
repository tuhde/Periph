import sigrokdecode as srd

ADDRS = {0x46, 0x47}

REGS = {
    0x01: 'chip_id',
    0x02: 'rev_id',
    0x11: 'chip_status',
    0x13: 'drive_config',
    0x14: 'int_config',
    0x15: 'int_source',
    0x16: 'fifo_config',
    0x17: 'fifo_count',
    0x18: 'fifo_sel',
    0x1D: 'temp_xlsb',
    0x1E: 'temp_lsb',
    0x1F: 'temp_msb',
    0x20: 'press_xlsb',
    0x21: 'press_lsb',
    0x22: 'press_msb',
    0x27: 'int_status',
    0x28: 'status',
    0x29: 'fifo_data',
    0x2B: 'nvm_addr',
    0x2C: 'nvm_data_lsb',
    0x2D: 'nvm_data_msb',
    0x30: 'dsp_config',
    0x31: 'dsp_iir',
    0x32: 'oor_thr_p_lsb',
    0x33: 'oor_thr_p_msb',
    0x34: 'oor_range',
    0x35: 'oor_config',
    0x36: 'osr_config',
    0x37: 'odr_config',
    0x38: 'osr_eff',
    0x7E: 'cmd',
}

OSR_NAMES = {
    0: '×1', 1: '×2', 2: '×4', 3: '×8',
    4: '×16', 5: '×32', 6: '×64', 7: '×128',
}

MODE_NAMES = {
    0b00: 'Standby', 0b01: 'Normal',
    0b10: 'Forced',  0b11: 'Continuous',
}

ODR_NAMES = {
    0x00: '240 Hz',  0x04: '160 Hz',  0x08: '120 Hz',  0x0D: '70 Hz',
    0x0E: '60 Hz',   0x0F: '50 Hz',   0x11: '40 Hz',   0x17: '10 Hz',
    0x1C: '1 Hz',    0x1D: '0.5 Hz',  0x1E: '0.25 Hz', 0x1F: '0.125 Hz',
}

IIR_NAMES = {
    0: 'bypass', 1: '1', 2: '3', 3: '7',
    4: '15', 5: '31', 6: '63', 7: '127',
}

FIFO_FRAME_NAMES = {
    0: 'disabled', 1: 'temperature', 2: 'pressure', 3: 'pressure+temperature',
}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_DATA_READ = 2
ANN_PTR_WRITE = 3
ANN_WARNING   = 4


def _s24(raw):
    return raw if raw < 0x800000 else raw - 0x1000000


def _decode_osr_config(raw):
    press_en = (raw >> 6) & 1
    osr_p = (raw >> 3) & 7
    osr_t = raw & 7
    return ('osr_config 0x%02X: press_en=%d osr_p=%s osr_t=%s'
            % (raw, press_en, OSR_NAMES[osr_p], OSR_NAMES[osr_t]))


def _decode_odr_config(raw):
    deep_dis = (raw >> 7) & 1
    odr = (raw >> 2) & 0x1F
    mode = raw & 3
    odr_str = ODR_NAMES.get(odr, '0x%02X' % odr)
    return ('odr_config 0x%02X: deep_dis=%d odr=%s mode=%s'
            % (raw, deep_dis, odr_str, MODE_NAMES.get(mode, '0x%02X' % mode)))


def _decode_dsp_config(raw):
    parts = []
    if (raw >> 7) & 1: parts.append('oor_iir_p=post')
    if (raw >> 6) & 1: parts.append('fifo_iir_p=post')
    if (raw >> 5) & 1: parts.append('shdw_iir_p=post')
    if (raw >> 4) & 1: parts.append('fifo_iir_t=post')
    if (raw >> 3) & 1: parts.append('shdw_iir_t=post')
    if (raw >> 2) & 1: parts.append('iir_flush_forced')
    comp = raw & 3
    if comp: parts.append('comp_pt=%d' % comp)
    detail = ', '.join(parts) if parts else 'default'
    return 'dsp_config 0x%02X: %s' % (raw, detail)


def _decode_dsp_iir(raw):
    p = (raw >> 3) & 7
    t = raw & 7
    return ('dsp_iir 0x%02X: iir_p=%s iir_t=%s'
            % (raw, IIR_NAMES[p], IIR_NAMES[t]))


def _decode_int_source(raw):
    parts = []
    if raw & 0x01: parts.append('drdy')
    if raw & 0x02: parts.append('fifo_full')
    if raw & 0x04: parts.append('fifo_ths')
    if raw & 0x08: parts.append('oor_p')
    detail = '+'.join(parts) if parts else 'none'
    return 'int_source 0x%02X: %s' % (raw, detail)


def _decode_int_status(raw):
    parts = []
    if raw & 0x10: parts.append('por')
    if raw & 0x08: parts.append('oor_p')
    if raw & 0x04: parts.append('fifo_ths')
    if raw & 0x02: parts.append('fifo_full')
    if raw & 0x01: parts.append('drdy')
    detail = '+'.join(parts) if parts else 'idle'
    return 'int_status 0x%02X: %s' % (raw, detail)


def _decode_status(raw):
    parts = []
    if raw & 0x80: parts.append('st_crack_pass')
    if raw & 0x10: parts.append('boot_err_corrected')
    if raw & 0x08: parts.append('nvm_cmd_err')
    if raw & 0x04: parts.append('nvm_err')
    if raw & 0x02: parts.append('nvm_rdy')
    if raw & 0x01: parts.append('core_rdy')
    detail = '+'.join(parts) if parts else 'idle'
    return 'status 0x%02X: %s' % (raw, detail)


def _decode_int_config(raw):
    pad_drv = (raw >> 5) & 7
    int_en = (raw >> 3) & 1
    int_od = (raw >> 2) & 1
    int_pol = (raw >> 1) & 1
    int_mode = raw & 1
    return ('int_config 0x%02X: pad_drv=%d en=%d od=%d pol=%d mode=%s'
            % (raw, pad_drv, int_en, int_od, int_pol,
               'latched' if int_mode else 'pulsed'))


def _decode_fifo_sel(raw):
    dec = (raw >> 2) & 7
    frame = raw & 3
    return 'fifo_sel 0x%02X: dec=%d frame=%s' % (raw, dec, FIFO_FRAME_NAMES.get(frame, '?'))


def _decode_fifo_config(raw):
    mode = (raw >> 5) & 1
    ths = raw & 0x1F
    return ('fifo_config 0x%02X: mode=%s threshold=%d'
            % (raw, 'stop_on_full' if mode else 'stream', ths))


def _decode_cmd(raw):
    if raw == 0xB6:
        return 'cmd ← 0xB6 (soft reset)'
    if raw == 0xA5:
        return 'cmd ← 0xA5 (NVM read trigger)'
    if raw == 0xA0:
        return 'cmd ← 0xA0 (NVM write trigger)'
    if raw == 0x5D:
        return 'cmd ← 0x5D (NVM seq)'
    return 'cmd ← 0x%02X' % raw


class Decoder(srd.Decoder):
    api_version = 3
    id = 'bmp581'
    name = 'BMP581'
    longname = 'BMP581 30-125 kPa MEMS pressure sensor'
    desc = 'Decode BMP581 I2C pressure/temperature sensor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['bmp581']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('data-read', 'Data read'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_DATA_READ, ANN_PTR_WRITE)),
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

    def _reg_name(self, reg):
        if reg in REGS:
            return REGS[reg]
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

        # Pressure data burst: 3 bytes from 0x20 (XLSB, LSB, MSB).
        if reg == 0x20 and len(buf) == 3:
            raw = (buf[2] << 16) | (buf[1] << 8) | buf[0]
            p_pa = _s24(raw) / 64.0
            self.put(ss, es, self.out_ann,
                     [ANN_DATA_READ,
                      ['Pressure: raw=0x%06X p=%.3f Pa' % (raw, p_pa),
                       'P=%.3f Pa' % p_pa]])
            return

        # Temperature data burst: 3 bytes from 0x1D.
        if reg == 0x1D and len(buf) == 3:
            raw = (buf[2] << 16) | (buf[1] << 8) | buf[0]
            t_c = _s24(raw) / 65536.0
            self.put(ss, es, self.out_ann,
                     [ANN_DATA_READ,
                      ['Temperature: raw=0x%06X t=%.4f °C' % (raw, t_c),
                       'T=%.4f °C' % t_c]])
            return

        # Combined burst: 6 bytes from 0x1D.
        if reg == 0x1D and len(buf) == 6:
            raw_t = (buf[2] << 16) | (buf[1] << 8) | buf[0]
            raw_p = (buf[5] << 16) | (buf[4] << 8) | buf[3]
            t_c = _s24(raw_t) / 65536.0
            p_pa = _s24(raw_p) / 64.0
            self.put(ss, es, self.out_ann,
                     [ANN_DATA_READ,
                      ['P+T: raw_p=0x%06X p=%.3f Pa, raw_t=0x%06X t=%.4f °C'
                       % (raw_p, p_pa, raw_t, t_c),
                       'P=%.3f Pa T=%.4f °C' % (p_pa, t_c)]])
            return

        if reg == 0x01 and len(buf) == 1:
            chip_id = buf[0]
            label = 'BMP581' if chip_id == 0x50 else 'unknown'
            if chip_id != 0x50:
                self._warn(ss, es, 'Unexpected chip ID: 0x%02X' % chip_id)
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['Chip ID: 0x%02X (%s)' % (chip_id, label),
                       'ID 0x%02X' % chip_id]])
            return

        if reg == 0x28 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      [_decode_status(buf[0]), 'status 0x%02X' % buf[0]]])
            return

        if reg == 0x27 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      [_decode_int_status(buf[0]), 'isr 0x%02X' % buf[0]]])
            return

        if reg == 0x38 and len(buf) == 1:
            odr_valid = (buf[0] >> 7) & 1
            osr_p = (buf[0] >> 3) & 7
            osr_t = buf[0] & 7
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['osr_eff 0x%02X: odr_valid=%d osr_p=%s osr_t=%s'
                       % (buf[0], odr_valid, OSR_NAMES[osr_p], OSR_NAMES[osr_t]),
                       'osr_eff 0x%02X' % buf[0]])
            return

        if reg == 0x17 and len(buf) == 1:
            n = buf[0] & 0x3F
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['fifo_count: %d frames' % n, 'fifo_count=%d' % n]])
            return

        # Generic single-byte read.
        if len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['Read %s: 0x%02X' % (self._reg_name(reg), buf[0]),
                       'R 0x%02X' % reg]])
            return

        hex_bytes = ' '.join('0x%02X' % b for b in buf)
        self.put(ss, es, self.out_ann,
                 [ANN_REG_READ,
                  ['Read %s: %s' % (self._reg_name(reg), hex_bytes),
                   'R 0x%02X' % reg]])

    def _finish_write(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.ss
        name = self._reg_name(reg)

        if not buf:
            self.put(ss, es, self.out_ann,
                     [ANN_PTR_WRITE,
                      ['Pointer → %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg]])
            return

        if reg == 0x36 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_osr_config(buf[0]),
                                       'OSR 0x%02X' % buf[0]]])
            return

        if reg == 0x37 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_odr_config(buf[0]),
                                       'ODR 0x%02X' % buf[0]]])
            return

        if reg == 0x30 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_dsp_config(buf[0]),
                                       'DSP 0x%02X' % buf[0]]])
            return

        if reg == 0x31 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_dsp_iir(buf[0]),
                                       'IIR 0x%02X' % buf[0]]])
            return

        if reg == 0x14 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_int_config(buf[0]),
                                       'ICFG 0x%02X' % buf[0]]])
            return

        if reg == 0x15 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_int_source(buf[0]),
                                       'ISRC 0x%02X' % buf[0]]])
            return

        if reg == 0x18 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_fifo_sel(buf[0]),
                                       'FSEL 0x%02X' % buf[0]]])
            return

        if reg == 0x16 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_fifo_config(buf[0]),
                                       'FCFG 0x%02X' % buf[0]]])
            return

        if reg == 0x7E and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_cmd(buf[0]), 'CMD 0x%02X' % buf[0]]])
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