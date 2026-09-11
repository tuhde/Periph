import sigrokdecode as srd

ADDRS = {0x5C, 0x5D}

REGS = {
    0x0B: 'interrupt_cfg',
    0x0C: 'ths_p_l',
    0x0D: 'ths_p_h',
    0x0F: 'who_am_i',
    0x10: 'ctrl_reg1',
    0x11: 'ctrl_reg2',
    0x12: 'ctrl_reg3',
    0x14: 'fifo_ctrl',
    0x15: 'ref_p_xl',
    0x16: 'ref_p_l',
    0x17: 'ref_p_h',
    0x18: 'rpds_l',
    0x19: 'rpds_h',
    0x1A: 'res_conf',
    0x25: 'int_source',
    0x26: 'fifo_status',
    0x27: 'status',
    0x28: 'press_xl',
    0x29: 'press_l',
    0x2A: 'press_h',
    0x2B: 'temp_l',
    0x2C: 'temp_h',
    0x33: 'lpfp_res',
}

# CTRL_REG1 ODR value -> Hz
ODR_NAMES = {
    0: 'Power-down/One-shot',
    1: '1 Hz',
    2: '10 Hz',
    3: '25 Hz',
    4: '50 Hz',
    5: '75 Hz',
    6: 'reserved',
    7: 'reserved',
}
CTRL_REG1_LPF_BW = {
    0: 'ODR/9',
    1: 'ODR/20',
}

# FIFO_CTRL F_MODE value -> name
FIFO_MODE_NAMES = {
    0: 'Bypass',
    1: 'FIFO',
    2: 'Stream',
    3: 'Stream-to-FIFO',
    4: 'Bypass-to-Stream',
    5: 'reserved',
    6: 'Dynamic-Stream',
    7: 'Bypass-to-FIFO',
}

# CTRL_REG3 INT_S selection
CTRL_REG3_INT_S = {
    0: 'data signals',
    1: 'pressure high',
    2: 'pressure low',
    3: 'pressure low/high',
}

ANN_REG_WRITE     = 0
ANN_REG_READ      = 1
ANN_BURST_READ    = 2
ANN_PTR_WRITE     = 3
ANN_WARNING       = 4
# Named start/end pair for the one-shot conversion conformance check
# (see specs/pressure/lps33hw.md "Timing Constraints" and
# specs/pressure/lps33hw_timing.conf):
#   one_shot_start = CTRL_REG2 write with ONE_SHOT=1 reaches the bus.
#   one_shot_done  = STATUS read returns P_DA=1 AND T_DA=1.
# The conformance checker measures the sample-delta between these two
# annotations. Additive — does not replace the generic reg-write/
# reg-read annotations emitted alongside.
ANN_ONE_SHOT_START = 5
ANN_ONE_SHOT_DONE  = 6


def _s16(raw):
    return raw if raw < 0x8000 else raw - 0x10000


def _s24(raw):
    return raw if raw < 0x800000 else raw - 0x1000000


def _decode_ctrl_reg1(raw):
    odr = (raw >> 4) & 7
    en_lpfp = (raw >> 3) & 1
    lpfp_cfg = (raw >> 2) & 1
    bdu = (raw >> 1) & 1
    sim = raw & 1
    lpf_text = ''
    if en_lpfp:
        lpf_text = ', en_lpfp=1, lpfp_cfg=%s' % CTRL_REG1_LPF_BW[lpfp_cfg]
    return ('ctrl_reg1 0x%02X: odr=%s, bdu=%d, sim=%d%s'
            % (raw, ODR_NAMES[odr], bdu, sim, lpf_text))


def _decode_ctrl_reg2(raw):
    boot = (raw >> 7) & 1
    fifo_en = (raw >> 6) & 1
    stop_on_fth = (raw >> 5) & 1
    if_add_inc = (raw >> 4) & 1
    i2c_dis = (raw >> 3) & 1
    swreset = (raw >> 2) & 1
    one_shot = raw & 1
    return ('ctrl_reg2 0x%02X: boot=%d, fifo_en=%d, stop_on_fth=%d, '
            'if_add_inc=%d, i2c_dis=%d, swreset=%d, one_shot=%d'
            % (raw, boot, fifo_en, stop_on_fth, if_add_inc, i2c_dis, swreset, one_shot))


