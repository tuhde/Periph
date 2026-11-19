import sigrokdecode as srd

ADDR = 0x60

REGS = {
    0x00: 'Padc_MSB', 0x01: 'Padc_LSB', 0x02: 'Tadc_MSB', 0x03: 'Tadc_LSB',
    0x04: 'a0_MSB', 0x05: 'a0_LSB', 0x06: 'b1_MSB', 0x07: 'b1_LSB',
    0x08: 'b2_MSB', 0x09: 'b2_LSB', 0x0A: 'c12_MSB', 0x0B: 'c12_LSB',
    0x12: 'CONVERT',
}
READ_ONLY = set(range(0x00, 0x12))

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_COEF_READ = 2
ANN_ADC_READ  = 3
ANN_PTR_WRITE = 4
ANN_WARNING   = 5
# Named start/end pair for the "conversion" conformance check (see
# specs/pressure/mpl115a2.md, Timing Constraints, and
# specs/pressure/mpl115a2_timing.conf): the write of CONVERT marks the start,
# the first following read of the ADC registers marks the result as ready.
# Additive - does not replace the generic reg-write/reg-read annotations.
ANN_CONVERSION_START = 6
ANN_CONVERSION_DONE  = 7


def _s16(raw):
    return raw if raw < 0x8000 else raw - 0x10000


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
    id = 'mpl115a2'
    name = 'MPL115A2'
    longname = 'MPL115A2 barometric pressure sensor'
    desc = 'Decode MPL115A2 I2C pressure sensor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mpl115a2']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('coef-read', 'Coefficient read'),
        ('adc-read',  'ADC read'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
        ('conversion-start', 'Conversion: start'),
        ('conversion-done',  'Conversion: done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_COEF_READ, ANN_ADC_READ, ANN_PTR_WRITE)),
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
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.coef     = {}           # decoded a0, b1, b2, c12 once a full block was seen
        self.last_convert_es = None  # end sample of the last CONVERT write
        self.samplerate = None

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _reg_name(self, reg):
        return REGS.get(reg, 'reg[0x%02X]' % reg)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        if reg is None:
            return
        if self.is_read:
            self._finish_read(reg)
        else:
            self._finish_write(reg)

    def _decode_coefficients(self, reg, buf):
        """Names and scales the coefficient bytes of a read starting at reg 0x04."""
        specs = [('a0', 8.0), ('b1', 8192.0), ('b2', 16384.0), ('c12', 16777216.0)]
        parts = []
        for i, (name, div) in enumerate(specs):
            r = 0x04 + 2 * i
            if r >= reg and r + 1 < reg + len(buf):
                idx = r - reg
                val = _s16((buf[idx] << 8) | buf[idx + 1]) / div
                self.coef[name] = val
                parts.append('%s=%.6g' % (name, val))
        return parts

    def _compensate(self, padc, tadc):
        c = self.coef
        pcomp = c['a0'] + (c['b1'] + c['c12'] * tadc) * padc + c['b2'] * tadc
        return (pcomp * (115.0 - 50.0) / 1023.0 + 50.0) * 1000.0

    def _finish_read(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es

        if 0x04 <= reg <= 0x0B and len(buf) >= 2:
            parts = self._decode_coefficients(reg, buf)
            self.put(ss, es, self.out_ann,
                     [ANN_COEF_READ, ['Coefficients: %s' % ', '.join(parts), 'COEF']])
            return

        if reg <= 0x03:
            if reg == 0x00 and len(buf) >= 4:
                padc = ((buf[0] << 8) | buf[1]) >> 6
                tadc = ((buf[2] << 8) | buf[3]) >> 6
                text = 'ADC: Padc=%d Tadc=%d' % (padc, tadc)
                if len(self.coef) == 4:
                    text += ' -> %.0f Pa' % self._compensate(padc, tadc)
                self.put(ss, es, self.out_ann, [ANN_ADC_READ, [text, 'P=%d' % padc]])
                if self.last_convert_es is not None:
                    if self.samplerate and (ss - self.last_convert_es) / self.samplerate < 0.001:
                        self._warn(ss, es, 'ADC read less than 1 ms after CONVERT (conversion not finished)')
                    self.last_convert_es = None
                    self.put(ss, es, self.out_ann,
                             [ANN_CONVERSION_DONE,
                              ['conversion_done: ADC registers read (Padc=%d, Tadc=%d)' % (padc, tadc),
                               'conversion_done', 'C\u2713']])
                    return
                return
            hex_bytes = ' '.join('0x%02X' % b for b in buf)
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ, ['Read %s: %s' % (self._reg_name(reg), hex_bytes), 'R 0x%02X' % reg]])
            return

        hex_bytes = ' '.join('0x%02X' % b for b in buf)
        self.put(ss, es, self.out_ann,
                 [ANN_REG_READ, ['Read %s: %s' % (self._reg_name(reg), hex_bytes), 'R 0x%02X' % reg]])

    def _finish_write(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        name = self._reg_name(reg)

        if not buf:
            self.put(ss, es, self.out_ann,
                     [ANN_PTR_WRITE, ['Pointer -> %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg]])
            return

        if reg in READ_ONLY:
            self._warn(ss, es, 'Write to read-only register %s (0x%02X)' % (name, reg))

        if reg == 0x12:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, ['CONVERT <- 0x%02X (start conversion)' % buf[0], 'CONVERT']])
            self.last_convert_es = es
            self.put(ss, es, self.out_ann,
                     [ANN_CONVERSION_START,
                      ['conversion_start: CONVERT written', 'conversion_start', 'C\u25b6']])
            return

        hex_bytes = ' '.join('0x%02X' % b for b in buf)
        self.put(ss, es, self.out_ann,
                 [ANN_REG_WRITE, ['Write %s: %s' % (name, hex_bytes), 'W 0x%02X' % reg]])

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
            if pdata != ADDR:
                self.state = 'IDLE'
                return
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
