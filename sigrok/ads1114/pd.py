import sigrokdecode as srd

ADDRS = set(range(0x48, 0x4C))

REG_CONVERSION = 0x00
REG_CONFIG = 0x01
REG_LO_THRESH = 0x02
REG_HI_THRESH = 0x03

REGISTERS = {
    REG_CONVERSION: 'CONVERSION',
    REG_CONFIG: 'CONFIG',
    REG_LO_THRESH: 'LO_THRESH',
    REG_HI_THRESH: 'HI_THRESH',
}

MUXES = ('AIN0-AIN1', 'AIN0-AIN3', 'AIN1-AIN3', 'AIN2-AIN3', 'AIN0-GND', 'AIN1-GND', 'AIN2-GND', 'AIN3-GND')
FSRS = (6.144, 4.096, 2.048, 1.024, 0.512, 0.256, 0.256, 0.256)
RATES = (8, 16, 32, 64, 128, 250, 475, 860)
QUEUES = ('1', '2', '4', 'off')
FIXED_FSR = False   # ADS1113: PGA/MUX bits are ignored in hardware

ANN_DATA = 0
ANN_STATUS = 1
ANN_WARNING = 2
# Named start/end pair for the conversion_done conformance check (see the
# Timing Constraints and Sigrok Decoder sections of the chip spec and its
# *_timing.conf).
ANN_CONV_START = 3
ANN_CONV_DONE = 4


def _signed(raw):
    return raw - 0x10000 if raw & 0x8000 else raw


def _config(raw, fixed):
    pga = (raw >> 9) & 7
    fsr = 2.048 if fixed else FSRS[pga]
    return ('OS=%d MUX=%s PGA=%d (+-%.3f V) MODE=%s DR=%d SPS COMP_MODE=%s COMP_POL=%s COMP_LAT=%d COMP_QUE=%s'
            % ((raw >> 15) & 1, MUXES[(raw >> 12) & 7], pga, fsr,
               'single-shot' if raw & 0x0100 else 'continuous', RATES[(raw >> 5) & 7],
               'window' if raw & 0x0010 else 'traditional',
               'active-high' if raw & 0x0008 else 'active-low',
               (raw >> 2) & 1, QUEUES[raw & 3]))


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
    id = 'ads1114'
    name = 'ADS1114'
    longname = 'ADS1114 16-bit delta-sigma ADC'
    desc = 'Decode ADS1114 I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ads1114']
    tags = ['IC', 'Analog/digital']

    annotations = (
        ('data',                 'Register value'),
        ('status',               'CONFIG'),
        ('warning',              'Warning'),
        ('conversion-start',     'Conversion start'),
        ('conversion-done',      'Conversion done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_CONV_START, ANN_CONV_DONE)),
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
        self.state = 'IDLE'         # IDLE | GET_PTR | WRITE | READ | IGNORE
        self.ptr = None             # Address Pointer, kept across transactions
        self.buf = []
        self.direction = None
        self.fsr = 2.048            # full scale of the last Config write (default +-2.048 V)
        self.conv_pending = False   # a Config write with OS=1 awaits its CONVERSION read

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg, tag):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, tag]])

    def _emit(self, ss, es, rw, reg, value):
        name = REGISTERS[reg]
        if reg == REG_CONFIG:
            if rw == 'W':
                self.fsr = 2.048 if FIXED_FSR else FSRS[(value >> 9) & 7]
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['%s CONFIG [0x%04X] %s' % (rw, value, _config(value, FIXED_FSR)),
                                   '%s CONFIG 0x%04X' % (rw, value), '%s CFG' % rw]])
            self.put(ss, es, self.out_python, ('STATUS', (rw, reg, value)))
            if rw == 'W' and value & 0x8000 and value & 0x0100:
                self.put(ss, es, self.out_ann,
                         [ANN_CONV_START, ['conversion_start: Config write with OS=1', 'conversion_start', '\u2192CONV']])
                self.conv_pending = True
            return
        if reg == REG_CONVERSION:
            code = _signed(value)
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['%s CONVERSION [0x%04X] %d = %.6f V' % (rw, value, code, code * self.fsr / 32768),
                                 '%s CONV %d' % (rw, code), '%d' % code]])
            self.put(ss, es, self.out_python, ('DATA', (rw, reg, code)))
            if rw == 'R' and self.conv_pending:
                self.put(ss, es, self.out_ann,
                         [ANN_CONV_DONE, ['conversion_done: first CONVERSION read after the start', 'conversion_done', 'CONV\u2713']])
                self.conv_pending = False
            return
        code = _signed(value)
        text = '%s %s [0x%04X] %d = %.6f V' % (rw, name, value, code, code * self.fsr / 32768)
        if reg == REG_HI_THRESH and value & 0x8000:
            text += ' (HI bit 15 set: conversion-ready mode if LO bit 15 is clear)'
        self.put(ss, es, self.out_ann, [ANN_DATA, [text, '%s %s %d' % (rw, name, code), '%d' % code]])
        self.put(ss, es, self.out_python, ('DATA', (rw, reg, code)))

    def _flush(self):
        buf, self.buf = self.buf, []
        if not buf or self.ptr is None:
            return
        rw = 'W' if self.direction == 'W' else 'R'
        ss, es = buf[0][0], buf[-1][1]
        reg = self.ptr
        if reg not in REGISTERS:
            self._warn(ss, es, '%s of undefined pointer 0x%02X' % ('Write' if rw == 'W' else 'Read', reg), 'PTR?')
            return
        if rw == 'W' and reg == REG_CONVERSION:
            self._warn(ss, es, 'Write to read-only CONVERSION', 'RO')
            return
        if len(buf) != 2:
            self._warn(ss, es, '%s of %d byte(s) to %s, expected 2'
                       % ('Write' if rw == 'W' else 'Read', len(buf), REGISTERS[reg]), 'LEN?')
            if len(buf) < 2:
                return
        value = (buf[0][2] << 8) | buf[1][2]
        self._emit(ss, buf[1][1], rw, reg, value)

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.buf = []

        elif ptype == 'START REPEAT':
            # Pointer write, repeated START, read: one register access.
            self._flush()
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata in ADDRS:
                self.state = 'GET_PTR'
                self.direction = 'W'
            else:
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata in ADDRS:
                self.state = 'READ'
                self.direction = 'R'
            else:
                self.state = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_PTR':
                self.ptr = pdata
                self.state = 'WRITE'
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Pointer → %s (0x%02X)' % (REGISTERS.get(pdata, 'undefined'), pdata),
                                     'PTR 0x%02X' % pdata,
                                     'P%02X' % pdata]])
            elif self.state == 'WRITE':
                self.buf.append((ss, es, pdata))

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.buf.append((ss, es, pdata))

        elif ptype == 'STOP':
            self._flush()
            self.state = 'IDLE'
