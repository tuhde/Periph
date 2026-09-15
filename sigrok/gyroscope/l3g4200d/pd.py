import sigrokdecode as srd

ADDRS = {0x68, 0x69}

REGS = {
    0x0F: 'WHO_AM_I',
    0x20: 'CTRL_REG1',
    0x21: 'CTRL_REG2',
    0x22: 'CTRL_REG3',
    0x23: 'CTRL_REG4',
    0x24: 'CTRL_REG5',
    0x25: 'REFERENCE',
    0x26: 'OUT_TEMP',
    0x27: 'STATUS_REG',
    0x28: 'OUT_X_L',
    0x29: 'OUT_X_H',
    0x2A: 'OUT_Y_L',
    0x2B: 'OUT_Y_H',
    0x2C: 'OUT_Z_L',
    0x2D: 'OUT_Z_H',
    0x2E: 'FIFO_CTRL_REG',
    0x2F: 'FIFO_SRC_REG',
    0x30: 'INT1_CFG',
    0x31: 'INT1_SRC',
    0x32: 'INT1_THS_XH',
    0x33: 'INT1_THS_XL',
    0x34: 'INT1_THS_YH',
    0x35: 'INT1_THS_YL',
    0x36: 'INT1_THS_ZH',
    0x37: 'INT1_THS_ZL',
    0x38: 'INT1_DURATION',
}

DR = {0: '100 Hz', 1: '200 Hz', 2: '400 Hz', 3: '800 Hz'}
BW = {0: '12.5 Hz', 1: '25 Hz', 2: '25 Hz', 3: '25 Hz'}
PWR = {0: 'Power-down', 1: 'Normal'}
FS = {0: '±250 dps', 1: '±500 dps', 2: '±2000 dps', 3: '±2000 dps'}
SENS = {0: 8.75e-3, 1: 17.5e-3, 2: 70.0e-3, 3: 70.0e-3}  # dps/digit, by FS bits
HPM = {0: 'normal', 1: 'reference', 2: 'normal', 3: 'autoreset'}
FM = {
    0: 'bypass',
    1: 'FIFO',
    2: 'stream',
    3: 'stream-to-FIFO',
    4: 'bypass-to-stream',
}
INT_COMB = {0: 'OR', 1: 'AND'}
INT_LATCH = {0: 'not latched', 1: 'latched'}
HPCF = {i: 'cutoff=%d' % i for i in range(16)}  # placeholder

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3
# Conformance annotation pairs (see specs/gyroscope/l3g4200d.md, Sigrok
# Decoder + Timing Constraints, and specs/gyroscope/l3g4200d_timing.conf).
# The first pair (odr_start / odr_ready) covers "first valid data is
# available after 1 ODR period"; the second (odr_start / odr_next)
# covers "subsequent conversions are emitted every ODR period".
ANN_ODR_START = 4
ANN_ODR_READY  = 5
ANN_ODR_NEXT   = 6


def _decode_ctrl_reg1(raw):
    dr = (raw >> 6) & 0x3
    bw = (raw >> 4) & 0x3
    pd = (raw >> 3) & 1
    zen = (raw >> 2) & 1
    yen = (raw >> 1) & 1
    xen = raw & 1
    parts = ['CTRL_REG1 0x%02X' % raw]
    parts.append('DR=%s' % DR.get(dr, str(dr)))
    parts.append('BW=%s' % BW.get(bw, str(bw)))
    parts.append('PD=%d' % pd)
    axes = []
    if xen: axes.append('X')
    if yen: axes.append('Y')
    if zen: axes.append('Z')
    parts.append('axes=%s' % ('all' if axes and len(axes) == 3 else
                              ('none' if not axes else '+'.join(axes))))
    return ', '.join(parts)


