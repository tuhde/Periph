"""APDS-9930 sigrok protocol decoder.

Sits on top of the `i2c` sigrok decoder and translates raw I²C transactions
into APDS-9930 register names and decoded field values. The APDS-9930 uses
a command-register protocol: every bus transaction starts with a command
byte whose high bits are the type (0x80=write, 0xA0=auto-increment read,
0xE0=special) and whose low 5 bits are the register address. The base
register map occupies addresses 0x00-0x1E; 0xE0-0xE7 are special-function
codes (interrupt clears).
"""

import sigrokdecode as srd

ADDRS = {0x39}

REGS = {
    0x00: 'ENABLE',
    0x01: 'ATIME',
    0x02: 'PTIME',
    0x03: 'WTIME',
    0x04: 'AILTL',
    0x05: 'AILTH',
    0x06: 'AIHTL',
    0x07: 'AIHTH',
    0x08: 'PILTL',
    0x09: 'PILTH',
    0x0A: 'PIHTL',
    0x0B: 'PIHTH',
    0x0C: 'PERS',
    0x0D: 'CONFIG',
    0x0E: 'PPULSE',
    0x0F: 'CONTROL',
    0x12: 'ID',
    0x13: 'STATUS',
    0x14: 'CH0DATAL',
    0x15: 'CH0DATAH',
    0x16: 'CH1DATAL',
    0x17: 'CH1DATAH',
    0x18: 'PDATAL',
    0x19: 'PDATAH',
    0x1E: 'POFFSET',
}

SPECIAL_FN = {
    0x00: 'NOOP',
    0x05: 'CLEAR_PROXIMITY_INT',
    0x06: 'CLEAR_ALS_INT',
    0x07: 'CLEAR_BOTH_INT',
}

AGAIN = {0: '1x', 1: '8x', 2: '16x', 3: '120x'}
PGAIN = {0: '1x', 1: '2x', 2: '4x', 3: '8x'}
PDRIVE = {0: '100 mA', 1: '50 mA', 2: '25 mA', 3: '12.5 mA'}

ANN_WRITE   = 0
ANN_READ    = 1
ANN_WARNING = 2

# Conformance annotation pairs. Each pair is named to match the spec's
# Sigrok Decoder section and the `specs/light/apds-9930_timing.conf` keys
# verbatim — the conformance checker reads these timestamps directly from
# the decoded capture.
#
# - poweron_start / poweron_done — `poweron_ready` conformance check
#   (≥ 4.5 ms between VDD-stable and first START).
# - conversion_start / conversion_done — `first_conversion_ready` check
#   (≥ 12 ms between ENABLE write with PON=1 and STATUS read with both
#   AVALID and PVALID set).
# - als_integration_start / als_integration_done — `als_integration_time`
#   check (≈ 101 ms at ATIME=0xDB between AEN-set and AVALID=1).
# - proximity_integration_start / proximity_integration_done —
#   `proximity_integration_time` check (≈ 2.73 ms at PTIME=0xFF between
#   PEN-set and PVALID=1).
ANN_POWERON_START                   = 3
ANN_POWERON_DONE                    = 4
ANN_CONVERSION_START                = 5
ANN_CONVERSION_DONE                 = 6
ANN_ALS_INTEGRATION_START           = 7
ANN_ALS_INTEGRATION_DONE            = 8
ANN_PROXIMITY_INTEGRATION_START     = 9
ANN_PROXIMITY_INTEGRATION_DONE     = 10


def _decode_enable(raw):
    bits = []
    if raw & 0x40: bits.append('SAI')
    if raw & 0x20: bits.append('PIEN')
    if raw & 0x10: bits.append('AIEN')
    if raw & 0x08: bits.append('WEN')
    if raw & 0x04: bits.append('PEN')
    if raw & 0x02: bits.append('AEN')
    if raw & 0x01: bits.append('PON')
    return 'ENABLE 0x%02X [%s]' % (raw, ', '.join(bits) if bits else 'all off')


def _decode_atime(raw):
    cycles = 256 - raw
    ms = cycles * 2.73
    max_count = min(65535, cycles * 1025)
    return 'ATIME 0x%02X (%d cycles, %.1f ms, max %d)' % (raw, cycles, ms, max_count)


