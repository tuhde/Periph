import sigrokdecode as srd

ADDRS = {0x76, 0x77}

REGS = {
    0x00: 'chip_id',
    0x02: 'err',
    0x03: 'status',
    0x04: 'press_xlsb',
    0x05: 'press_lsb',
    0x06: 'press_msb',
    0x07: 'temp_xlsb',
    0x08: 'temp_lsb',
    0x09: 'temp_msb',
    0x10: 'event',
    0x11: 'int_status',
    0x12: 'fifo_len_0',
    0x13: 'fifo_len_1',
    0x14: 'fifo_data',
    0x15: 'fifo_wtm_0',
    0x16: 'fifo_wtm_1',
    0x17: 'fifo_config_1',
    0x18: 'fifo_config_2',
    0x19: 'int_ctrl',
    0x1A: 'if_conf',
    0x1B: 'pwr_ctrl',
    0x1C: 'osr',
    0x1D: 'odr',
    0x1F: 'config',
    0x7E: 'cmd',
}

# Calibration NVM: 0x31..0x45, 21 bytes total (mixed widths).
CAL_REGS = [
    (0x31, 'par_t1',  'u16_le', 2),
    (0x33, 'par_t2',  'u16_le', 2),
    (0x35, 'par_t3',  's8',     1),
    (0x36, 'par_p1',  's16_le', 2),
    (0x38, 'par_p2',  's16_le', 2),
    (0x3A, 'par_p3',  's8',     1),
    (0x3B, 'par_p4',  's8',     1),
    (0x3C, 'par_p5',  'u16_le', 2),
    (0x3E, 'par_p6',  'u16_le', 2),
    (0x40, 'par_p7',  's8',     1),
    (0x41, 'par_p8',  's8',     1),
    (0x42, 'par_p9',  's16_le', 2),
    (0x44, 'par_p10', 's8',     1),
    (0x45, 'par_p11', 's8',     1),
]

OSR_P_NAMES = {0: '×1', 1: '×2', 2: '×4', 3: '×8', 4: '×16', 5: '×32'}
OSR_T_NAMES = OSR_P_NAMES
IIR_NAMES = {
    0: 'off',
    1: 'coef 1', 2: 'coef 3', 3: 'coef 7', 4: 'coef 15',
    5: 'coef 31', 6: 'coef 63', 7: 'coef 127',
}
MODE_NAMES = {0: 'sleep', 1: 'forced', 2: 'forced', 3: 'normal'}
ODR_NAMES = {
    0x00: '200 Hz',    0x01: '100 Hz',    0x02: '50 Hz',     0x03: '25 Hz',
    0x04: '12.5 Hz',   0x05: '6.25 Hz',   0x06: '3.1 Hz',    0x07: '1.5 Hz',
    0x08: '0.78 Hz',   0x09: '0.39 Hz',   0x0A: '0.2 Hz',    0x0B: '0.1 Hz',
    0x0C: '0.05 Hz',   0x0D: '0.02 Hz',   0x0E: '0.01 Hz',   0x0F: '0.006 Hz',
    0x10: '0.003 Hz',  0x11: '25/16384 Hz',
}

ANN_REG_WRITE   = 0
ANN_REG_READ    = 1
ANN_CAL_READ    = 2
ANN_DATA_READ   = 3
ANN_PTR_WRITE   = 4
ANN_FIFO_READ   = 5
ANN_WARNING     = 6


def _s16(raw):
    return raw if raw < 0x8000 else raw - 0x10000


def _s8(raw):
    return raw - 0x100 if raw >= 0x80 else raw


def _decode_pwr_ctrl(raw):
    mode_bits = (raw >> 4) & 0x03
    press_en  = (raw & 0x01) != 0
    temp_en   = (raw & 0x02) != 0
    return ('pwr_ctrl 0x%02X: mode=%s press_en=%d temp_en=%d'
            % (raw, MODE_NAMES.get(mode_bits, 'reserved'), int(press_en), int(temp_en)))


def _decode_osr(raw):
    osr_t = (raw >> 3) & 0x07
    osr_p = raw & 0x07
    return ('osr 0x%02X: osr_t=%s osr_p=%s'
            % (raw, OSR_T_NAMES.get(osr_t, 'reserved'), OSR_P_NAMES.get(osr_p, 'reserved')))


def _decode_config(raw):
    iir = (raw >> 1) & 0x07
    return 'config 0x%02X: iir=%s' % (raw, IIR_NAMES.get(iir, 'reserved'))


def _decode_odr(raw):
    return 'odr 0x%02X: %s' % (raw, ODR_NAMES.get(raw, 'reserved'))


