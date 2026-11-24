import sigrokdecode as srd

ADDRS = {0x70}

CMD_SLEEP = 0xB098
CMD_WAKE = 0x3517
CMD_RESET = 0x805D
CMD_READ_ID = 0xEFC8
# measurement command -> (clock stretching, low power, first value)
MEASURE = {
    0x7CA2: (True, False, 'T'), 0x5C24: (True, False, 'RH'),
    0x7866: (False, False, 'T'), 0x58E0: (False, False, 'RH'),
    0x6458: (True, True, 'T'), 0x44DE: (True, True, 'RH'),
    0x609C: (False, True, 'T'), 0x401A: (False, True, 'RH'),
}
ID_MASK = 0x083F
ID_VALUE = 0x0807

ANN_CMD     = 0
ANN_RESULT  = 1
ANN_STATUS  = 2
ANN_WARNING = 3


def _crc8(data):
    crc = 0xFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ 0x31) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def _describe_command(code):
    if code == CMD_SLEEP:
        return 'sleep'
    if code == CMD_WAKE:
        return 'wake-up'
    if code == CMD_RESET:
        return 'soft reset'
    if code == CMD_READ_ID:
        return 'read ID'
    if code in MEASURE:
        stretching, low_power, first = MEASURE[code]
        return 'measure (%s, %s, %s first)' % (
            'low power' if low_power else 'normal',
            'clock stretching' if stretching else 'no stretching', first)
    return None


def _decode_words(first, data):
    """Decode the two CRC-protected result words of a measurement read; returns (text, crc_ok)."""
    parts = []
    ok = True
    kinds = (first, 'RH' if first == 'T' else 'T')
    for i, kind in enumerate(kinds):
        chunk = data[3 * i:3 * i + 3]
        if len(chunk) < 3:
            break
        good = _crc8(chunk[0:2]) == chunk[2]
        ok = ok and good
        code = (chunk[0] << 8) | chunk[1]
        if kind == 'T':
            parts.append('T=%.2f \u00b0C' % (-45.0 + 175.0 * code / 65536.0))
        else:
            parts.append('RH=%.1f %%RH' % min(100.0, max(0.0, 100.0 * code / 65536.0)))
        parts[-1] += ' (code 0x%04X, crc %s)' % (code, 'ok' if good else 'BAD')
    return ', '.join(parts), ok


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
    id = 'shtc3'
    name = 'SHTC3'
    longname = 'Sensirion SHTC3 humidity and temperature sensor'
    desc = 'Decode SHTC3 I2C commands, temperature and humidity results with CRC check, the ID word and sleep/wake-up tracking.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['shtc3']
    tags = ['IC', 'Sensor', 'Humidity']

    annotations = (
        ('cmd',     'Command'),
        ('result',  'Measurement result'),
        ('status',  'ID'),
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
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None       # last command code (used to interpret reads)
        self.asleep   = False
        self.databuf  = []
        self.ss_block = None
        self.cmd_buf  = []

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_ADDR_DONE'):
            return
        buf = self.databuf
        if self.is_read:
            if not buf:
                return
            cmd = self.reg_ptr
            if cmd in MEASURE and len(buf) >= 3:
                text, ok = _decode_words(MEASURE[cmd][2], buf)
                self.put(self.ss_block, self.es, self.out_ann, [ANN_RESULT, [text, 'Result']])
                if not ok:
                    self._warn(self.ss_block, self.es, 'CRC mismatch in result')
                return
            if cmd == CMD_READ_ID and len(buf) >= 3:
                ok = _crc8(buf[0:2]) == buf[2]
                ident = (buf[0] << 8) | buf[1]
                known = (ident & ID_MASK) == ID_VALUE
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_STATUS, ['ID 0x%04X (%s, crc %s)' % (
                             ident, 'SHTC3' if known else 'not an SHTC3', 'ok' if ok else 'BAD'), 'ID']])
                if not ok:
                    self._warn(self.ss_block, self.es, 'CRC mismatch in ID word')
                elif not known:
                    self._warn(self.ss_block, self.es, 'Unexpected chip ID 0x%04X' % ident)
                return
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_RESULT, ['Read %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'R %dB' % len(buf)]])
            return
        # write phase: every write is a 16-bit command, MSB first
        if len(self.cmd_buf) < 2:
            if self.cmd_buf:
                self._warn(self.ss_block, self.es, 'Short command (%d byte)' % len(self.cmd_buf))
            return
        code = (self.cmd_buf[0] << 8) | self.cmd_buf[1]
        self.reg_ptr = code
        desc = _describe_command(code)
        if desc is not None:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['Command 0x%04X: %s' % (code, desc), 'CMD 0x%04X' % code]])
        else:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['Command 0x%04X: unknown' % code, 'CMD 0x%04X' % code]])
            self._warn(self.ss_block, self.es, 'Unknown command code 0x%04X' % code)
        if code == CMD_WAKE:
            self.asleep = False
        elif self.asleep:
            self._warn(self.ss_block, self.es, 'Command 0x%04X while the sensor is asleep (no wake-up seen)' % code)
        elif code == CMD_SLEEP:
            self.asleep = True
        if len(self.cmd_buf) > 2:
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