def _decode_ptime(raw):
    cycles = 256 - raw
    ms = cycles * 2.73
    return 'PTIME 0x%02X (%d cycles, %.2f ms)' % (raw, cycles, ms)


def _decode_control(raw):
    pdrive = (raw >> 6) & 3
    pdiode = (raw >> 4) & 3
    pgain = (raw >> 2) & 3
    again = raw & 3
    pdiode_str = {0: 'reserved', 1: 'reserved', 2: 'Ch1'}.get(pdiode, str(pdiode))
    return 'CONTROL 0x%02X (PDRIVE=%s, PDIODE=%s, PGAIN=%s, AGAIN=%s)' % (
        raw, PDRIVE.get(pdrive, str(pdrive)), pdiode_str,
        PGAIN.get(pgain, str(pgain)), AGAIN.get(again, str(again)))


def _decode_config(raw):
    parts = []
    if raw & 0x04: parts.append('AGL')
    if raw & 0x02: parts.append('WLONG')
    if raw & 0x01: parts.append('PDL')
    if not parts:
        parts.append('none')
    return 'CONFIG 0x%02X [%s]' % (raw, ', '.join(parts))


def _decode_pers(raw):
    ppers = (raw >> 4) & 0x0F
    apers_map = {0: 'every', 1: '1', 2: '2', 3: '3', 4: '5', 5: '10',
                 6: '15', 7: '20', 8: '25', 9: '30', 10: '35',
                 11: '40', 12: '45', 13: '50', 14: '55', 15: '60'}
    return 'PERS 0x%02X (PPERS=%d, APERS=%s)' % (raw, ppers, apers_map.get(apers, str(apers)))


def _decode_status(raw):
    flags = []
    if raw & 0x40: flags.append('PSAT')
    if raw & 0x20: flags.append('PINT')
    if raw & 0x10: flags.append('AINT')
    if raw & 0x02: flags.append('PVALID')
    if raw & 0x01: flags.append('AVALID')
    return 'STATUS 0x%02X [%s]' % (raw, ', '.join(flags) if flags else 'none')


def _decode_poffset(raw):
    sign = '+' if (raw & 0x80) else '-'
    magnitude = raw & 0x7F
    return 'POFFSET 0x%02X (%s%d)' % (raw, sign, magnitude)


