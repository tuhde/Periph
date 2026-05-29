import sigrokdecode as srd

ADDRS = {0x39}

REGS = {
    0x80: 'ENABLE',
    0x81: 'ATIME',
    0x83: 'WTIME',
    0x84: 'AILTL',
    0x85: 'AILTH',
    0x86: 'AIHTL',
    0x87: 'AIHTH',
    0x89: 'PILT',
    0x8B: 'PIHT',
    0x8C: 'PERS',
    0x8D: 'CONFIG1',
    0x8E: 'PPULSE',
    0x8F: 'CONTROL',
    0x90: 'CONFIG2',
    0x92: 'ID',
    0x93: 'STATUS',
    0x94: 'CDATAL',
    0x95: 'CDATAH',
    0x96: 'RDATAL',
    0x97: 'RDATAH',
    0x98: 'GDATAL',
    0x99: 'GDATAH',
    0x9A: 'BDATAL',
    0x9B: 'BDATAH',
    0x9C: 'PDATA',
    0x9D: 'POFFSET_UR',
    0x9E: 'POFFSET_DL',
    0x9F: 'CONFIG3',
    0xA0: 'GPENTH',
    0xA1: 'GEXTH',
    0xA2: 'GCONF1',
    0xA3: 'GCONF2',
    0xA4: 'GOFFSET_U',
    0xA5: 'GOFFSET_D',
    0xA6: 'GPULSE',
    0xA7: 'GOFFSET_L',
    0xA9: 'GOFFSET_R',
    0xAA: 'GCONF3',
    0xAB: 'GCONF4',
    0xAE: 'GFLVL',
    0xAF: 'GSTATUS',
    0xE4: 'IFORCE',
    0xE5: 'PICLEAR',
    0xE6: 'CICLEAR',
    0xE7: 'AICLEAR',
    0xFC: 'GFIFO_U',
    0xFD: 'GFIFO_D',
    0xFE: 'GFIFO_L',
    0xFF: 'GFIFO_R',
}

CLEAR_REGS = {0xE4: 'IFORCE', 0xE5: 'PICLEAR', 0xE6: 'CICLEAR', 0xE7: 'AICLEAR'}

AGAIN = {0: '1x', 1: '4x', 2: '16x', 3: '64x'}
PGAIN = {0: '1x', 1: '2x', 2: '4x', 3: '8x'}
LDRIVE = {0: '100 mA', 1: '50 mA', 2: '25 mA', 3: '12.5 mA'}
PLEN = {0: '4 us', 1: '8 us', 2: '16 us', 3: '32 us'}
GWTIME = {0: '0 ms', 1: '2.8 ms', 2: '5.6 ms', 3: '8.4 ms',
          4: '14.0 ms', 5: '22.4 ms', 6: '30.8 ms', 7: '39.2 ms'}
GGAIN = {0: '1x', 1: '2x', 2: '4x', 3: '8x'}

ANN_WRITE   = 0
ANN_READ    = 1
ANN_WARNING = 2


def _decode_enable(raw):
    bits = []
    if raw & 0x40: bits.append('GEN')
    if raw & 0x20: bits.append('PIEN')
    if raw & 0x10: bits.append('AIEN')
    if raw & 0x08: bits.append('WEN')
    if raw & 0x04: bits.append('PEN')
    if raw & 0x02: bits.append('AEN')
    if raw & 0x01: bits.append('PON')
    return 'ENABLE 0x%02X [%s]' % (raw, ', '.join(bits) if bits else 'all off')


def _decode_atime(raw):
    cycles = 256 - raw
    ms = cycles * 2.78
    max_count = min(65535, cycles * 1025)
    return 'ATIME 0x%02X (%d cycles, %.1f ms, max %d)' % (raw, cycles, ms, max_count)


def _decode_control(raw):
    ldrive = (raw >> 6) & 3
    pgain = (raw >> 2) & 3
    again = raw & 3
    return 'CONTROL 0x%02X (LDRIVE=%s, PGAIN=%s, AGAIN=%s)' % (
        raw, LDRIVE.get(ldrive, str(ldrive)), PGAIN.get(pgain, str(pgain)), AGAIN.get(again, str(again)))


def _decode_status(raw):
    flags = []
    if raw & 0x80: flags.append('CPSAT')
    if raw & 0x40: flags.append('PGSAT')
    if raw & 0x20: flags.append('PINT')
    if raw & 0x10: flags.append('AINT')
    if raw & 0x04: flags.append('GINT')
    if raw & 0x02: flags.append('PVALID')
    if raw & 0x01: flags.append('AVALID')
    return 'STATUS 0x%02X [%s]' % (raw, ', '.join(flags) if flags else 'none')


