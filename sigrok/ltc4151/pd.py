import sigrokdecode as srd

# 0x66 is the mass-write address (writes only, all devices); 0x67-0x6F are the nine
# ADR1/ADR0 three-state addresses.
ADDRS = set(range(0x66, 0x70))

REGS = {
    0x00: 'SENSE_H', 0x01: 'SENSE_L',
    0x02: 'VIN_H',   0x03: 'VIN_L',
    0x04: 'ADIN_H',  0x05: 'ADIN_L',
    0x06: 'CONTROL',
}

CHANNELS = {0: 'SENSE', 1: 'VIN', 2: 'ADIN'}
# (name, V per LSB) for the three 12-bit results, keyed by the high-byte register.
RESULTS = {0x00: ('SENSE', 20e-6, 'V'), 0x02: ('VIN', 0.025, 'V'), 0x04: ('ADIN', 0.0005, 'V')}
DEFAULT_RSENSE = 0.02  # ohms, used only for the current estimate

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3
# Named start/end pair for the "conversion" conformance check (see specs/power/ltc4151.md,
# Timing Constraints, and specs/power/ltc4151_timing.conf): the CONTROL write that starts a
# snapshot conversion (SNAPSHOT = 1) is conversion_start; the first read of the selected
# channel's low register with BUSY = 0 is conversion_done. Additive - the generic
# reg-write/reg-read annotations are emitted alongside these.
ANN_CONVERSION_START = 4
ANN_CONVERSION_DONE  = 5
ANN_STATUS = 6


def _decode_control(raw):
    snap = (raw >> 7) & 1
    ch = (raw >> 5) & 3
    parts = [
        'CONTROL 0x%02X' % raw,
        'SNAPSHOT channel=%s' % CHANNELS.get(ch, 'reserved(%d)' % ch) if snap else 'continuous scan',
        'TEST=1 (ADC halted!)' if raw & 0x10 else None,
        'PAGE_RW=%d' % ((raw >> 3) & 1),
        'STUCK_BUS=%d' % ((raw >> 2) & 1),
    ]
    return ', '.join(p for p in parts if p is not None)


def _decode_result(hi_reg, hi, lo):
    name, lsb, unit = RESULTS[hi_reg]
    code = (hi << 4) | (lo >> 4)
    busy = ' BUSY' if lo & 0x08 else ''
    text = '%s code %d = %.5f %s%s' % (name, code, code * lsb, unit, busy)
    if name == 'SENSE':
        text += ' (%.4f A @ %g mohm)' % (code * lsb / DEFAULT_RSENSE, DEFAULT_RSENSE * 1000)
    return text


def _decode_bytes(reg, data):
    """Decode a (possibly auto-incrementing) run of bytes starting at register reg."""
    parts = []
    i = 0
    while i < len(data):
        r = (reg + i) & 0x07
        if r in RESULTS and i + 1 < len(data):
            parts.append(_decode_result(r, data[i], data[i + 1]))
            i += 2
        elif r == 0x06:
            parts.append(_decode_control(data[i]))
            i += 1
        else:
            parts.append('%s 0x%02X' % (REGS.get(r, 'Reg[0x%02X]' % r), data[i]))
            i += 1
    return '; '.join(parts)


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
    id = 'ltc4151'
    name = 'LTC4151'
    longname = 'LTC4151 7-80 V power monitor'
    desc = 'Decode LTC4151 I2C high-side current and voltage monitor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ltc4151']
    tags = ['IC', 'Power']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
        ('conversion-start', 'Conversion: start'),
        ('conversion-done',  'Conversion: done'),
        ('status',    'Status flags'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_CONVERSION_START, ANN_CONVERSION_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid not in ('timing', 'warnings') for c in classes}
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
        self.snap_ch  = None   # channel of a snapshot conversion awaiting conversion_done

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _read_covers(self, reg, length, target):
        """True if a read of length bytes at reg (auto-increment, wrapping at 0x06) covers target."""
        return any(((reg + i) & 0x07) == target for i in range(length))

    def _byte_at(self, reg, target):
        for i, b in enumerate(self.databuf):
            if ((reg + i) & 0x07) == target:
                return b
        return None

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg = self.reg_ptr
        if reg is None:
            return
        name = REGS.get(reg & 0x07, 'Reg[0x%02X]' % reg)

        if self.is_read:
            if not self.databuf:
                return
            text = _decode_bytes(reg, self.databuf)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_REG_READ, ['Read %s: %s' % (name, text), 'R %s' % name]])
            if self._read_covers(reg, len(self.databuf), 0x06):
                ctrl = self._byte_at(reg, 0x06)
                mode = 'snapshot' if ctrl & 0x80 else 'continuous'
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_STATUS, ['CONTROL: %s mode' % mode, mode, mode[:4]]])
            if self.snap_ch is not None:
                lo_reg = self.snap_ch * 2 + 1
                if self._read_covers(reg, len(self.databuf), lo_reg):
                    lo = self._byte_at(reg, lo_reg)
                    busy = bool(lo & 0x08)
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_STATUS, ['%s BUSY=%d' % (CHANNELS[self.snap_ch], busy),
                                           'BUSY=%d' % busy, 'B%d' % busy]])
                    if not busy:
                        self.put(self.ss_block, self.es, self.out_ann,
                                 [ANN_CONVERSION_DONE,
                                  ['conversion_done: %s BUSY cleared' % CHANNELS[self.snap_ch],
                                   'conversion_done', 'CD\u2713']])
                        self.snap_ch = None
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_PTR_WRITE, ['Pointer \u2192 %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg]])
            else:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE, ['Write %s: %s' % (name, _decode_bytes(reg, self.databuf)),
                                          'W %s' % name]])
                if self._read_covers(reg, len(self.databuf), 0x06):
                    ctrl = self._byte_at(reg, 0x06)
                    if ctrl & 0x10:
                        self._warn(self.ss_block, self.es, 'Test mode enabled: the ADC is halted')
                    if ctrl & 0x80:
                        self.snap_ch = (ctrl >> 5) & 3
                        if self.snap_ch > 2:
                            self._warn(self.ss_block, self.es, 'Reserved snapshot channel selected')
                            self.snap_ch = None
                        else:
                            self.put(self.ss_block, self.es, self.out_ann,
                                     [ANN_CONVERSION_START,
                                      ['conversion_start: snapshot of %s (CONTROL=0x%02X)'
                                       % (CHANNELS[self.snap_ch], ctrl),
                                       'conversion_start', 'CS\u25b6']])
                    else:
                        self.snap_ch = None
                if any(((reg + i) & 0x07) < 6 for i in range(len(self.databuf))):
                    self._warn(self.ss_block, self.es,
                               'Write to a read-only data register (only valid in test mode)')

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
                self.reg_ptr = byte & 0x07
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
