import sigrokdecode as srd

ADDRS = {0x0B}

REGS = {
    0x04: 'Before RSOC',
    0x06: 'Thermistor B',
    0x07: 'Initial RSOC',
    0x08: 'Cell Temperature',
    0x09: 'Cell Voltage',
    0x0A: 'Current Direction',
    0x0B: 'APA',
    0x0C: 'APT',
    0x0D: 'RSOC',
    0x0F: 'ITE',
    0x11: 'IC Version',
    0x12: 'Change of the Parameter',
    0x13: 'Alarm Low RSOC',
    0x14: 'Alarm Low Cell Voltage',
    0x15: 'IC Power Mode',
    0x16: 'Status Bit',
    0x1A: 'Number of the Parameter',
}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3
ANN_STATUS    = 4


def crc8(data):
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def _decode_reg(reg, raw):
    if reg in (0x04, 0x07):
        return '%s 0x%04X%s' % (REGS[reg], raw, ' (init command)' if raw == 0xAA55 else ' (expected 0xAA55!)')
    if reg == 0x06:
        return 'Thermistor B = %d K' % raw
    if reg == 0x08:
        return 'Temperature 0x%04X = %.1f \u00b0C' % (raw, raw / 10.0 - 273.15)
    if reg == 0x09:
        return 'Cell Voltage 0x%04X = %d mV (%.3f V)' % (raw, raw, raw / 1000.0)
    if reg == 0x0A:
        return 'Current Direction = %s' % {0: 'auto', 1: 'charge', 0xFFFF: 'discharge'}.get(raw, '0x%04X (invalid)' % raw)
    if reg == 0x0B:
        return 'APA 0x%02X' % raw
    if reg == 0x0C:
        return 'APT 0x%04X' % raw
    if reg == 0x0D:
        return 'RSOC = %d %%' % raw
    if reg == 0x0F:
        return 'ITE 0x%04X = %.1f %%' % (raw, raw / 10.0)
    if reg == 0x11:
        return 'IC Version 0x%04X' % raw
    if reg == 0x12:
        return 'Battery profile = %d' % raw
    if reg == 0x13:
        return 'Alarm Low RSOC = %s' % ('disabled' if raw == 0 else '%d %%' % raw)
    if reg == 0x14:
        return 'Alarm Low Cell Voltage = %s' % ('disabled' if raw == 0 else '%d mV' % raw)
    if reg == 0x15:
        return 'Power mode = %s' % {1: 'operational', 2: 'sleep'}.get(raw, '0x%04X (invalid)' % raw)
    if reg == 0x16:
        return 'Temperature source = %s' % {0: 'I\u00b2C mode', 1: 'thermistor mode'}.get(raw, '0x%04X (invalid)' % raw)
    if reg == 0x1A:
        return 'Parameter file 0x%04X' % raw
    return 'Reg[0x%02X] 0x%04X' % (reg, raw)


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'chip id' in m or 'device id' in m:
        return 'ID?'
    if 'address' in m:
        return 'ADDR?'
    if any(k in m for k in ('length', 'byte', 'short', 'missing', 'expected', 'no data', 'empty')):
        return 'LEN?'
    if any(k in m for k in ('unknown', 'unexpected', 'invalid', 'undefined', 'reserved', 'out of range')):
        return 'BAD?'
    return 'WARN'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); if a
    call site supplied fewer, derive the missing tier from the existing text
    (leading name token, <= 8 chars when it becomes the shortest tier) so a
    narrow PulseView zoom still has something to show."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings

    def lead(text):
        text = text.strip()
        n = 0
        while n < len(text) and (text[n].isalnum() or text[n] in '_\u2192'):
            n += 1
        return text[:n]

    first = strings[0]
    words = first.split()
    for cand in (lead(strings[-1])[:8], lead(first), ' '.join(words[:2]).rstrip(':,'),
                 ' '.join(words[:3]).rstrip(':,'), lead(first)[:8]):
        if len(strings) >= 3:
            break
        if cand and cand not in strings and len(cand) < len(first):
            i = 0
            while i < len(strings) and len(strings[i]) > len(cand):
                i += 1
            strings.insert(i, cand)
    return strings