def _decode_ctrl_reg4(raw):
    bdu = (raw >> 7) & 1
    ble = (raw >> 6) & 1
    fs  = (raw >> 4) & 0x3
    st  = raw & 0x3
    sim = raw & 1  # SPI 3-wire mode
    parts = ['CTRL_REG4 0x%02X' % raw, 'BDU=%d' % bdu, 'BLE=%d' % ble,
             'FS=%s' % FS.get(fs, str(fs)), 'ST=%d' % st]
    if sim:
        parts.append('SIM=1 (3-wire SPI)')
    return ', '.join(parts)


def _decode_ctrl_reg2(raw):
    hpm = (raw >> 4) & 0x3
    hpcf = raw & 0x0F
    return 'CTRL_REG2 0x%02X, HPF mode=%s, HPCF=%d' % (raw, HPM.get(hpm, '?'), hpcf)


def _decode_ctrl_reg3(raw):
    return 'CTRL_REG3 0x%02X (INT/DRDY routing)' % raw


def _decode_ctrl_reg5(raw):
    boot = (raw >> 7) & 1
    fifo_en = (raw >> 6) & 1
    hpen = (raw >> 4) & 1
    int1_sel = (raw >> 2) & 0x3
    out_sel = raw & 0x3
    return ('CTRL_REG5 0x%02X, BOOT=%d, FIFO_EN=%d, HPen=%d, '
            'INT1_Sel=%d, Out_Sel=%d' % (raw, boot, fifo_en, hpen, int1_sel, out_sel))


def _decode_status(raw):
    flags = []
    if raw & 0x80: flags.append('ZYXOR')
    if raw & 0x40: flags.append('ZOR')
    if raw & 0x20: flags.append('YOR')
    if raw & 0x10: flags.append('XOR')
    if raw & 0x08: flags.append('ZYXDA')
    if raw & 0x04: flags.append('ZDA')
    if raw & 0x02: flags.append('YDA')
    if raw & 0x01: flags.append('XDA')
    return 'STATUS_REG 0x%02X%s' % (raw, (' [' + ' '.join(flags) + ']') if flags else '')


def _decode_fifo_ctrl(raw):
    mode = (raw >> 5) & 0x7
    wtm = raw & 0x1F
    return 'FIFO_CTRL_REG 0x%02X, mode=%s, watermark=%d' % (raw, FM.get(mode, '?'), wtm)


def _decode_fifo_src(raw):
    wtm_flag = (raw >> 7) & 1
    ovrn = (raw >> 6) & 1
    empty = (raw >> 5) & 1
    fss = raw & 0x1F
    flags = []
    if wtm_flag: flags.append('WTM')
    if ovrn: flags.append('OVRN')
    if empty: flags.append('EMPTY')
    return 'FIFO_SRC_REG 0x%02X%s, FSS=%d' % (
        raw, (' [' + ' '.join(flags) + ']') if flags else '', fss)


def _decode_int1_cfg(raw):
    and_or = (raw >> 7) & 1
    lir = (raw >> 6) & 1
    events = []
    if raw & 0x20: events.append('Z_high')
    if raw & 0x10: events.append('Z_low')
    if raw & 0x08: events.append('Y_high')
    if raw & 0x04: events.append('Y_low')
    if raw & 0x02: events.append('X_high')
    if raw & 0x01: events.append('X_low')
    return ('INT1_CFG 0x%02X, comb=%s, latch=%s, events=%s'
            % (raw, INT_COMB[and_or], INT_LATCH[lir],
               ('none' if not events else '+'.join(events))))


def _decode_int1_src(raw):
    return 'INT1_SRC 0x%02X' % raw


def _decode_int1_duration(raw):
    wait = (raw >> 7) & 1
    dur = raw & 0x7F
    return 'INT1_DURATION 0x%02X, wait=%d, samples=%d' % (raw, wait, dur)


def _decode_out_temp(raw):
    signed = raw if raw < 0x80 else raw - 0x100
    return 'OUT_TEMP 0x%02X (signed %d, -1 °C/digit relative)' % (raw, signed)


