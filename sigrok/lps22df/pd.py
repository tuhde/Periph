import sigrokdecode as srd

ADDRS = {0x5C, 0x5D}

REGS = {
    0x0B: 'interrupt_cfg',
    0x0C: 'ths_p_l',
    0x0D: 'ths_p_h',
    0x0E: 'if_ctrl',
    0x0F: 'who_am_i',
    0x10: 'ctrl_reg1',
    0x11: 'ctrl_reg2',
    0x12: 'ctrl_reg3',
    0x13: 'ctrl_reg4',
    0x14: 'fifo_ctrl',
    0x15: 'fifo_wtm',
    0x16: 'ref_p_l',
    0x17: 'ref_p_h',
    0x1A: 'rpds_l',
    0x1B: 'rpds_h',
    0x24: 'int_source',
    0x25: 'fifo_status1',
    0x26: 'fifo_status2',
    0x27: 'status',
    0x28: 'press_xl',
    0x29: 'press_l',
    0x2A: 'press_h',
    0x2B: 'temp_l',
    0x2C: 'temp_h',
    0x78: 'fifo_press_xl',
    0x79: 'fifo_press_l',
    0x7A: 'fifo_press_h',
}

ODR_NAMES = {
    0x00: 'power-down',
    0x01: '1 Hz',
    0x02: '4 Hz',
    0x03: '10 Hz',
    0x04: '25 Hz',
    0x05: '50 Hz',
    0x06: '75 Hz',
    0x07: '100 Hz',
    0x08: '200 Hz',
    0x09: '200 Hz',
    0x0A: '200 Hz',
    0x0B: '200 Hz',
    0x0C: '200 Hz',
    0x0D: '200 Hz',
    0x0E: '200 Hz',
    0x0F: '200 Hz',
}
AVG_NAMES = {
    0x00: '4 samples',
    0x01: '8 samples',
    0x02: '16 samples',
    0x03: '32 samples',
    0x04: '64 samples',
    0x05: '128 samples',
    0x06: '128 samples',
    0x07: '512 samples',
}
STATUS_NAMES = {
    0x01: 'P_DA',
    0x02: 'T_DA',
    0x04: '—',
    0x08: '—',
    0x10: 'P_OR',
    0x20: 'T_OR',
}
INT_SOURCE_NAMES = {
    0x01: 'PH',
    0x02: 'PL',
    0x04: 'IA',
    0x80: 'BOOT_ON',
}

ANN_REG_WRITE    = 0
ANN_REG_READ     = 1
ANN_PRESS_READ   = 2
ANN_TEMP_READ    = 3
ANN_FIFO_COUNT   = 4
ANN_FIFO_READ    = 5
ANN_WARNING      = 6