def _decode_status(raw):
    drdy_t = (raw >> 6) & 1
    drdy_p = (raw >> 5) & 1
    cmd_rdy = (raw >> 4) & 1
    parts = []
    if drdy_t: parts.append('drdy_temp')
    if drdy_p: parts.append('drdy_press')
    if cmd_rdy: parts.append('cmd_rdy')
    detail = ', '.join(parts) if parts else 'idle'
    return 'status 0x%02X: %s' % (raw, detail)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'bmp384'
    name = 'BMP384'
    longname = 'BMP384 high-precision barometric pressure sensor'
    desc = 'Decode BMP384 I2C pressure/temperature sensor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['bmp384']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('cal-read',  'Calibration read'),
        ('data-read', 'ADC data read'),
        ('ptr-write', 'Register pointer write'),
        ('fifo-read', 'FIFO read'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_CAL_READ,
                                  ANN_DATA_READ, ANN_PTR_WRITE, ANN_FIFO_READ)),
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
        if 0x31 <= reg <= 0x45:
            return 'nvm[0x%02X]' % reg
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

        # Calibration NVM burst: 0x31..0x45 (21 bytes total, mixed widths).
        if 0x31 <= reg <= 0x45 and len(buf) >= 1:
            cursor = reg
            entries = []
            ok = True
            while cursor <= 0x45 and len(entries) < len(CAL_REGS):
                start_addr, name, fmt, size = CAL_REGS[len(entries)]
                if cursor > start_addr:
                    ok = False
                    break
                if cursor < start_addr:
                    cursor = start_addr
                if cursor + size > 0x46 or cursor + size > len(buf) + reg:
                    break
                if size == 1:
                    raw8 = buf[cursor - reg]
                    val = _s8(raw8)
                else:
                    lo = buf[cursor - reg]
                    hi = buf[cursor - reg + 1]
                    raw16 = (hi << 8) | lo
                    val = _s16(raw16) if fmt == 's16_le' else raw16
                entries.append('%s=%d' % (name, val))
                cursor += size
            if entries:
                self.put(ss, es, self.out_ann,
                         [ANN_CAL_READ,
                          ['Calibration: %s' % ', '.join(entries),
                           'CAL']])
                if not ok:
                    self._warn(ss, es, 'Calibration parse fell short of NVM range')
                return

        # ADC data burst: 6 bytes from 0x04 (P) and 0x07..0x09 (T) split,
        # but the spec models one burst of 0x04..0x09 (3+3 bytes).
        if reg == 0x04 and len(buf) >= 6:
            uncomp_p = (buf[2] << 16) | (buf[1] << 8) | buf[0]
            uncomp_t = (buf[5] << 16) | (buf[4] << 8) | buf[3]
            self.put(ss, es, self.out_ann,
                     [ANN_DATA_READ,
                      ['ADC: uncomp_p=%d uncomp_t=%d (raw)' % (uncomp_p, uncomp_t),
                       'P=%d T=%d' % (uncomp_p, uncomp_t)]])
            return

        if reg == 0x00 and len(buf) == 1:
            chip_id = buf[0]
            label = 'BMP384' if chip_id == 0x50 else 'unknown'
            if chip_id != 0x50:
                self._warn(ss, es, 'Unexpected chip ID: 0x%02X' % chip_id)
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['Chip ID: 0x%02X (%s)' % (chip_id, label),
                       'ID 0x%02X' % chip_id]])
            return

        if reg == 0x03 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      [_decode_status(buf[0]), 'status 0x%02X' % buf[0]]])
            return

        if reg == 0x14 and len(buf) >= 1:
            # FIFO_DATA read: emit a single "FIFO read" annotation showing
            # the byte count, then fall through to a generic read note.
            self.put(ss, es, self.out_ann,
                     [ANN_FIFO_READ,
                      ['FIFO: %d byte(s)' % len(buf), 'FIFO %dB' % len(buf)]])

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

        if reg == 0x1B and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_pwr_ctrl(buf[0]), 'pwr 0x%02X' % buf[0]]])
            return
        if reg == 0x1C and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_osr(buf[0]), 'osr 0x%02X' % buf[0]]])
            return
        if reg == 0x1F and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_config(buf[0]), 'cfg 0x%02X' % buf[0]]])
            return
        if reg == 0x1D and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_odr(buf[0]), 'odr 0x%02X' % buf[0]]])
            return
        if reg == 0x7E and len(buf) == 1:
            if buf[0] == 0xB6:
                note = ' (soft reset)'
            elif buf[0] == 0xB0:
                note = ' (FIFO flush)'
            else:
                note = ' (unexpected 0x%02X)' % buf[0]
                self._warn(ss, es, 'Unexpected CMD value: 0x%02X' % buf[0])
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['cmd ← 0x%02X%s' % (buf[0], note), 'CMD']])
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
                pass  # pointer already set; preserve across repeated start
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