class Decoder(srd.Decoder):
    api_version = 3
    id = 'lc709203f'
    name = 'LC709203F'
    longname = 'LC709203F 1-cell Li-ion fuel gauge'
    desc = 'Decode LC709203F I2C fuel gauge transactions and verify the CRC-8.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['lc709203f']
    tags = ['IC', 'Power']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('ptr-write', 'Command pointer write'),
        ('warning',   'Warning'),
        ('status',    'Status'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid not in ('warnings',) for c in classes}
            if data[0] in tiered:
                data = [data[0], _with_short(data[1])]
                self._mirror_python(ss, es)
        super().put(ss, es, out, data)

    def _mirror_python(self, ss, es):
        """OUTPUT_PYTHON mirror of each transaction-level data annotation:
        ('REG_READ' | 'REG_WRITE', (register, bytes)), or
        ('I2C_READ' | 'I2C_WRITE', (address, bytes)) for chips without a register pointer."""
        out_py = self.__dict__.get('out_python')
        if out_py is None or self.__dict__.get('_py_span') == (ss, es):
            return
        self._py_span = (ss, es)
        try:
            buf = bytes(b & 0xFF for b in getattr(self, 'databuf', None) or ())
        except TypeError:
            return
        rw = 'READ' if getattr(self, 'is_read', False) else 'WRITE'
        reg = getattr(self, 'reg_ptr', None)
        if reg is None:
            reg = getattr(self, 'reg_byte', None)
        if reg is not None:
            super().put(ss, es, out_py, ('REG_' + rw, (reg, buf)))
        else:
            super().put(ss, es, out_py, ('I2C_' + rw, (getattr(self, 'addr', None), buf)))

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
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg  = self.reg_ptr
        name = REGS.get(reg, 'Reg[0x%02X]' % reg) if reg is not None else '?'
        if reg is not None and reg not in REGS:
            self._warn(self.ss_block, self.es, 'Unknown command code 0x%02X' % reg)

        if self.is_read:
            if len(self.databuf) == 3:
                lo, hi, crc = self.databuf
                raw = lo | (hi << 8)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ,
                          ['Read %s: %s' % (name, _decode_reg(reg, raw)),
                           'R %s 0x%04X' % (name, raw)]])
                if crc8([0x16, reg, 0x17, lo, hi]) != crc:
                    self._warn(self.ss_block, self.es, 'CRC mismatch on read of %s (got 0x%02X)' % (name, crc))
                else:
                    self.put(self.ss_block, self.es, self.out_ann, [ANN_STATUS, ['CRC ok', 'CRC']])
            elif len(self.databuf) == 2:
                self._warn(self.ss_block, self.es, 'Missing CRC byte on read of %s' % name)
            elif self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d for %s' % (len(self.databuf), name))
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_PTR_WRITE,
                          ['Command \u2192 %s (0x%02X)' % (name, reg),
                           'CMD 0x%02X' % reg]])
            elif len(self.databuf) == 3:
                lo, hi, crc = self.databuf
                raw = lo | (hi << 8)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE,
                          ['Write %s: %s' % (name, _decode_reg(reg, raw)),
                           'W %s 0x%04X' % (name, raw)]])
                if crc8([0x16, reg, lo, hi]) != crc:
                    self._warn(self.ss_block, self.es, 'CRC mismatch on write of %s (got 0x%02X)' % (name, crc))
                else:
                    self.put(self.ss_block, self.es, self.out_ann, [ANN_STATUS, ['CRC ok', 'CRC']])
            elif len(self.databuf) == 2:
                self._warn(self.ss_block, self.es, 'Missing CRC byte on write of %s' % name)
            else:
                self._warn(self.ss_block, self.es,
                           'Unexpected write length %d for %s' % (len(self.databuf), name))

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass  # pointer already set; don't reset databuf
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