def _decode_ctrl_reg3(raw):
    int_h_l = (raw >> 7) & 1
    pp_od = (raw >> 6) & 1
    f_fss5 = (raw >> 5) & 1
    f_fth = (raw >> 4) & 1
    f_ovr = (raw >> 3) & 1
    drdy = (raw >> 2) & 1
    int_s = raw & 3
    return ('ctrl_reg3 0x%02X: int_h_l=%d, pp_od=%d, f_fss5=%d, f_fth=%d, '
            'f_ovr=%d, drdy=%d, int_s=%s'
            % (raw, int_h_l, pp_od, f_fss5, f_fth, f_ovr, drdy,
               CTRL_REG3_INT_S[int_s]))


def _decode_interrupt_cfg(raw):
    autorifp = (raw >> 7) & 1
    reset_arp = (raw >> 6) & 1
    autozero = (raw >> 5) & 1
    reset_az = (raw >> 4) & 1
    diff_en = (raw >> 3) & 1
    lir = (raw >> 2) & 1
    ple = (raw >> 1) & 1
    phe = raw & 1
    return ('interrupt_cfg 0x%02X: autorifp=%d, reset_arp=%d, autozero=%d, '
            'reset_az=%d, diff_en=%d, lir=%d, ple=%d, phe=%d'
            % (raw, autorifp, reset_arp, autozero, reset_az, diff_en, lir, ple, phe))


def _decode_res_conf(raw):
    lc_en = raw & 1
    return 'res_conf 0x%02X: lc_en=%d' % (raw, lc_en)


def _decode_status(raw):
    t_or = (raw >> 5) & 1
    p_or = (raw >> 4) & 1
    t_da = (raw >> 1) & 1
    p_da = raw & 1
    parts = []
    if p_da:
        parts.append('Pressure ready')
    if t_da:
        parts.append('Temperature ready')
    if p_or:
        parts.append('P overrun')
    if t_or:
        parts.append('T overrun')
    detail = ', '.join(parts) if parts else 'idle'
    return 'status 0x%02X: %s' % (raw, detail)


def _decode_int_source(raw):
    boot = (raw >> 7) & 1
    ia = (raw >> 2) & 1
    pl = (raw >> 1) & 1
    ph = raw & 1
    parts = []
    if ia:
        parts.append('Interrupt active')
    if ph:
        parts.append('Pressure high')
    if pl:
        parts.append('Pressure low')
    if boot:
        parts.append('Boot in progress')
    detail = ', '.join(parts) if parts else 'idle'
    return 'int_source 0x%02X: %s' % (raw, detail)


