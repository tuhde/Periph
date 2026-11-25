import sigrokdecode as srd

ADDRS = {0x76, 0x77}

CMD_RESET = 0x1E
CMD_ADC_READ = 0x00
OSR_VALUES = (256, 512, 1024, 2048, 4096)
# maximum conversion time per OSR index (s), datasheet
T_CONV_MAX = (0.00060, 0.00117, 0.00228, 0.00454, 0.00904)
PROM_NAMES = ('manufacturer', 'C1 (SENS_T1)', 'C2 (OFF_T1)', 'C3 (TCS)', 'C4 (TCO)',
              'C5 (T_REF)', 'C6 (TEMPSENS)', 'serial code + CRC-4')

ANN_CMD     = 0
ANN_RESULT  = 1
ANN_STATUS  = 2
ANN_WARNING = 3


def _crc4(words):
    prom = list(words)
    prom[7] &= 0xFF00
    rem = 0
    for cnt in range(16):
        rem ^= (prom[cnt >> 1] & 0x00FF) if cnt & 1 else (prom[cnt >> 1] >> 8)
        for _ in range(8):
            rem = ((rem << 1) ^ 0x3000) & 0xFFFF if rem & 0x8000 else (rem << 1) & 0xFFFF
    return (rem >> 12) & 0x0F


def _div(a, b):
    q = abs(a) // abs(b)
    return q if (a >= 0) == (b >= 0) else -q


def _compensate(prom, d1, d2):
    """Datasheet integer algorithm with the second-order correction; returns (degC, Pa)."""
    c1, c2, c3, c4, c5, c6 = prom[1:7]
    dt = d2 - c5 * 256
    temp = 2000 + _div(dt * c6, 1 << 23)
    off = c2 * (1 << 17) + _div(c4 * dt, 1 << 6)
    sens = c1 * (1 << 16) + _div(c3 * dt, 1 << 7)
    if temp < 2000:
        d = temp - 2000
        t2 = _div(dt * dt, 1 << 31)
        off2 = _div(61 * d * d, 16)
        sens2 = 2 * d * d
        if temp < -1500:
            e = temp + 1500
            off2 += 15 * e * e
            sens2 += 8 * e * e
        temp -= t2
        off -= off2
        sens -= sens2
    p = _div(_div(d1 * sens, 1 << 21) - off, 1 << 15)
    return temp / 100.0, float(p)


def _describe_command(code):
    """Return (text, kind, osr_index, which) for a command byte, or None if undocumented."""
    if code == CMD_RESET:
        return 'reset'
    if code == CMD_ADC_READ:
        return 'ADC read'
    if 0x40 <= code <= 0x48 and code % 2 == 0:
        return 'convert D1 (pressure), OSR %d' % OSR_VALUES[(code - 0x40) // 2]
    if 0x50 <= code <= 0x58 and code % 2 == 0:
        return 'convert D2 (temperature), OSR %d' % OSR_VALUES[(code - 0x50) // 2]
    if 0xA0 <= code <= 0xAE and code % 2 == 0:
        return 'PROM read, word %d (%s)' % ((code - 0xA0) // 2, PROM_NAMES[(code - 0xA0) // 2])
    return None


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
    id = 'ms5607'
    name = 'MS5607'
    longname = 'TE Connectivity MS5607 barometric pressure sensor'
    desc = 'Decode MS5607 I2C commands, PROM words with CRC-4 check, raw ADC results and the compensated temperature and pressure.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ms5607']
    tags = ['IC', 'Sensor', 'Pressure']

    annotations = (
        ('cmd',     'Command'),
        ('result',  'Result'),
        ('status',  'PROM'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CMD, ANN_RESULT, ANN_STATUS)),
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
        self.samplerate = None
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None       # last command byte (used to interpret reads)
        self.databuf  = []
        self.ss_block = None
        self.cmd_buf  = []
        self.prom     = [None] * 8
        self.last_conv = None      # (kind, osr_index, start_sample)
        self.raw = {'D1': None, 'D2': None}

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _prom_complete(self):
        return all(w is not None for w in self.prom)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_ADDR_DONE'):
            return
        buf = self.databuf
        if self.is_read:
            if not buf:
                return
            cmd = self.reg_ptr
            if cmd is not None and 0xA0 <= cmd <= 0xAE and cmd % 2 == 0 and len(buf) >= 2:
                n = (cmd - 0xA0) // 2
                word = (buf[0] << 8) | buf[1]
                self.prom[n] = word
                text = 'PROM[%d] %s = 0x%04X (%d)' % (n, PROM_NAMES[n], word, word)
                self.put(self.ss_block, self.es, self.out_ann, [ANN_STATUS, [text, 'PROM %d' % n]])
                if self._prom_complete():
                    ok = _crc4(self.prom) == (self.prom[7] & 0x0F)
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_STATUS, ['PROM complete, CRC-4 %s' % ('ok' if ok else 'BAD'), 'CRC']])
                    if not ok:
                        self._warn(self.ss_block, self.es, 'PROM CRC-4 mismatch')
                return
            if cmd == CMD_ADC_READ and len(buf) >= 3:
                value = (buf[0] << 16) | (buf[1] << 8) | buf[2]
                kind = self.last_conv[0] if self.last_conv else None
                label = {'D1': 'D1 (pressure)', 'D2': 'D2 (temperature)'}.get(kind, 'ADC')
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_RESULT, ['%s raw = %d' % (label, value), 'ADC %d' % value]])
                if value == 0:
                    self._warn(self.ss_block, self.es, 'ADC read returned 0 (too early, repeated or no conversion)')
                    return
                if kind in self.raw:
                    self.raw[kind] = value
                if kind == 'D1' and self._prom_complete() and self.raw['D2'] is not None:
                    t, p = _compensate(self.prom, value, self.raw['D2'])
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_RESULT, ['T = %.2f \u00b0C, P = %.0f Pa' % (t, p), 'T/P']])
                return
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_RESULT, ['Read %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'R %dB' % len(buf)]])
            return
        # write phase: every write is one command byte
        if not self.cmd_buf:
            return
        code = self.cmd_buf[0]
        self.reg_ptr = code
        desc = _describe_command(code)
        if desc is None:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['Command 0x%02X: unknown' % code, 'CMD 0x%02X' % code]])
            self._warn(self.ss_block, self.es, 'Unknown command byte 0x%02X' % code)
            return
        self.put(self.ss_block, self.es, self.out_ann,
                 [ANN_CMD, ['Command 0x%02X: %s' % (code, desc), 'CMD 0x%02X' % code]])
        if 0x40 <= code <= 0x5F and code != CMD_RESET:
            kind = 'D2' if code & 0x10 else 'D1'
            self.last_conv = (kind, (code & 0x0F) // 2, self.ss_block)
        elif code == CMD_ADC_READ and self.last_conv and self.samplerate:
            kind, idx, started = self.last_conv
            if (self.ss_block - started) / self.samplerate < T_CONV_MAX[idx]:
                self._warn(self.ss_block, self.es, 'ADC read during the conversion (before %.2f ms)' % (T_CONV_MAX[idx] * 1000.0))
        if len(self.cmd_buf) > 1:
            self._warn(self.ss_block, self.es, 'Unexpected extra bytes after command')

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf = []
            self.is_read = False
            self.cmd_buf = []
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = addr
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state = 'GET_DATA_READ'
            else:
                self.state = 'GET_DATA_WRITE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_DATA_WRITE':
                self.cmd_buf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
