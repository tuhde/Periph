import sigrokdecode as srd

ADDRS = set(range(0x70, 0x78))

BLINK_FREQ = {0: 'off', 1: '2 Hz', 2: '1 Hz', 3: '0.5 Hz'}

ANN_CMD = 0
ANN_DATA = 1
ANN_WARNING = 2


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
    id = 'ht16k33'
    name = 'HT16K33'
    longname = 'HT16K33 16x8 LED matrix driver with key scan'
    desc = 'Decode HT16K33 I2C command, display RAM and key data transfers.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ht16k33']
    tags = ['IC', 'Display', 'LED']

    annotations = (
        ('cmd',     'Command'),
        ('data',    'Display / key data'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CMD, ANN_DATA)),
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
        self.cmd      = None
        self.databuf  = []
        self.last_ptr = None
        self.standby  = False
        self.ss_block = None
        self.ss       = None
        self.es       = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _decode_command(self, raw):
        """Return a label for a command byte (None for display-address bytes)."""
        if raw == 0xD9:
            return 'test mode 0xD9'
        hi = raw & 0xF0
        if hi == 0x00:
            return 'display address pointer 0x%02X' % (raw & 0x0F)
        if hi == 0x20:
            return 'system setup 0x%02X: oscillator %s' % (raw, 'on' if raw & 1 else 'off (standby)')
        if hi == 0x40:
            return 'key data address pointer 0x%02X' % raw
        if hi == 0x60:
            return 'INT flag address pointer 0x%02X' % raw
        if hi == 0x80:
            return 'display setup 0x%02X: display %s, blink %s' % (
                raw, 'on' if raw & 1 else 'off', BLINK_FREQ[(raw >> 1) & 3])
        if hi == 0xA0:
            if raw & 1:
                return 'ROW/INT set 0x%02X: INT output, active %s' % (raw, 'high' if raw & 2 else 'low')
            return 'ROW/INT set 0x%02X: ROW15 output' % raw
        if hi == 0xE0:
            return 'dimming set 0x%02X: %d/16 duty' % (raw, (raw & 0x0F) + 1)
        return 'unknown command 0x%02X' % raw

    def _keys(self, buf):
        keys = []
        for k in range(min(3, len(buf) // 2)):
            word = (buf[2 * k] | (buf[2 * k + 1] << 8)) & 0x1FFF
            for n in range(13):
                if word >> n & 1:
                    keys.append('KS%d/K%d' % (k, n + 1))
        return keys

    def _finish_transaction(self):
        if self.state not in ('GET_CMD', 'GET_DATA', 'GET_READ'):
            return
        ss, es = self.ss_block, self.es
        if self.is_read:
            if self.last_ptr == 0x40:
                keys = self._keys(self.databuf)
                label = 'Keys: ' + (', '.join(keys) if keys else 'none')
                label += ' [' + ' '.join('0x%02X' % b for b in self.databuf) + ']'
            elif self.last_ptr == 0x60:
                label = 'INT flag: %d' % (self.databuf[0] & 1 if self.databuf else 0)
            else:
                label = 'Read: ' + ' '.join('0x%02X' % b for b in self.databuf)
            self.put(ss, es, self.out_ann, [ANN_DATA, [label, 'Rd']])
            return
        if self.cmd is None:
            return
        raw = self.cmd
        label = self._decode_command(raw)
        if raw == 0xD9:
            self._warn(ss, es, 'Test mode command 0xD9 is for Holtek use only')
        if self.standby and (raw & 0xF0) != 0x20:
            self._warn(ss, es, 'Command 0x%02X after standby: only system setup is accepted' % raw)
        if (raw & 0xF0) == 0x20:
            self.standby = not (raw & 1)
        if (raw & 0xF0) in (0x40, 0x60):
            self.last_ptr = raw & 0xF0
        self.put(ss, es, self.out_ann, [ANN_CMD, ['Cmd: ' + label, 'C']])
        if (raw & 0xF0) == 0x00 and self.databuf:
            start = raw & 0x0F
            if start + len(self.databuf) > 16:
                self._warn(ss, es, 'Display data past RAM address 0x0F wraps around (address %d + %d bytes)' % (
                    start, len(self.databuf)))
            parts = []
            for i, b in enumerate(self.databuf):
                a = (start + i) & 0x0F
                parts.append('COM%d %s: 0x%02X' % (a >> 1, 'ROW8-15' if a & 1 else 'ROW0-7', b))
            dlong = 'Display RAM: ' + ', '.join(parts)
            dshort = 'data[%d]' % len(self.databuf)
            self.put(ss, es, self.out_ann, [ANN_DATA, [dlong, dshort]])
        elif self.databuf:
            self._warn(ss, es, 'Unexpected data bytes after command 0x%02X' % raw)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.cmd     = None
            self.databuf = []
            self.is_read = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self._warn(ss, es, 'Unexpected address 0x%02X' % addr)
                self.state = 'IDLE'
                return
            self.addr = addr
            if ptype == 'ADDRESS READ':
                self.is_read = True
                self.state = 'GET_READ'
            else:
                self.state = 'GET_CMD'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_CMD':
                self.cmd = pdata
                self.state = 'GET_DATA'
            elif self.state == 'GET_DATA':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.cmd     = None
            self.databuf = []
            self.is_read = False
