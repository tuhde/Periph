import sigrokdecode as srd

ADDR = 0x38

# 8/16/24-bit register addresses, from the spec's Register Map.
REGS = {
    0x000: 'SAGCYC',
    0x001: 'DISNOLOAD',
    0x004: 'LCYCMODE',
    0x007: 'PGA_V',
    0x008: 'PGA_IA',
    0x009: 'PGA_IB',
    0x040: 'WRITE_PROTECT',
    0x0FD: 'LAST_OP',
    0x0FF: 'LAST_RWDATA',
    0x100: 'ZXTOUT',
    0x101: 'LINECYC',
    0x102: 'CONFIG',
    0x103: 'CF1DEN',
    0x104: 'CF2DEN',
    0x107: 'CFMODE',
    0x108: 'PHCALA',
    0x109: 'PHCALB',
    0x10A: 'PFA',
    0x10B: 'PFB',
    0x10C: 'ANGLE_A',
    0x10D: 'ANGLE_B',
    0x10E: 'Period',
    0x110: 'ALT_OUTPUT',
    0x120: 'INTERNAL_RES',
    0x1FE: 'LAST_ADD',
    0x1FF: 'LAST_RWDATA',
    0x200: 'SAGLVL',
    0x201: 'ACCMODE',
    0x203: 'AP_NOLOAD',
    0x204: 'VAR_NOLOAD',
    0x205: 'VA_NOLOAD',
    0x210: 'AVA',
    0x211: 'BVA',
    0x212: 'AWATT',
    0x213: 'BWATT',
    0x214: 'AVAR',
    0x215: 'BVAR',
    0x216: 'IA',
    0x217: 'IB',
    0x218: 'V',
    0x21A: 'IRMSA',
    0x21B: 'IRMSB',
    0x21C: 'VRMS',
    0x21E: 'AENERGYA',
    0x21F: 'AENERGYB',
    0x220: 'RENERGYA',
    0x221: 'RENERGYB',
    0x222: 'APENERGYA',
    0x223: 'APENERGYB',
    0x224: 'OVLVL',
    0x225: 'OILVL',
    0x226: 'VPEAK',
    0x227: 'RSTVPEAK',
    0x228: 'IAPEAK',
    0x229: 'RSTIAPEAK',
    0x22A: 'IBPEAK',
    0x22B: 'RSTIBPEAK',
    0x22C: 'IRQENA',
    0x22D: 'IRQSTATA',
    0x22E: 'RSTIRQSTATA',
    0x22F: 'IRQENB',
    0x230: 'IRQSTATB',
    0x231: 'RSTIRQSTATB',
    0x280: 'AIGAIN',
    0x281: 'AVGAIN',
    0x282: 'AWGAIN',
    0x283: 'AVARGAIN',
    0x284: 'AVAGAIN',
    0x286: 'AIRMSOS',
    0x288: 'VRMSOS',
    0x289: 'AWATTOS',
    0x28A: 'AVAROS',
    0x28B: 'AVAOS',
    0x28C: 'BIGAIN',
    0x28E: 'BWGAIN',
    0x28F: 'BVARGAIN',
    0x290: 'BVAGAIN',
    0x292: 'BIRMSOS',
    0x295: 'BWATTOS',
    0x296: 'BVAROS',
    0x297: 'BVAOS',
    0x2FF: 'LAST_RWDATA',
    0x37F: 'CRC',
    0x702: 'Version',
    0x800: 'EX_REF',
}

# Width in bytes per register (default = 1).
WIDTH = {0x120: 2}
for _reg in range(0x100, 0x200):
    WIDTH[_reg] = 2
for _reg in range(0x200, 0x300):
    WIDTH[_reg] = 3

# Engineering-unit constants (gain-independent).
PF_LSB = 1.0 / 32768.0
ANGLE_LSB = 1.0 / 223750.0

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3


def _decode_pf(raw):
    return 'PF=%.4f' % (raw * PF_LSB)


def _decode_angle(raw):
    return 'angle=%d LSB' % raw


def _decode_period(raw):
    seconds = (raw + 1) * ANGLE_LSB
    return 'Period=%d LSB (T=%.6f s)' % (raw, seconds)


