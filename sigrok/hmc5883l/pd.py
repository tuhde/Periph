import sigrokdecode as srd

ADDRS = {0x1E}

REGS = {
    0x00: 'CONFIG_A',
    0x01: 'CONFIG_B',
    0x02: 'MODE',
    0x03: 'DATA_X_MSB',
    0x04: 'DATA_X_LSB',
    0x05: 'DATA_Z_MSB',
    0x06: 'DATA_Z_LSB',
    0x07: 'DATA_Y_MSB',
    0x08: 'DATA_Y_LSB',
    0x09: 'STATUS',
    0x0A: 'ID_A',
    0x0B: 'ID_B',
    0x0C: 'ID_C',
}

GAIN_LSB_PER_GAUSS = {
    0: 1370,  # GN=0: ±0.88 Ga
    1: 1090,  # GN=1: ±1.3 Ga
    2: 820,   # GN=2: ±1.9 Ga
    3: 660,   # GN=3: ±2.5 Ga
    4: 440,   # GN=4: ±4.0 Ga
    5: 390,   # GN=5: ±4.7 Ga
    6: 330,   # GN=6: ±5.6 Ga
    7: 230,   # GN=7: ±8.1 Ga
}

ODR_MAP = {
    0b000: 0.75,
    0b001: 1.5,
    0b010: 3,
    0b011: 7.5,
    0b100: 15,
    0b101: 30,
    0b110: 75,
}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3


def _decode_config_a(raw):
    ma = (raw >> 5) & 0x03
    do = (raw >> 2) & 0x07
    ms = raw & 0x03
    avg_map = {0b00: '1', 0b01: '2', 0b10: '4', 0b11: '8'}
    ms_map = {0b00: 'Normal', 0b01: 'Positive bias', 0b10: 'Negative bias', 0b11: 'Reserved'}
    odr = ODR_MAP.get(do, 'Reserved')
    parts = [
        'CONFIG_A 0x%02X' % raw,
        'MA=%s (avg %s)' % (ma, avg_map.get(ma, '?')),
        'DO=0x%X (ODR %s Hz)' % (do, odr),
        'MS=%s (%s)' % (ms, ms_map.get(ms, '?')),
    ]
    return ', '.join(parts)


def _decode_config_b(raw):
    gn = (raw >> 5) & 0x07
    lsb_per_gauss = GAIN_LSB_PER_GAUSS.get(gn, '?')
    return 'CONFIG_B 0x%02X: GN=%d (±%s Ga, %s LSb/Gauss)' % (raw, gn, {0: '0.88', 1: '1.3', 2: '1.9', 3: '2.5', 4: '4.0', 5: '4.7', 6: '5.6', 7: '8.1'}.get(gn, '?'), lsb_per_gauss)


def _decode_mode(raw):
    hs = (raw >> 7) & 1
    md = raw & 0x03
    md_map = {0b00: 'Continuous', 0b01: 'Single', 0b10: 'Idle', 0b11: 'Idle'}
    return 'MODE 0x%02X: HS=%d (%s), MD=%s' % (raw, hs, '3400 kHz' if hs else '400 kHz', md_map.get(md, '?'))


def _decode_status(raw):
    rdy = 'RDY=1 (Data Ready)' if (raw & 0x01) else 'RDY=0'
    lock = 'LOCK=1 (Locked)' if (raw & 0x02) else 'LOCK=0'
    return 'STATUS 0x%02X: %s, %s' % (raw, rdy, lock)


def _decode_id(raw_a, raw_b, raw_c):
    try:
        s = ''.join(chr(b) for b in (raw_a, raw_b, raw_c))
        return 'ID: 0x%02X 0x%02X 0x%02X = "%s"' % (raw_a, raw_b, raw_c, s)
    except Exception:
        return 'ID: 0x%02X 0x%02X 0x%02X' % (raw_a, raw_b, raw_c)


def _raw_to_tesla(raw, gain_lsb):
    if raw == -4096:
        return 'OVERFLOW'
    return '%.2f µT' % ((raw / gain_lsb) * 1e4)


