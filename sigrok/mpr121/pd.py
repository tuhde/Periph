"""MPR121 sigrok protocol decoder.

Sits on top of the `i2c` sigrok decoder and translates raw I²C transactions
into MPR121 register names and decoded field values. The MPR121 uses
plain register addressing (no command byte prefix); the register map
spans addresses 0x00..0x80. Auto-increment wraps within the filtered-data
block (0x04-0x1D) and within the per-electrode charge-time block
(0x6C-0x72).
"""

import sigrokdecode as srd

ADDRS = {0x5A, 0x5B, 0x5C, 0x5D}

REGS = {
    0x00: 'ELE0_7_TOUCH',
    0x01: 'ELE8_PROX_TOUCH',
    0x02: 'ELE0_7_OOR',
    0x03: 'ELE8_PROX_OOR',
    0x04: 'EFD0L', 0x05: 'EFD0H',
    0x06: 'EFD1L', 0x07: 'EFD1H',
    0x08: 'EFD2L', 0x09: 'EFD2H',
    0x0A: 'EFD3L', 0x0B: 'EFD3H',
    0x0C: 'EFD4L', 0x0D: 'EFD4H',
    0x0E: 'EFD5L', 0x0F: 'EFD5H',
    0x10: 'EFD6L', 0x11: 'EFD6H',
    0x12: 'EFD7L', 0x13: 'EFD7H',
    0x14: 'EFD8L', 0x15: 'EFD8H',
    0x16: 'EFD9L', 0x17: 'EFD9H',
    0x18: 'EFD10L', 0x19: 'EFD10H',
    0x1A: 'EFD11L', 0x1B: 'EFD11H',
    0x1C: 'EFDPROXL', 0x1D: 'EFDPROXH',
    0x1E: 'E0BV', 0x1F: 'E1BV', 0x20: 'E2BV', 0x21: 'E3BV',
    0x22: 'E4BV', 0x23: 'E5BV', 0x24: 'E6BV', 0x25: 'E7BV',
    0x26: 'E8BV', 0x27: 'E9BV', 0x28: 'E10BV', 0x29: 'E11BV',
    0x2A: 'EPROXBV',
    0x2B: 'MHDR', 0x2C: 'NHDR', 0x2D: 'NCLR', 0x2E: 'FDLR',
    0x2F: 'MHDF', 0x30: 'NHDF', 0x31: 'NCLF', 0x32: 'FDLF',
    0x33: 'NHDT', 0x34: 'NCLT', 0x35: 'FDLT',
    0x36: 'EPROXMHDR', 0x37: 'EPROXNHDR', 0x38: 'EPROXNCLR',
    0x39: 'EPROXFDLR', 0x3A: 'EPROXMHDF', 0x3B: 'EPROXNHDF',
    0x3C: 'EPROXNCLF', 0x3D: 'EPROXFDFL',
    0x3E: 'EPROXNHDT', 0x3F: 'EPROXNCLT', 0x40: 'EPROXFDLT',
    0x41: 'E0TTH', 0x42: 'E0RTH',
    0x43: 'E1TTH', 0x44: 'E1RTH',
    0x45: 'E2TTH', 0x46: 'E2RTH',
    0x47: 'E3TTH', 0x48: 'E3RTH',
    0x49: 'E4TTH', 0x4A: 'E4RTH',
    0x4B: 'E5TTH', 0x4C: 'E5RTH',
    0x4D: 'E6TTH', 0x4E: 'E6RTH',
    0x4F: 'E7TTH', 0x50: 'E7RTH',
    0x51: 'E8TTH', 0x52: 'E8RTH',
    0x53: 'E9TTH', 0x54: 'E9RTH',
    0x55: 'E10TTH', 0x56: 'E10RTH',
    0x57: 'E11TTH', 0x58: 'E11RTH',
    0x59: 'EPROXTTH', 0x5A: 'EPROXRTH',
    0x5B: 'DEBOUNCE',
    0x5C: 'CDC_CONFIG', 0x5D: 'CDT_CONFIG',
    0x5E: 'ECR',
    0x5F: 'CDC0', 0x60: 'CDC1', 0x61: 'CDC2', 0x62: 'CDC3',
    0x63: 'CDC4', 0x64: 'CDC5', 0x65: 'CDC6', 0x66: 'CDC7',
    0x67: 'CDC8', 0x68: 'CDC9', 0x69: 'CDC10', 0x6A: 'CDC11',
    0x6B: 'CDCPROX',
    0x6C: 'CDT0_HI', 0x6D: 'CDT0_LO',
    0x6E: 'CDT1_HI', 0x6F: 'CDT1_LO',
    0x70: 'CDT2_HI', 0x71: 'CDT2_LO',
    0x72: 'CDT3_HI',
    0x73: 'GPIO_CTL0', 0x74: 'GPIO_CTL1', 0x75: 'GPIO_DAT',
    0x76: 'GPIO_DIR', 0x77: 'GPIO_EN',
    0x78: 'GPIO_SET', 0x79: 'GPIO_CLR', 0x7A: 'GPIO_TOG',
    0x7B: 'AUTOCONFIG0', 0x7C: 'AUTOCONFIG1',
    0x7D: 'USL', 0x7E: 'LSL', 0x7F: 'TL',
    0x80: 'SRST',
}