def _decode_fifo_status(raw):
    fth = (raw >> 7) & 1
    ovr = (raw >> 6) & 1
    fss = raw & 0x3F
    parts = ['fth=%d' % fth, 'ovr=%d' % ovr, 'count=%d' % fss]
    return 'fifo_status 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_fifo_ctrl(raw):
    f_mode = (raw >> 5) & 7
    wtm = raw & 0x1F
    return 'fifo_ctrl 0x%02X: f_mode=%s, watermark=%d' % (
        raw, FIFO_MODE_NAMES[f_mode], wtm)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'lps33hw'
    name = 'LPS33HW'
    longname = 'LPS33HW water-resistant MEMS absolute pressure sensor'
    desc = 'Decode LPS33HW I2C pressure/temperature sensor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['lps33hw']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write',     'Register write'),
        ('reg-read',      'Register read'),
        ('burst-read',    'Multi-byte burst read'),
        ('ptr-write',     'Register pointer write'),
        ('warning',       'Warning'),
        ('one-shot-start', 'One-shot: start'),
        ('one-shot-done',  'One-shot: done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_BURST_READ, ANN_PTR_WRITE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
        ('timing',   'Timing',   (ANN_ONE_SHOT_START, ANN_ONE_SHOT_DONE)),
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
        return REGS.get(reg, 'reg[0x%02X]' % reg)

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

        # Chip-ID read.
        if reg == 0x0F and len(buf) == 1:
            chip_id = buf[0]
            label = 'LPS33HW' if chip_id == 0xB1 else 'unknown'
            if chip_id != 0xB1:
                self._warn(ss, es, 'Unexpected chip ID: 0x%02X' % chip_id)
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['Chip ID: 0x%02X (%s)' % (chip_id, label),
                       'ID 0x%02X' % chip_id]])
            return

        # Status read — single byte.
        if reg == 0x27 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ, [_decode_status(buf[0]),
                                     'status 0x%02X' % buf[0]]])
            # one_shot_done: P_DA=1 AND T_DA=1 set. Conformance checker keys
            # off the substring 'Pressure ready' AND 'Temperature ready' (or
            # both bit names) in the annotation text — emitting it here keeps
            # it on the timing row alongside any other status annotations.
            if (buf[0] & 0x03) == 0x03:
                self.put(ss, es, self.out_ann,
                         [ANN_ONE_SHOT_DONE,
                          ['one_shot_done: STATUS shows P_DA=1 and T_DA=1',
                           'one_shot_done']])
            return

        # INT_SOURCE read.
        if reg == 0x25 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ, [_decode_int_source(buf[0]),
                                     'int_source 0x%02X' % buf[0]]])
            return

        # FIFO_STATUS read.
        if reg == 0x26 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ, [_decode_fifo_status(buf[0]),
                                     'fifo_status 0x%02X' % buf[0]]])
            return

        # FIFO_CTRL read.
        if reg == 0x14 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ, [_decode_fifo_ctrl(buf[0]),
                                     'fifo_ctrl 0x%02X' % buf[0]]])
            return

        # LPFP_RES read.
        if reg == 0x33 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ, ['LPF reset (flush transitory state)',
                                     'LPF reset']])
            return

        # Pressure + temperature burst read from 0x28..0x2C (5 bytes).
        if reg == 0x28 and len(buf) >= 5:
            raw_p = (buf[2] << 16) | (buf[1] << 8) | buf[0]
            signed_p = _s24(raw_p)
            pressure_Pa = signed_p * 100.0 / 4096.0
            raw_t = (buf[4] << 8) | buf[3]
            signed_t = _s16(raw_t)
            temperature_C = signed_t / 100.0
            pressure_hPa = pressure_Pa / 100.0
            self.put(ss, es, self.out_ann,
                     [ANN_BURST_READ,
                      ['Pressure/Temperature burst: P=%.2f Pa (%.2f hPa), T=%.2f C '
                       '(raw P=%d, raw T=%d)' % (pressure_Pa, pressure_hPa,
                                                 temperature_C, signed_p, signed_t),
                       'P=%.1fPa T=%.2fC' % (pressure_Pa, temperature_C)]])
            return

        # Threshold (THS_P_H:THS_P_L) auto-increment read.
        if reg == 0x0C and len(buf) >= 2:
            raw_t = (buf[1] << 8) | buf[0]
            threshold_hPa = raw_t / 16.0
            self.put(ss, es, self.out_ann,
                     [ANN_BURST_READ,
                      ['Pressure threshold: %.2f hPa (raw=%d)' % (threshold_hPa, raw_t),
                       'THS=%.2fhPa' % threshold_hPa]])
            return

        # REF_P burst read.
        if reg == 0x15 and len(buf) >= 3:
            raw_p = (buf[2] << 16) | (buf[1] << 8) | buf[0]
            signed_p = _s24(raw_p)
            ref_hPa = signed_p / 4096.0
            self.put(ss, es, self.out_ann,
                     [ANN_BURST_READ,
                      ['Reference pressure: %.2f hPa (raw=%d)' % (ref_hPa, signed_p),
                       'REF_P=%.2fhPa' % ref_hPa]])
            return

        # RPDS (RPDS_H:RPDS_L) auto-increment read.
        if reg == 0x18 and len(buf) >= 2:
            raw_t = (buf[1] << 8) | buf[0]
            signed_t = _s16(raw_t)
            offset_hPa = signed_t / 16.0
            self.put(ss, es, self.out_ann,
                     [ANN_BURST_READ,
                      ['Pressure offset: %.4f hPa (raw=%d)' % (offset_hPa, signed_t),
                       'RPDS=%.4fhPa' % offset_hPa]])
            return

        # Generic read.
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

        if reg == 0x10 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_ctrl_reg1(buf[0]), 'ctrl1 0x%02X' % buf[0]]])
            return

        if reg == 0x11 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_ctrl_reg2(buf[0]), 'ctrl2 0x%02X' % buf[0]]])
            # one_shot_start: ONE_SHOT bit (bit 0) set in CTRL_REG2.
            if buf[0] & 0x01:
                self.put(ss, es, self.out_ann,
                         [ANN_ONE_SHOT_START,
                          ['one_shot_start: CTRL_REG2 ONE_SHOT=1 reaches the bus',
                           'one_shot_start']])
            return

        if reg == 0x12 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_ctrl_reg3(buf[0]), 'ctrl3 0x%02X' % buf[0]]])
            return

        if reg == 0x0B and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_interrupt_cfg(buf[0]), 'cfg 0x%02X' % buf[0]]])
            return

        if reg == 0x1A and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_res_conf(buf[0]), 'res 0x%02X' % buf[0]]])
            return

        if reg == 0x14 and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, [_decode_fifo_ctrl(buf[0]), 'fifo_ctrl 0x%02X' % buf[0]]])
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