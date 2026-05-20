import sigrokdecode as srd

ADDRS = set(range(0x40, 0x50))

REGS = {
    0x00: 'Configuration',
    0x01: 'Shunt Voltage',
    0x02: 'Bus Voltage',
    0x03: 'Power',
    0x04: 'Current',
    0x05: 'Calibration',
    0x06: 'Mask/Enable',
    0x07: 'Alert Limit',
    0xFE: 'Manufacturer ID',
    0xFF: 'Die ID',
}

AVG = {0: '1', 1: '4', 2: '16', 3: '64', 4: '128', 5: '256', 6: '512', 7: '1024'}

CT = {
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
    rst    = (raw >> 15) & 1
    avg    = (raw >> 9) & 7
    vbusct = (raw >> 6) & 7
    vshct  = (raw >> 3) & 7
    mode   = raw & 7
    parts = [
        'Config 0x%04X' % raw,
        'RST=1' if rst else None,
        'AVG=%s' % AVG.get(avg, str(avg)),
        'VBUSCT=%s' % CT.get(vbusct, str(vbusct)),
        'VSHCT=%s' % CT.get(vshct, str(vshct)),
        'MODE=%s' % MODE.get(mode, str(mode)),
    ]
    return ', '.join(p for p in parts if p is not None)


def _decode_mask_enable(raw):
    alert_funcs = []
    if raw & (1 << 15): alert_funcs.append('SOL')
    if raw & (1 << 14): alert_funcs.append('SUL')
    if raw & (1 << 13): alert_funcs.append('BOL')
    if raw & (1 << 12): alert_funcs.append('BUL')
    if raw & (1 << 11): alert_funcs.append('POL')
    if raw & (1 << 10): alert_funcs.append('CNVR')
    flags = []
    if raw & (1 << 4): flags.append('AFF')
    if raw & (1 << 3): flags.append('CVRF')
    if raw & (1 << 2): flags.append('OVF')
    apol = 'active-high' if (raw & 2) else 'active-low'
    latch = 'latched' if (raw & 1) else 'transparent'
    parts = ['Mask/Enable 0x%04X' % raw]
    if alert_funcs:
        parts.append('alert=%s' % '+'.join(alert_funcs))
    if flags:
        parts.append(' '.join(flags))
    parts.extend([apol, latch])
    return ', '.join(parts)


def _decode_reg(reg, raw):
    if reg == 0x00:
        return _decode_config(raw)
    elif reg == 0x01:
        signed = raw if raw < 0x8000 else raw - 0x10000
        uv = signed * 2.5
        return 'Shunt 0x%04X = %+.1f µV (%+.4f mV)' % (raw, uv, uv / 1000.0)
    elif reg == 0x02:
        mv = raw * 1.25
        return 'Bus 0x%04X = %.2f mV (%.4f V)' % (raw, mv, mv / 1000.0)
    elif reg == 0x03:
        return 'Power 0x%04X (×25×Current_LSB W)' % raw
    elif reg == 0x04:
        signed = raw if raw < 0x8000 else raw - 0x10000
        return 'Current 0x%04X = %+d (×Current_LSB A)' % (raw, signed)
    elif reg == 0x05:
        return 'Calibration 0x%04X' % raw
    elif reg == 0x06:
        return _decode_mask_enable(raw)
    elif reg == 0x07:
        return 'Alert Limit 0x%04X' % raw
    elif reg == 0xFE:
        ok = ' (TI ✓)' if raw == 0x5449 else ' (expected 0x5449!)'
        return 'Manufacturer ID 0x%04X%s' % (raw, ok)
    elif reg == 0xFF:
        ok = ' (INA226 ✓)' if raw == 0x2260 else ' (expected 0x2260!)'
        return 'Die ID 0x%04X%s' % (raw, ok)
    return 'Reg[0x%02X] 0x%04X' % (reg, raw)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'ina226'
    name = 'INA226'
    longname = 'INA226 36V power monitor'
    desc = 'Decode INA226 I2C current/voltage/power monitor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ina226']
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

    def decode(self):
        while True:
            ptype, pdata = self.wait()

            if ptype in ('START', 'START REPEAT'):
                if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                    pass  # pointer already set; don't reset databuf
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
                self._finish_transaction()
                self.state   = 'IDLE'
                self.databuf = []