ANN_WRITE   = 0
ANN_READ    = 1
ANN_WARNING = 2

# Conformance annotation pairs. Each pair is named to match the spec's
# Sigrok Decoder section and the `specs/other/mpr121_timing.conf` keys
# verbatim — the conformance checker reads these timestamps directly from
# the decoded capture.
#
# - soft_reset_start / soft_reset_done — `soft_reset_ready` conformance
#   check (≥ 1 ms between write of 0x63 to register 0x80 and the first
#   START condition issued by the host to the chip).
# - autoconfig_start / autoconfig_done — `autoconfig_ready` conformance
#   check (≤ 50 ms practical safe wait after ECR write with ELE_EN>0).
ANN_SOFT_RESET_START = 3
ANN_SOFT_RESET_DONE  = 4
ANN_AUTOCONFIG_START = 5
ANN_AUTOCONFIG_DONE  = 6


def _decode_touch_status_0(raw):
    bits = []
    for n in range(8):
        if raw & (1 << n):
            bits.append('ELE%d:touch' % n)
        else:
            bits.append('ELE%d:release' % n)
    return 'ELE0_7_TOUCH 0x%02X [%s]' % (raw, ', '.join(bits))


def _decode_touch_status_1(raw):
    bits = []
    if raw & 0x80: bits.append('OVCF')
    if raw & 0x10: bits.append('ELEPROX:touch')
    for n in range(4):
        if raw & (1 << n):
            bits.append('ELE%d:touch' % (8 + n))
    if not bits:
        bits.append('all off')
    return 'ELE8_PROX_TOUCH 0x%02X [%s]' % (raw, ', '.join(bits))


def _decode_ecr(raw):
    cl = (raw >> 6) & 0x03
    eleprox = (raw >> 4) & 0x03
    n_electrodes = raw & 0x0F
    if raw == 0x00:
        return 'ECR 0x00 (Stop Mode)'
    cl_str = {0: 'baseline reg', 1: 'tracking off',
              2: 'first 5 MSBs', 3: 'all 10 bits'}.get(cl, str(cl))
    prox_str = {0: 'off', 1: 'ELE0-1', 2: 'ELE0-3', 3: 'ELE0-11 summed'}.get(eleprox, str(eleprox))
    n_str = 'all 12' if n_electrodes >= 12 else 'ELE0..%d' % max(n_electrodes - 1, 0)
    return 'ECR 0x%02X (Run Mode: CL=%d/%s, ELEPROX_EN=%d/%s, ELE_EN=%d/%s)' % (
        raw, cl, cl_str, eleprox, prox_str, n_electrodes, n_str)


def _decode_autoconfig0(raw):
    ffi = (raw >> 6) & 0x03
    retry = (raw >> 4) & 0x03
    bva = (raw >> 2) & 0x03
    bits = []
    if raw & 0x08: bits.append('ARE')
    if raw & 0x01: bits.append('ACE')
    return 'AUTOCONFIG0 0x%02X (FFI=%d, RETRY=%d, BVA=%d, %s)' % (
        raw, ffi, retry, bva, ', '.join(bits) if bits else 'no flags')