def _decode_ppulse(raw, name='PPULSE'):
    plen = (raw >> 6) & 3
    count = (raw & 0x3F) + 1
    return '%s 0x%02X (%d pulses, %s)' % (name, raw, count, PLEN.get(plen, str(plen)))


def _decode_config1(raw):
    wlong = (raw >> 1) & 1
    return 'CONFIG1 0x%02X (WLONG=%d, wait x%d)' % (raw, wlong, 12 if wlong else 1)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'apds9960'
    name = 'APDS-9960'
    longname = 'APDS-9960 proximity/ambient light/RGB/gesture sensor'
    desc = 'Decode APDS-9960 I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['apds9960']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
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
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ'):
            return

        reg  = self.reg_ptr
        name = REGS.get(reg, 'Reg[0x%02X]' % reg) if reg is not None else '?'

        if self.is_read:
            if reg == 0x94 and len(self.databuf) == 8:
                c = self.databuf[0] | (self.databuf[1] << 8)
                r = self.databuf[2] | (self.databuf[3] << 8)
                g = self.databuf[4] | (self.databuf[5] << 8)
                b = self.databuf[6] | (self.databuf[7] << 8)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['RGBC burst: C=%d R=%d G=%d B=%d' % (c, r, g, b),
                           'C=%d R=%d G=%d B=%d' % (c, r, g, b)]])
            elif reg == 0xFC and len(self.databuf) >= 4 and len(self.databuf) % 4 == 0:
                datasets = len(self.databuf) // 4
                parts = []
                for i in range(datasets):
                    u = self.databuf[i*4]
                    d = self.databuf[i*4+1]
                    l = self.databuf[i*4+2]
                    r = self.databuf[i*4+3]
                    parts.append('(U=%d,D=%d,L=%d,R=%d)' % (u, d, l, r))
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['GFIFO %d datasets: %s' % (datasets, ' '.join(parts)),
                           'GFIFO %d' % datasets]])
            elif len(self.databuf) == 1:
                val = self.databuf[0]
                if reg == 0x93:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_READ, [_decode_status(val), 'STATUS 0x%02X' % val]])
                elif reg == 0x9C:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_READ, ['PDATA %d' % val, 'P=%d' % val]])
                elif reg == 0xAE:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_READ, ['GFLVL %d datasets' % val, 'GFLVL=%d' % val]])
                elif reg == 0x92:
                    ok = ' (APDS-9960)' if val == 0xAB else ' (expected 0xAB!)'
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_READ, ['ID 0x%02X%s' % (val, ok), 'ID=0x%02X' % val]])
                else:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_READ, ['Read %s: 0x%02X' % (name, val), 'R %s 0x%02X' % (name, val)]])
            elif len(self.databuf) == 2:
                val = self.databuf[0] | (self.databuf[1] << 8)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, ['Read %s: 0x%04X' % (name, val), 'R %s 0x%04X' % (name, val)]])
            elif self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d for %s' % (len(self.databuf), name))
        else:
            if not self.databuf:
                if reg in CLEAR_REGS:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WRITE,
                              ['Clear %s (address-only)' % CLEAR_REGS[reg],
                               'CLR %s' % CLEAR_REGS[reg]]])
                else:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WRITE,
                              ['Pointer -> %s (0x%02X)' % (name, reg),
                               'PTR 0x%02X' % reg]])
            elif len(self.databuf) == 1:
                val = self.databuf[0]
                if reg == 0x80:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WRITE, [_decode_enable(val), 'ENABLE 0x%02X' % val]])
                elif reg == 0x81:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WRITE, [_decode_atime(val), 'ATIME 0x%02X' % val]])
                elif reg == 0x8F:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WRITE, [_decode_control(val), 'CTRL 0x%02X' % val]])
                elif reg == 0x8E or reg == 0xA6:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WRITE, [_decode_ppulse(val, name), '%s 0x%02X' % (name, val)]])
                elif reg == 0x8D:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WRITE, [_decode_config1(val), 'CFG1 0x%02X' % val]])
                else:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WRITE, ['Write %s: 0x%02X' % (name, val), 'W %s 0x%02X' % (name, val)]])
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
            self.reg_ptr = None
            self.databuf = []