def _decode_reg_value(reg, raw_bytes):
    """Return a list of (long, medium, short) annotation strings for a
    register read or write, decoding engineering units where applicable.
    """
    if reg == 0x10A or reg == 0x10B:
        raw = (raw_bytes[0] << 8) | raw_bytes[1]
        if raw & 0x8000:
            raw -= 0x10000
        return _decode_pf(raw), '0x%04X' % (raw & 0xFFFF), '%d' % raw
    if reg == 0x10C or reg == 0x10D:
        raw = (raw_bytes[0] << 8) | raw_bytes[1]
        if raw & 0x8000:
            raw -= 0x10000
        return _decode_angle(raw), '0x%04X' % (raw & 0xFFFF), '%d' % raw
    if reg == 0x10E:
        raw = (raw_bytes[0] << 8) | raw_bytes[1]
        return _decode_period(raw), '0x%04X' % raw, '%d' % raw
    if reg in (0x21A, 0x21B, 0x21C):
        raw = (raw_bytes[0] << 16) | (raw_bytes[1] << 8) | raw_bytes[2]
        return ('%s raw=0x%06X (%d)' % (REGS[reg], raw, raw),
                '%s 0x%06X' % (REGS[reg], raw),
                '%d' % raw)
    if reg in (0x212, 0x213, 0x214, 0x215, 0x210, 0x211):
        raw = (raw_bytes[0] << 16) | (raw_bytes[1] << 8) | raw_bytes[2]
        if raw & 0x800000:
            raw -= 0x1000000
        return ('%s signed=0x%06X (%d)' % (REGS[reg], raw & 0xFFFFFF, raw),
                '%s 0x%06X' % (REGS[reg], raw & 0xFFFFFF),
                '%d' % raw)
    if WIDTH.get(reg, 1) == 3:
        raw = (raw_bytes[0] << 16) | (raw_bytes[1] << 8) | raw_bytes[2]
        return ('%s raw=0x%06X (%d)' % (REGS.get(reg, 'Reg[0x%03X]' % reg), raw, raw),
                '%s 0x%06X' % (REGS.get(reg, 'Reg[0x%03X]' % reg), raw),
                '%d' % raw)
    if WIDTH.get(reg, 1) == 2:
        raw = (raw_bytes[0] << 8) | raw_bytes[1]
        return ('%s=0x%04X (%d)' % (REGS.get(reg, 'Reg[0x%03X]' % reg), raw, raw),
                '%s 0x%04X' % (REGS.get(reg, 'Reg[0x%03X]' % reg), raw),
                '%d' % raw)
    raw = raw_bytes[0]
    return ('%s=0x%02X (%d)' % (REGS.get(reg, 'Reg[0x%03X]' % reg), raw, raw),
            '%s 0x%02X' % (REGS.get(reg, 'Reg[0x%03X]' % reg), raw),
            '%d' % raw)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'ade7953'
    name = 'ADE7953'
    longname = 'Analog Devices ADE7953 single-phase metering IC'
    desc = 'Decode ADE7953 I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ade7953']
    tags = ['IC', 'Sensor', 'Power']

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
        if self.reg_ptr is None:
            return
        reg = self.reg_ptr
        name = REGS.get(reg, 'Reg[0x%03X]' % reg)
        width = WIDTH.get(reg, 1)
        if self.is_read:
            if not self.databuf:
                return
            if len(self.databuf) != width:
                self._warn(self.ss_block, self.es,
                           '%s read expected %d bytes, got %d' % (name, width, len(self.databuf)))
                self.databuf = []
                return
            long_, med, short = _decode_reg_value(reg, self.databuf)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_REG_READ,
                      ['Read %s: %s' % (name, long_),
                       'R %s %s' % (name, med),
                       short]])
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_PTR_WRITE,
                          ['Pointer → %s (0x%03X)' % (name, reg),
                           'PTR 0x%03X' % reg,
                           '0x%03X' % reg]])
                return
            if len(self.databuf) < width:
                self._warn(self.ss_block, self.es,
                           '%s write expected %d bytes, got %d' % (name, width, len(self.databuf)))
                return
            raw_bytes = self.databuf[:width]
            long_, med, short = _decode_reg_value(reg, raw_bytes)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['Write %s: %s' % (name, long_),
                       'W %s %s' % (name, med),
                       short]])
        self.databuf = []

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
            if addr != ADDR:
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
                if len(self.databuf) == 0:
                    self.reg_ptr_hi = byte
                    self.databuf.append(byte)
                else:
                    self.reg_ptr = (self.reg_ptr_hi << 8) | byte
                    self.databuf = []
                    self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state    = 'IDLE'
            self.reg_ptr  = None
            self.databuf  = []