def _decode_whoami(raw):
    if raw == 0xD3:
        return 'WHO_AM_I 0x%02X (L3G4200D ✓)' % raw
    return 'WHO_AM_I 0x%02X (expected 0xD3!)' % raw


def _decode_reg(reg, raw):
    if reg == 0x0F:  return _decode_whoami(raw)
    if reg == 0x20:  return _decode_ctrl_reg1(raw)
    if reg == 0x21:  return _decode_ctrl_reg2(raw)
    if reg == 0x22:  return _decode_ctrl_reg3(raw)
    if reg == 0x23:  return _decode_ctrl_reg4(raw)
    if reg == 0x24:  return _decode_ctrl_reg5(raw)
    if reg == 0x26:  return _decode_out_temp(raw)
    if reg == 0x27:  return _decode_status(raw)
    if reg == 0x2E:  return _decode_fifo_ctrl(raw)
    if reg == 0x2F:  return _decode_fifo_src(raw)
    if reg == 0x30:  return _decode_int1_cfg(raw)
    if reg == 0x31:  return _decode_int1_src(raw)
    if reg == 0x38:  return _decode_int1_duration(raw)
    return 'Reg[0x%02X] 0x%02X' % (reg, raw)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'l3g4200d'
    name = 'L3G4200D'
    longname = 'ST L3G4200D 3-axis MEMS gyroscope'
    desc = 'Decode L3G4200D I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['l3g4200d']
    tags = ['IC', 'Sensor', 'Gyroscope']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
        ('odr-start', 'odr_start: CTRL_REG1 write with PD=1'),
        ('odr-ready', 'odr_ready: STATUS_REG read with ZYXDA=1'),
        ('odr-next',  'odr_next: STATUS_REG read with ZYXDA=1'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
        ('timing',   'Timing',   (ANN_ODR_START, ANN_ODR_READY, ANN_ODR_NEXT)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state     = 'IDLE'
        self.addr      = None
        self.is_read   = False
        self.reg_ptr   = None
        self.databuf   = []
        self.ss_block  = None
        self.full_scale_bits = 0  # last seen FS[1:0] from CTRL_REG4

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
            if not self.databuf:
                return
            raw = self.databuf[0]
            if reg == 0x28 or reg == 0x2A or reg == 0x2C:
                # low byte of a 2-byte angular-rate register; wait for the
                # next byte to assemble a 16-bit value (auto-increment).
                return
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_REG_READ,
                      ['Read %s: %s' % (name, _decode_reg(reg, raw)),
                       'R %s 0x%02X' % (name, raw)]])
            # odr_ready / odr_next: STATUS_REG read with ZYXDA (bit 3) set.
            if reg == 0x27 and (raw & 0x08):
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_ODR_READY,
                          ['odr_ready: ZYXDA=1 (STATUS_REG=0x%02X)' % raw,
                           'odr_ready']])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_ODR_NEXT,
                          ['odr_next: ZYXDA=1 (STATUS_REG=0x%02X)' % raw,
                           'odr_next']])
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_PTR_WRITE,
                          ['Pointer → %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg]])
                return
            if len(self.databuf) >= 1:
                raw = self.databuf[0]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE,
                          ['Write %s: %s' % (name, _decode_reg(reg, raw)),
                           'W %s 0x%02X' % (name, raw)]])
                # odr_start: CTRL_REG1 write with PD=1 (bit 3 set).
                if reg == 0x20 and (raw & 0x08):
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_ODR_START,
                              ['odr_start: CTRL_REG1=0x%02X (PD=1)' % raw,
                               'odr_start']])
                # Track the configured full scale so subsequent angular-rate
                # reads can be annotated with the sensitivity.
                if reg == 0x23:
                    self.full_scale_bits = (raw >> 4) & 0x3

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass  # pointer already set; keep databuf
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
                self.state   = 'GET_REG_PTR'

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
