import sigrokdecode as srd

ADDRS = {0x36}

REGS = {
    0x02: 'VCELL',
    0x04: 'SOC',
    0x06: 'MODE',
    0x08: 'VERSION',
    0x0A: 'HIBRT',
    0x0C: 'CONFIG',
    0x14: 'VALRT',
    0x16: 'CRATE',
    0x18: 'VRESET/ID',
    0x1A: 'STATUS',
    0xFE: 'CMD',
}

READ_ONLY = {0x02, 0x04, 0x08, 0x16}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3
ANN_STATUS    = 4

STATUS_BITS = (
    (14, 'ENVR'), (13, 'SC'), (12, 'HD'), (11, 'VR'), (10, 'VL'), (9, 'VH'), (8, 'RI'),
)


def _decode_config(raw):
    parts = ['CONFIG 0x%04X' % raw, 'RCOMP=0x%02X' % (raw >> 8)]
    if raw & 0x80:
        parts.append('SLEEP')
    if raw & 0x40:
        parts.append('ALSC')
    if raw & 0x20:
        parts.append('ALRT')
    parts.append('empty alert %d %%' % (32 - (raw & 0x1F)))
    return ', '.join(parts)


def _decode_status(raw):
    flags = [name for bit, name in STATUS_BITS if raw & (1 << bit)]
    return 'STATUS 0x%04X' % raw + (', ' + ' '.join(flags) if flags else '')


def _decode_reg(reg, raw):
    if reg == 0x02:
        v = (raw >> 4) * 1.25e-3
        return 'VCELL 0x%04X = %.4f V per cell' % (raw, v)
    elif reg == 0x04:
        return 'SOC 0x%04X = %.2f %%' % (raw, raw / 256.0)
    elif reg == 0x06:
        parts = ['MODE 0x%04X' % raw]
        if raw & 0x4000:
            parts.append('QuickStart')
        if raw & 0x2000:
            parts.append('EnSleep')
        if raw & 0x1000:
            parts.append('HibStat')
        return ', '.join(parts)
    elif reg == 0x08:
        ok = ' (ok)' if (raw & 0xFFF0) == 0x0010 else ' (expected 0x001x!)'
        return 'VERSION 0x%04X%s' % (raw, ok)
    elif reg == 0x0A:
        return 'HIBRT 0x%04X: hibernate < %.3f %%/h, active > %.2f mV' % (raw, (raw >> 8) * 0.208, (raw & 0xFF) * 1.25)
    elif reg == 0x0C:
        return _decode_config(raw)
    elif reg == 0x14:
        return 'VALRT 0x%04X: min %.2f V, max %.2f V' % (raw, (raw >> 8) * 0.02, (raw & 0xFF) * 0.02)
    elif reg == 0x16:
        signed = raw - 0x10000 if raw & 0x8000 else raw
        return 'CRATE 0x%04X = %+.2f %%/h' % (raw, signed * 0.208)
    elif reg == 0x18:
        return 'VRESET/ID 0x%04X: reset %.2f V, comparator %s, ID 0x%02X' % (
            raw, (raw >> 9) * 0.04, 'off' if raw & 0x100 else 'on', raw & 0xFF)
    elif reg == 0x1A:
        return _decode_status(raw)
    elif reg == 0xFE:
        return 'CMD 0x%04X%s' % (raw, ' (power-on reset)' if raw == 0x5400 else ' (unknown command!)')
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
    id = 'max17048'
    name = 'MAX17048'
    longname = 'MAX17048/MAX17049 lithium fuel gauge'
    desc = 'Decode MAX17048/MAX17049 I2C fuel gauge register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['max17048']
    tags = ['IC', 'Power']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
        ('status',    'Alert status flags'),
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
                    if rid != 'warnings' for c in classes}
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

    def _emit_status(self, raw):
        flags = [name for bit, name in STATUS_BITS if raw & (1 << bit)]
        text = ', '.join(flags) if flags else 'none'
        self.put(self.ss_block, self.es, self.out_ann,
                 [ANN_STATUS, ['STATUS flags: %s' % text, text, ' '.join(flags) or '-']])

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
                if reg == 0x1A:
                    self._emit_status(raw)
            elif self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d for %s' % (len(self.databuf), name))
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_PTR_WRITE,
                          ['Pointer \u2192 %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg]])
            elif len(self.databuf) == 2:
                raw = (self.databuf[0] << 8) | self.databuf[1]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE,
                          ['Write %s: %s' % (name, _decode_reg(reg, raw)),
                           'W %s 0x%04X' % (name, raw)]])
                if reg in READ_ONLY:
                    self._warn(self.ss_block, self.es, 'Write to read-only register %s' % name)
                elif reg not in REGS and not (0x40 <= reg <= 0x7F):
                    self._warn(self.ss_block, self.es, 'Write to unknown register 0x%02X' % reg)
            elif len(self.databuf) == 1:
                self._warn(self.ss_block, self.es,
                           'Short write to %s: 8-bit writes have no effect, write both bytes' % name)
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