class Decoder(srd.Decoder):
    api_version = 3
    id = 'lps22df'
    name = 'LPS22DF'
    longname = 'STMicroelectronics LPS22DF absolute pressure sensor'
    desc = 'Decode LPS22DF I²C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['lps22df']
    tags = ['IC', 'Sensor', 'Pressure']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('press-read','Pressure burst read'),
        ('temp-read', 'Temperature burst read'),
        ('fifo-count','FIFO sample count'),
        ('fifo-read', 'FIFO pressure burst read'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PRESS_READ, ANN_TEMP_READ, ANN_FIFO_COUNT, ANN_FIFO_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.databuf = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf = []
            self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.state = 'GET_DATA_READ' if self.is_read else 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = pdata
                self.databuf = []
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []

    def _finish_transaction(self):
        if self.reg_ptr is None:
            return

        # Burst pressure read 0x28-0x2A (3 bytes signed -> Pa).
        if self.is_read and self.reg_ptr == 0x28 and len(self.databuf) >= 3:
            raw = self.databuf[0] | (self.databuf[1] << 8) | (self.databuf[2] << 16)
            if raw & 0x800000:
                raw -= 0x1000000
            hpa = raw / 4096.0
            pa = hpa * 100.0
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_PRESS_READ, ['Pressure: %.2f Pa (raw 0x%06X, %.4f hPa)'
                                       % (pa, raw & 0xFFFFFF, hpa),
                                       'P: %.2f Pa' % pa,
                                       'P']])
            self.reg_ptr = None
            return

        # Burst temperature read 0x2B-0x2C (2 bytes signed -> °C).
        if self.is_read and self.reg_ptr == 0x2B and len(self.databuf) >= 2:
            raw = self.databuf[0] | (self.databuf[1] << 8)
            if raw & 0x8000:
                raw -= 0x10000
            c = raw / 100.0
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_TEMP_READ, ['Temperature: %.2f °C (raw 0x%04X)' % (c, raw & 0xFFFF),
                                       'T: %.2f °C' % c,
                                       'T']])
            self.reg_ptr = None
            return

        # FIFO sample count read 0x25.
        if self.is_read and self.reg_ptr == 0x25 and len(self.databuf) >= 1:
            count = self.databuf[0]
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_FIFO_COUNT, ['FIFO sample count: %u' % count,
                                       'count=%u' % count,
                                       'C=%u' % count]])
            self.reg_ptr = None
            return

        # FIFO pressure burst 0x78-0x7A (3 bytes per sample).
        if self.is_read and self.reg_ptr == 0x78 and len(self.databuf) >= 3:
            count = len(self.databuf) // 3
            samples = []
            for i in range(count):
                base = i * 3
                raw = self.databuf[base] | (self.databuf[base + 1] << 8) | (self.databuf[base + 2] << 16)
                if raw & 0x800000:
                    raw -= 0x1000000
                samples.append((raw / 4096.0) * 100.0)
            summary = 'FIFO: %u samples, ' % count + ', '.join('%.0f Pa' % s for s in samples)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_FIFO_READ, [summary, 'FIFO x%u' % count, 'FIFO']])
            self.reg_ptr = None
            return

        # Generic register read.
        if self.is_read:
            name = REGS.get(self.reg_ptr, 'reg 0x%02X' % self.reg_ptr)
            value = self.databuf[0] if self.databuf else 0
            extra = ''
            if self.reg_ptr == 0x0F:
                if value == 0xB4:
                    extra = ' (LPS22DF)'
                else:
                    extra = ' (unexpected, expected 0xB4)'
            elif self.reg_ptr == 0x27:
                extra = ' [' + ', '.join(n for bit, n in STATUS_NAMES.items() if value & bit) + ']'
            elif self.reg_ptr == 0x24:
                extra = ' [' + ', '.join(n for bit, n in INT_SOURCE_NAMES.items() if value & bit) + ']'
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_REG_READ, ['%s: 0x%02X%s' % (name, value, extra),
                                       '%s=0x%02X' % (name, value),
                                       name]])
            self.reg_ptr = None
            return

        # Register write.
        name = REGS.get(self.reg_ptr, 'reg 0x%02X' % self.reg_ptr)
        value = self.databuf[0] if self.databuf else 0
        extra = ''
        if self.reg_ptr == 0x10:
            odr = (value >> 3) & 0x0F
            avg = value & 0x07
            extra = ' [ODR=%s, AVG=%s]' % (ODR_NAMES.get(odr, '0x%X' % odr),
                                            AVG_NAMES.get(avg, '0x%X' % avg))
        elif self.reg_ptr == 0x11:
            flags = []
            if value & 0x80: flags.append('BOOT')
            if value & 0x20: flags.append('LFPF_CFG')
            if value & 0x10: flags.append('EN_LPFP')
            if value & 0x08: flags.append('BDU')
            if value & 0x04: flags.append('SWRESET')
            if value & 0x01: flags.append('ONESHOT')
            extra = ' [' + ', '.join(flags) + ']'
        self.put(self.ss_block, self.es, self.out_ann,
                 [ANN_REG_WRITE, ['%s <- 0x%02X%s' % (name, value, extra),
                                    '%s=0x%02X' % (name, value),
                                    name]])
        self.reg_ptr = None