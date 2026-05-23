import sigrokdecode as srd

ADDRS = set(range(0x40, 0x44))

REGS = {
    0x00: 'Configuration',
    0x01: 'CH1 Shunt Voltage',
    0x02: 'CH1 Bus Voltage',
    0x03: 'CH2 Shunt Voltage',
    0x04: 'CH2 Bus Voltage',
    0x05: 'CH3 Shunt Voltage',
    0x06: 'CH3 Bus Voltage',
    0x07: 'CH1 Critical Limit',
    0x08: 'CH1 Warning Limit',
    0x09: 'CH2 Critical Limit',
    0x0A: 'CH2 Warning Limit',
    0x0B: 'CH3 Critical Limit',
    0x0C: 'CH3 Warning Limit',
    0x0D: 'Shunt-Voltage Sum',
    0x0E: 'Shunt-Voltage Sum Limit',
    0x0F: 'Mask/Enable',
    0x10: 'Power-Valid Upper Limit',
    0x11: 'Power-Valid Lower Limit',
    0xFE: 'Manufacturer ID',
    0xFF: 'Die ID',
}

# Registers that hold shunt voltage (40 µV LSB, 13-bit signed in bits 15:3)
SHUNT_REGS = {0x01, 0x03, 0x05, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E}
# Registers that hold bus voltage (8 mV LSB, 13-bit unsigned in bits 15:3)
BUS_REGS = {0x02, 0x04, 0x06, 0x10, 0x11}

AVG = {0: '1', 1: '4', 2: '16', 3: '64', 4: '128', 5: '256', 6: '512', 7: '1024'}
CT  = {
    0: '140 µs', 1: '204 µs', 2: '332 µs', 3: '588 µs',
    4: '1.1 ms', 5: '2.116 ms', 6: '4.156 ms', 7: '8.244 ms',
}
MODE = {
    0: 'Power-down', 1: 'Shunt triggered', 2: 'Bus triggered',
    3: 'Shunt+Bus triggered', 4: 'Power-down',
    5: 'Shunt continuous', 6: 'Bus continuous', 7: 'Shunt+Bus continuous',
}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3


def _decode_config(raw):
    rst   = (raw >> 15) & 1
    ch1en = (raw >> 14) & 1
    ch2en = (raw >> 13) & 1
    ch3en = (raw >> 12) & 1
    avg   = (raw >> 9)  & 7
    vbusct= (raw >> 6)  & 7
    vshct = (raw >> 3)  & 7
    mode  = raw & 7
    parts = [
        'Config 0x%04X' % raw,
        'RST=1' if rst else None,
        'CH1=%s CH2=%s CH3=%s' % ('on' if ch1en else 'off',
                                   'on' if ch2en else 'off',
                                   'on' if ch3en else 'off'),
        'AVG=%s' % AVG.get(avg, str(avg)),
        'VBUSCT=%s' % CT.get(vbusct, str(vbusct)),
        'VSHCT=%s' % CT.get(vshct, str(vshct)),
        'MODE=%s' % MODE.get(mode, str(mode)),
    ]
    return ', '.join(p for p in parts if p is not None)


def _decode_shunt_reg(name, raw):
    # 13-bit signed value in bits 15:3; LSB = 40 µV
    val13 = (raw >> 3) & 0x1FFF
    if val13 >= 0x1000:
        val13 -= 0x2000
    uv = val13 * 40
    return '%s 0x%04X = %+d µV (%+.3f mV)' % (name, raw, uv, uv / 1000.0)


def _decode_bus_reg(name, raw):
    # 13-bit unsigned value in bits 15:3; LSB = 8 mV
    val13 = (raw >> 3) & 0x1FFF
    mv = val13 * 8
    return '%s 0x%04X = %d mV (%.3f V)' % (name, raw, mv, mv / 1000.0)


def _decode_mask_enable(raw):
    fields = []
    if raw & (1 << 14): fields.append('SCHEN1')
    if raw & (1 << 13): fields.append('SCHEN2')
    if raw & (1 << 12): fields.append('SCHEN3')
    if raw & (1 << 11): fields.append('CEN1')
    if raw & (1 << 10): fields.append('CEN2')
    if raw & (1 << 9):  fields.append('CEN3')
    if raw & (1 << 8):  fields.append('WEN1')
    if raw & (1 << 7):  fields.append('WEN2')
    if raw & (1 << 6):  fields.append('WEN3')
    flags = []
    if raw & (1 << 4): flags.append('SF')
    if raw & (1 << 3): flags.append('CF')
    if raw & (1 << 2): flags.append('WF')
    if raw & (1 << 1): flags.append('PVAF')
    if raw & 1:         flags.append('CVRF')
    result = 'Mask/Enable 0x%04X' % raw
    if fields:
        result += ' alerts=%s' % '+'.join(fields)
    if flags:
        result += ' flags=%s' % ','.join(flags)
    return result


def _decode_reg(reg, raw):
    name = REGS.get(reg, 'Reg[0x%02X]' % reg)
    if reg == 0x00:
        return _decode_config(raw)
    elif reg in SHUNT_REGS:
        return _decode_shunt_reg(name, raw)
    elif reg in BUS_REGS:
        return _decode_bus_reg(name, raw)
    elif reg == 0x0F:
        return _decode_mask_enable(raw)
    elif reg == 0xFE:
        ok = ' (TI ✓)' if raw == 0x5449 else ' (expected 0x5449!)'
        return 'Manufacturer ID 0x%04X%s' % (raw, ok)
    elif reg == 0xFF:
        ok = ' (INA3221 ✓)' if raw == 0x3220 else ' (expected 0x3220!)'
        return 'Die ID 0x%04X%s' % (raw, ok)
    return '%s 0x%04X' % (name, raw)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'ina3221'
    name = 'INA3221'
    longname = 'INA3221 3-channel 26V power monitor'
    desc = 'Decode INA3221 I2C 3-channel current/voltage monitor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ina3221']
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

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg  = self.reg_ptr
        name = REGS.get(reg, 'Reg[0x%02X]' % reg) if reg is not None else '?'

        if self.is_read:
            if len(self.databuf) == 2:
                raw = (self.databuf[0] << 8) | self.databuf[1]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ,
                          ['Read %s: %s' % (name, _decode_reg(reg, raw)),
                           'R %s 0x%04X' % (name, raw)]])
            elif self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d for %s' % (len(self.databuf), name))
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_PTR_WRITE,
                          ['Pointer → %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg]])
            elif len(self.databuf) == 2:
                raw = (self.databuf[0] << 8) | self.databuf[1]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE,
                          ['Write %s: %s' % (name, _decode_reg(reg, raw)),
                           'W %s 0x%04X' % (name, raw)]])
            else:
                self._warn(self.ss_block, self.es,
                           'Unexpected write length %d for %s' % (len(self.databuf), name))

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
