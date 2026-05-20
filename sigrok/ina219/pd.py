import sigrokdecode as srd

ADDRS = set(range(0x40, 0x50))

REGS = {
    0x00: 'Configuration',
    0x01: 'Shunt Voltage',
    0x02: 'Bus Voltage',
    0x03: 'Power',
    0x04: 'Current',
    0x05: 'Calibration',
}

PGA = {0: '/1 (±40 mV)', 1: '/2 (±80 mV)', 2: '/4 (±160 mV)', 3: '/8 (±320 mV)'}

ADC_MODE = {
    0:  '9-bit (84 µs)',
    1:  '10-bit (148 µs)',
    2:  '11-bit (276 µs)',
    3:  '12-bit (532 µs)',
    8:  '12-bit (532 µs)',
    9:  'avg×2 (1.06 ms)',
    10: 'avg×4 (2.13 ms)',
    11: 'avg×8 (4.26 ms)',
    12: 'avg×16 (8.51 ms)',
    13: 'avg×32 (17.02 ms)',
    14: 'avg×64 (34.05 ms)',
    15: 'avg×128 (68.10 ms)',
}

MODE = {
    0: 'Power-down',
    1: 'Shunt triggered',
    2: 'Bus triggered',
    3: 'Shunt+Bus triggered',
    4: 'ADC off',
    5: 'Shunt continuous',
    6: 'Bus continuous',
    7: 'Shunt+Bus continuous',
}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3


def _decode_config(raw):
    rst  = (raw >> 15) & 1
    brng = (raw >> 13) & 1
    pg   = (raw >> 11) & 3
    badc = (raw >> 7)  & 0xF
    sadc = (raw >> 3)  & 0xF
    mode = raw & 7
    parts = [
        'Config 0x%04X' % raw,
        'RST=%d' % rst if rst else None,
        'BRNG=%s' % ('32V' if brng else '16V'),
        'PGA=%s' % PGA.get(pg, str(pg)),
        'BADC=%s' % ADC_MODE.get(badc, str(badc)),
        'SADC=%s' % ADC_MODE.get(sadc, str(sadc)),
        'MODE=%s' % MODE.get(mode, str(mode)),
    ]
    return ', '.join(p for p in parts if p is not None)


def _decode_shunt(raw):
    signed = raw if raw < 0x8000 else raw - 0x10000
    uv = signed * 10
    return 'Shunt 0x%04X = %+d µV (%+.3f mV)' % (raw, uv, uv / 1000.0)


def _decode_bus(raw):
    flags = []
    if raw & 2:
        flags.append('CNVR')
    if raw & 1:
        flags.append('OVF')
    mv = (raw >> 3) * 4
    flag_str = ' [%s]' % ','.join(flags) if flags else ''
    return 'Bus 0x%04X = %d mV (%.3f V)%s' % (raw, mv, mv / 1000.0, flag_str)


def _decode_reg(reg, raw):
    if reg == 0x00:
        return _decode_config(raw)
    elif reg == 0x01:
        return _decode_shunt(raw)
    elif reg == 0x02:
        return _decode_bus(raw)
    elif reg == 0x03:
        return 'Power 0x%04X (×Power_LSB W)' % raw
    elif reg == 0x04:
        signed = raw if raw < 0x8000 else raw - 0x10000
        return 'Current 0x%04X = %+d (×Current_LSB A)' % (raw, signed)
    elif reg == 0x05:
        return 'Calibration 0x%04X' % raw
    return 'Reg[0x%02X] 0x%04X' % (reg, raw)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'ina219'
    name = 'INA219'
    longname = 'INA219 26V power monitor'
    desc = 'Decode INA219 I2C current/voltage/power monitor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ina219']
    tags = ['IC', 'Power']

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

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _emit(self, ann_idx, ss, es, texts):
        self.put(ss, es, self.out_ann, [ann_idx, texts])

    def _finish_transaction(self):
        if not self.databuf:
            return

        reg  = self.reg_ptr
        name = REGS.get(reg, 'Reg[0x%02X]' % reg) if reg is not None else '?'

        if self.is_read:
            if len(self.databuf) == 2:
                raw = (self.databuf[0] << 8) | self.databuf[1]
                detail = _decode_reg(reg, raw)
                self._emit(ANN_REG_READ, self.ss_block, self.es,
                           ['Read %s: %s' % (name, detail),
                            'R %s 0x%04X' % (name, raw)])
            else:
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d for %s' % (len(self.databuf), name))
        else:
            if len(self.databuf) == 0:
                # Pure pointer write (no data)
                self._emit(ANN_PTR_WRITE, self.ss_block, self.es,
                           ['Pointer → %s (0x%02X)' % (name, reg),
                            'PTR 0x%02X' % reg])
            elif len(self.databuf) == 2:
                raw = (self.databuf[0] << 8) | self.databuf[1]
                detail = _decode_reg(reg, raw)
                self._emit(ANN_REG_WRITE, self.ss_block, self.es,
                           ['Write %s: %s' % (name, detail),
                            'W %s 0x%04X' % (name, raw)])
            else:
                self._warn(self.ss_block, self.es,
                           'Unexpected write length %d for %s' % (len(self.databuf), name))

    def decode(self):
        while True:
            ptype, pdata = self.wait()

            if ptype in ('START', 'START REPEAT'):
                if self.state == 'GET_DATA_READ' and ptype == 'START REPEAT':
                    pass  # keep accumulated read data; repeated-start precedes the read phase
                else:
                    self._finish_transaction()
                    self.databuf  = []
                    self.is_read  = False
                self.ss_block = self.ss
                self.state    = 'GET_ADDR'

            elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
                addr = pdata[0]
                if addr not in ADDRS:
                    self.state = 'IDLE'
                    continue
                self.addr    = addr
                self.is_read = (ptype == 'ADDRESS READ')
                if self.is_read:
                    self.databuf = []
                    self.state   = 'GET_DATA_READ'
                else:
                    self.state = 'GET_REG_PTR'

            elif ptype == 'DATA WRITE':
                byte = pdata[0]
                if self.state == 'GET_REG_PTR':
                    self.reg_ptr = byte
                    self.databuf = []
                    self.state   = 'GET_DATA_WRITE'
                elif self.state == 'GET_DATA_WRITE':
                    self.databuf.append(byte)

            elif ptype == 'DATA READ':
                if self.state == 'GET_DATA_READ':
                    self.databuf.append(pdata[0])

            elif ptype == 'STOP':
                if self.state in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
                    self._finish_transaction()
                self.state   = 'IDLE'
                self.databuf = []