def _decode_autoconfig1(raw):
    bits = []
    if raw & 0x80: bits.append('SCTS')
    if raw & 0x04: bits.append('OORIE')
    if raw & 0x02: bits.append('ARFIE')
    if raw & 0x01: bits.append('ACFIE')
    return 'AUTOCONFIG1 0x%02X [%s]' % (raw, ', '.join(bits) if bits else 'all off')


def _decode_cdc_config(raw):
    ffi = (raw >> 6) & 0x03
    cdc = raw & 0x3F
    ffi_str = {0: '6', 1: '10', 2: '18', 3: '34'}.get(ffi, str(ffi))
    if cdc == 0:
        cdc_str = 'disabled'
    else:
        cdc_str = '%d uA' % cdc
    return 'CDC_CONFIG 0x%02X (FFI=%s samples, CDC=%s)' % (raw, ffi_str, cdc_str)


def _decode_cdt_config(raw):
    cdt = (raw >> 5) & 0x07
    sfi = (raw >> 2) & 0x03
    esi = raw & 0x07
    cdt_str = 'disabled' if cdt == 0 else '%.1f us' % (0.5 * (1 << (cdt - 1)))
    sfi_str = {0: '4', 1: '6', 2: '10', 3: '18'}.get(sfi, str(sfi))
    esi_ms = [1, 2, 4, 8, 16, 32, 64, 128][esi] if esi < 8 else 0
    return 'CDT_CONFIG 0x%02X (CDT=%s, SFI=%s, ESI=%d ms)' % (
        raw, cdt_str, sfi_str, esi_ms)


def _decode_debounce(raw):
    dr = (raw >> 4) & 0x07
    dt = raw & 0x07
    return 'DEBOUNCE 0x%02X (DR=%d release, DT=%d touch)' % (raw, dr, dt)


def _decode_srst(raw):
    if raw == 0x63:
        return 'SRST 0x%02X (Soft Reset)' % raw
    return 'SRST 0x%02X (unexpected key)' % raw


def _decode_threshold(reg_name, raw):
    return '%s 0x%02X (threshold=%d)' % (reg_name, raw, raw)