def _decode_data_burst(raw_x, raw_z, raw_y, gain_lsb):
    # Output register byte order is X, Z, Y (not X, Y, Z)
    x_str = _raw_to_tesla(raw_x, gain_lsb)
    z_str = _raw_to_tesla(raw_z, gain_lsb)
    y_str = _raw_to_tesla(raw_y, gain_lsb)
    return 'DATA: X=%s, Z=%s, Y=%s' % (x_str, z_str, y_str)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'hmc5883l'
    name = 'HMC5883L'
    longname = 'HMC5883L 3-axis magnetometer'
    desc = 'Decode HMC5883L I2C 3-axis magnetometer register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['hmc5883l']
    tags = ['IC', 'Sensor', 'Magnetometer']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE)),
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
        self.gain_lsb = 1090  # default gain GN=1

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, 'WARN']])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg = self.reg_ptr
        if reg is None:
            if self.databuf:
                self._warn(self.ss_block, self.es,
                           'Read %d byte(s) with no register pointer set' % len(self.databuf))
            return
        name = REGS.get(reg, 'Reg[0x%02X]' % reg)

        if self.is_read:
            if reg == 0x03 and len(self.databuf) == 6:
                # Burst read from DATA_X_MSB: X MSB/LSB, Z MSB/LSB, Y MSB/LSB
                raw_x = ((self.databuf[0] << 8) | self.databuf[1]) & 0xFFFF
                raw_z = ((self.databuf[2] << 8) | self.databuf[3]) & 0xFFFF
                raw_y = ((self.databuf[4] << 8) | self.databuf[5]) & 0xFFFF
                # Convert to signed 16-bit
                raw_x = raw_x if raw_x < 0x8000 else raw_x - 0x10000
                raw_z = raw_z if raw_z < 0x8000 else raw_z - 0x10000
                raw_y = raw_y if raw_y < 0x8000 else raw_y - 0x10000
                text = _decode_data_burst(raw_x, raw_z, raw_y, self.gain_lsb)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, [text, 'R DATA burst']])
            elif reg == 0x09 and len(self.databuf) == 1:
                # STATUS: single byte read
                raw = self.databuf[0]
                text = _decode_status(raw)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, [text, 'R STATUS 0x%02X' % raw]])
            elif reg in (0x0A, 0x0B, 0x0C) and len(self.databuf) == 1:
                # ID registers
                raw = self.databuf[0]
                label = 'ID_A' if reg == 0x0A else ('ID_B' if reg == 0x0B else 'ID_C')
                text = '%s: 0x%02X (%s)' % (label, raw, chr(raw) if 0x20 <= raw < 0x7F else '?')
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, [text, 'R %s' % label]])
            elif self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d for %s' % (len(self.databuf), name))
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_PTR_WRITE,
                          ['Pointer → %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg]])
            elif reg == 0x00 and len(self.databuf) == 1:
                # CONFIG_A write
                raw = self.databuf[0]
                text = _decode_config_a(raw)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE, [text, 'W CONFIG_A 0x%02X' % raw]])
            elif reg == 0x01 and len(self.databuf) == 1:
                # CONFIG_B write
                raw = self.databuf[0]
                text = _decode_config_b(raw)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE, [text, 'W CONFIG_B 0x%02X' % raw]])
                # Update gain_lsb for subsequent data decode
                gn = (raw >> 5) & 0x07
                self.gain_lsb = GAIN_LSB_PER_GAUSS.get(gn, 1090)
            elif reg == 0x02 and len(self.databuf) == 1:
                # MODE write
                raw = self.databuf[0]
                text = _decode_mode(raw)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE, [text, 'W MODE 0x%02X' % raw]])
            else:
                self._warn(self.ss_block, self.es,
                           'Unexpected write length %d for %s' % (len(self.databuf), name))

    def decode(self, ss, es, data):
        self.ss, self.es = ss, es
        ptype, pdata = data

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass  # pointer-only write followed by repeated start; keep reg_ptr
            else:
                self._finish_transaction()
                self.databuf  = []
                self.is_read  = False
            self.ss_block = self.ss
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