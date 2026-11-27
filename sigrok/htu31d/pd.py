import sigrokdecode as srd

ADDRS = {0x40, 0x41}

CMDS = {
    0x1E: 'reset', 0x04: 'heater on', 0x02: 'heater off', 0x00: 'read temperature and humidity',
    0x10: 'read humidity', 0x08: 'read diagnostic', 0x0A: 'read serial number',
}
READ_TRH = 0x00
READ_RH = 0x10
READ_DIAG = 0x08
READ_SERIAL = 0x0A

ANN_CMD     = 0
ANN_RESULT  = 1
ANN_STATUS  = 2
ANN_WARNING = 3

DIAG_FLAGS = (
    (7, 'NVM_ERR'), (6, 'RH_OVERRUN'), (5, 'RH_HIGH_ERR'), (4, 'RH_LOW_ERR'),
    (3, 'T_OVERRUN'), (2, 'T_HIGH_ERR'), (1, 'T_LOW_ERR'), (0, 'HEATER_ON'),
)


def _crc8(data):
    crc = 0x00
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ 0x31) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def _is_convert(code):
    return 0x40 <= code <= 0x5F


def _describe_command(code):
    if _is_convert(code):
        return 'start conversion (OSR RH %d, T %d)' % ((code >> 3) & 3, (code >> 1) & 3)
    return CMDS.get(code)


def _decode_diag(v):
    names = [name for bit, name in DIAG_FLAGS if (v >> bit) & 1]
    return 'Diagnostic 0x%02X: %s' % (v, ', '.join(names) if names else 'no flags')


def _decode_words(cmd, data):
    """Decode the CRC-protected result words of a read; returns (text, crc_ok)."""
    parts = []
    ok = True
    if cmd == READ_TRH:
        kinds = ('T', 'RH')
    else:
        kinds = ('RH',)
    for i, kind in enumerate(kinds):
        chunk = data[3 * i:3 * i + 3]
        if len(chunk) < 3:
            break
        good = _crc8(chunk[0:2]) == chunk[2]
        ok = ok and good
        code = (chunk[0] << 8) | chunk[1]
        if kind == 'T':
            parts.append('T=%.2f \u00b0C' % (-40.0 + 165.0 * code / 65535.0))
        else:
            parts.append('RH=%.1f %%RH' % min(100.0, max(0.0, 100.0 * code / 65535.0)))
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
    id = 'htu31d'
    name = 'HTU31D'
    longname = 'TE Connectivity HTU31D humidity and temperature sensor'
    desc = 'Decode HTU31D I2C commands, temperature and humidity results with CRC check, diagnostic byte and serial number.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['htu31d']
    tags = ['IC', 'Sensor', 'Humidity']

    annotations = (
        ('cmd',     'Command'),
        ('result',  'Measurement result'),
        ('status',  'Diagnostic'),
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
            if cmd in (READ_TRH, READ_RH) and len(buf) >= 3:
                text, ok = _decode_words(cmd, buf)
                self.put(self.ss_block, self.es, self.out_ann, [ANN_RESULT, [text, 'Result']])
                if not ok:
                    self._warn(self.ss_block, self.es, 'CRC mismatch in result')
                return
            if cmd == READ_DIAG and len(buf) == 1:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_STATUS, [_decode_diag(buf[0]), 'Diag 0x%02X' % buf[0]]])
                return
            if cmd == READ_SERIAL and len(buf) >= 4:
                ok = _crc8(buf[0:3]) == buf[3]
                serial = (buf[0] << 16) | (buf[1] << 8) | buf[2]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_RESULT, ['Serial number 0x%06X (crc %s)' % (serial, 'ok' if ok else 'BAD'), 'Serial']])
                if not ok:
                    self._warn(self.ss_block, self.es, 'CRC mismatch in serial number')
                return
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_RESULT, ['Read %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'R %dB' % len(buf)]])
            return
        # write phase: every write is a single command byte
        if not self.cmd_buf:
            return
        code = self.cmd_buf[0]
        self.reg_ptr = code
        desc = _describe_command(code)
        if desc is not None:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['Command 0x%02X: %s' % (code, desc), 'CMD 0x%02X' % code]])
        else:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['Command 0x%02X: unknown' % code, 'CMD 0x%02X' % code]])
            self._warn(self.ss_block, self.es, 'Undocumented command 0x%02X' % code)
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