def _decode_ppulse(raw):
    return 'PPULSE 0x%02X (%d pulses)' % (raw, raw)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'apds9930'
    name = 'APDS-9930'
    longname = 'Avago/Broadcom APDS-9930 digital ambient light and proximity sensor'
    desc = 'Decode APDS-9930 I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['apds9930']
    tags = ['IC', 'Light/Proximity']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('warning',   'Warning'),
        # Conformance annotation pairs (named per spec/light/apds-9930.md):
        ('poweron-start',                'Power-on: first START'),
        ('poweron-done',                 'Power-on: settled'),
        ('conversion-start',             'Conversion: PON set'),
        ('conversion-done',              'Conversion: AVALID+PVALID'),
        ('als-integration-start',        'ALS integration: start'),
        ('als-integration-done',         'ALS integration: AVALID'),
        ('proximity-integration-start',  'Proximity integration: start'),
        ('proximity-integration-done',   'Proximity integration: PVALID'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
        ('poweron',                'Power-on (poweron_ready)',
            (ANN_POWERON_START, ANN_POWERON_DONE)),
        ('conversion',             'First conversion (first_conversion_ready)',
            (ANN_CONVERSION_START, ANN_CONVERSION_DONE)),
        ('als_integration',        'ALS integration (als_integration_time)',
            (ANN_ALS_INTEGRATION_START, ANN_ALS_INTEGRATION_DONE)),
        ('proximity_integration',  'Proximity integration (proximity_integration_time)',
            (ANN_PROXIMITY_INTEGRATION_START, ANN_PROXIMITY_INTEGRATION_DONE)),
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

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf  = []
            self.is_read  = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

            if ptype == 'START':
                # First START after bus release marks the power-on start
                # (heuristic for the conformance checker; the test rig
                # captures from VDD-stable to settled so any START here is
                # the host's first transaction).
                if self.ss_block is not None and self.state == 'GET_ADDR':
                    self.put(ss, es, ANN_POWERON_START,
                             ['poweron-start', 'poweron-start'])
            return

        if ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
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
            self.reg_ptr = None

    def _finish_transaction(self):
        if not self.addr:
            return
        if self.is_read:
            self._emit_read()
        elif self.reg_ptr is not None:
            self._emit_write()

    def _emit_write(self):
        if self.reg_ptr is None:
            return
        # Single-byte writes can be either a register write (data byte
        # follows the command byte as the register address itself) or a
        # special-function command (0xE0 | function). The decoder treats
        # every write as a register write by default, except when the
        # command byte indicates TYPE = 11 (special), in which case the
        # data byte is the function code.
        if not self.databuf:
            return
        first = self.databuf[0]
        if first in REGS:
            # Auto-increment multi-byte register write.
            for i, val in enumerate(self.databuf):
                reg = (self.reg_ptr + i) & 0x1F
                name = REGS.get(reg, '0x%02X' % reg)
                self._annotate_reg_write(reg, name, val)
        else:
            # Single register write.
            reg = self.reg_ptr & 0x1F
            name = REGS.get(reg, '0x%02X' % reg)
            self._annotate_reg_write(reg, name, first)

    def _annotate_reg_write(self, reg, name, value):
        decoded = self._decode(reg, value)
        # Mark als_integration_start / proximity_integration_start /
        # conversion_start as the driver turns on each engine.
        if reg == 0x00:
            en = value
            if en & 0x01:
                self.put(self.ss, self.es, ANN_CONVERSION_START,
                         ['conversion-start', 'conversion-start'])
            if en & 0x02:
                self.put(self.ss, self.es, ANN_ALS_INTEGRATION_START,
                         ['als-integration-start', 'als-integration-start'])
            if en & 0x04:
                self.put(self.ss, self.es, ANN_PROXIMITY_INTEGRATION_START,
                         ['proximity-integration-start', 'proximity-integration-start'])
        if reg == 0x01:
            self.put(self.ss, self.es, ANN_ALS_INTEGRATION_START,
                     ['als-integration-start', 'als-integration-start'])
        if reg == 0x0E:
            self.put(self.ss, self.es, ANN_PROXIMITY_INTEGRATION_START,
                     ['proximity-integration-start', 'proximity-integration-start'])
        self.put(self.ss, self.es, ANN_WRITE,
                 [decoded, '%s 0x%02X' % (name, value)])

    def _emit_read(self):
        if self.reg_ptr is None or not self.databuf:
            return
        first = self.reg_ptr & 0x1F
        name = REGS.get(first, '0x%02X' % first)
        if len(self.databuf) == 1:
            value = self.databuf[0]
            decoded = self._decode(first, value)
            self.put(self.ss, self.es, ANN_READ,
                     [decoded, '%s 0x%02X' % (name, value)])
            # Mark conformance "done" boundaries when STATUS is read with
            # both AVALID and PVALID set, or with AVALID alone for the
            # als_integration_time check.
            if first == 0x13:
                if (value & 0x01) and (value & 0x02):
                    self.put(self.ss, self.es, ANN_CONVERSION_DONE,
                             ['conversion-done', 'conversion-done'])
                    self.put(self.ss, self.es, ANN_POWERON_DONE,
                             ['poweron-done', 'poweron-done'])
                elif value & 0x01:
                    self.put(self.ss, self.es, ANN_ALS_INTEGRATION_DONE,
                             ['als-integration-done', 'als-integration-done'])
                elif value & 0x02:
                    self.put(self.ss, self.es, ANN_PROXIMITY_INTEGRATION_DONE,
                             ['proximity-integration-done', 'proximity-integration-done'])
        else:
            # Multi-byte read (auto-increment burst).
            text = '%s+%d' % (name, len(self.databuf) - 1)
            self.put(self.ss, self.es, ANN_READ, [text, text])

    def _decode(self, reg, value):
        if reg == 0x00: return _decode_enable(value)
        if reg == 0x01: return _decode_atime(value)
        if reg == 0x02: return _decode_ptime(value)
        if reg == 0x0C: return _decode_pers(value)
        if reg == 0x0D: return _decode_config(value)
        if reg == 0x0E: return _decode_ppulse(value)
        if reg == 0x0F: return _decode_control(value)
        if reg == 0x13: return _decode_status(value)
        if reg == 0x1E: return _decode_poffset(value)
        if reg == 0x12: return 'ID 0x%02X' % value
        return '0x%02X 0x%02X' % (reg, value)