# Registers that, when written, trigger conformance annotations.
_DECODE_FNS = {
    'ELE0_7_TOUCH': _decode_touch_status_0,
    'ELE8_PROX_TOUCH': _decode_touch_status_1,
    'ECR': _decode_ecr,
    'AUTOCONFIG0': _decode_autoconfig0,
    'AUTOCONFIG1': _decode_autoconfig1,
    'CDC_CONFIG': _decode_cdc_config,
    'CDT_CONFIG': _decode_cdt_config,
    'DEBOUNCE': _decode_debounce,
    'SRST': _decode_srst,
}


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mpr121'
    name = 'MPR121'
    longname = 'Freescale MPR121 proximity capacitive touch sensor'
    desc = 'Decode MPR121 I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mpr121']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('warning',   'Warning'),
        ('soft-reset-start', 'Soft reset start'),
        ('soft-reset-done',  'Soft reset done'),
        ('autoconfig-start', 'Autoconfig start'),
        ('autoconfig-done',  'Autoconfig done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
        ('conformance', 'Conformance', (ANN_SOFT_RESET_START, ANN_SOFT_RESET_DONE,
                                         ANN_AUTOCONFIG_START, ANN_AUTOCONFIG_DONE)),
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
        self.last_ecr_write_ss = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf  = []
            self.is_read  = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.state   = 'GET_DATA_READ' if self.is_read else 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = pdata
                self.databuf = []
                self.state   = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []

    def _finish_transaction(self):
        if self.addr is None or self.reg_ptr is None or self.ss_block is None:
            return
        if self.is_read:
            self._emit_read()
        else:
            self._emit_write()
        self.ss_block = None

    def _emit_read(self):
        reg_name = REGS.get(self.reg_ptr, 'REG_0x%02X' % self.reg_ptr)
        if reg_name in ('EFD0L', 'EFD1L', 'EFD2L', 'EFD3L', 'EFD4L', 'EFD5L',
                        'EFD6L', 'EFD7L', 'EFD8L', 'EFD9L', 'EFD10L', 'EFD11L'):
            electrode = int(reg_name[3:-1])
            if len(self.databuf) == 2:
                lsb = self.databuf[0]
                msb = self.databuf[1] & 0x03
                raw10 = lsb | (msb << 8)
                self.put(self.ss_block, self.es, ANN_READ,
                         'EFD%d 0x%03X (electrode %d filtered = %d)' % (
                             electrode, raw10, electrode, raw10))
            elif len(self.databuf) == 1:
                self.put(self.ss_block, self.es, ANN_READ,
                         '%s 0x%02X' % (reg_name, self.databuf[0]))
            else:
                self.put(self.ss_block, self.es, ANN_READ,
                         '%s (%d bytes)' % (reg_name, len(self.databuf)))
        elif reg_name in ('EFDPROXL',):
            if len(self.databuf) == 2:
                lsb = self.databuf[0]
                msb = self.databuf[1] & 0x03
                raw10 = lsb | (msb << 8)
                self.put(self.ss_block, self.es, ANN_READ,
                         'EFDPROX 0x%03X (proximity filtered = %d)' % (raw10, raw10))
            elif len(self.databuf) == 1:
                self.put(self.ss_block, self.es, ANN_READ,
                         '%s 0x%02X' % (reg_name, self.databuf[0]))
            else:
                self.put(self.ss_block, self.es, ANN_READ,
                         '%s (%d bytes)' % (reg_name, len(self.databuf)))
        elif reg_name == 'ELE0_7_TOUCH' and len(self.databuf) >= 2:
            raw = self.databuf[0]
            self.put(self.ss_block, self.es, ANN_READ, _decode_touch_status_0(raw))
            if len(self.databuf) >= 2:
                self.put(self.ss_block, self.es, ANN_READ,
                         _decode_touch_status_1(self.databuf[1]))
        else:
            self.put(self.ss_block, self.es, ANN_READ,
                     '%s 0x%02X' % (reg_name, self.databuf[0] if self.databuf else 0))

    def _emit_write(self):
        reg_name = REGS.get(self.reg_ptr, 'REG_0x%02X' % self.reg_ptr)
        value = self.databuf[0] if self.databuf else 0
        if reg_name == 'ECR':
            self.last_ecr_write_ss = self.ss_block
            if value != 0:
                self.put(self.ss_block, self.es, ANN_AUTOCONFIG_START,
                         'autoconfig start (ECR=%#x)' % value)
        if reg_name == 'SRST' and value == 0x63:
            self.put(self.ss_block, self.es, ANN_SOFT_RESET_START,
                     'soft reset start (write 0x63 to 0x80)')
        if reg_name in _DECODE_FNS:
            self.put(self.ss_block, self.es, ANN_WRITE,
                     _DECODE_FNS[reg_name](value))
        elif reg_name.startswith('E') and reg_name.endswith('TTH'):
            electrode = int(reg_name[1:-3])
            self.put(self.ss_block, self.es, ANN_WRITE,
                     _decode_threshold(reg_name, value) +
                     ' [ELE%d touch threshold]' % electrode)
        elif reg_name.startswith('E') and reg_name.endswith('RTH'):
            electrode = int(reg_name[1:-3])
            self.put(self.ss_block, self.es, ANN_WRITE,
                     _decode_threshold(reg_name, value) +
                     ' [ELE%d release threshold]' % electrode)
        elif reg_name.startswith('E') and reg_name.endswith('BV') and len(reg_name) == 4:
            electrode = int(reg_name[1:-2])
            self.put(self.ss_block, self.es, ANN_WRITE,
                     '%s 0x%02X (baseline MSB; 10-bit baseline = %d)' % (
                         reg_name, value, value << 2))
        else:
            self.put(self.ss_block, self.es, ANN_WRITE,
                     '%s 0x%02X' % (reg_name, value))
