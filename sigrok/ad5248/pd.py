import sigrokdecode as srd

ADDRS = {0x2C, 0x2D, 0x2E, 0x2F}

ANN_WRITE   = 0
ANN_READ    = 1
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
    id = 'ad5248'
    name = 'AD5248'
    longname = 'AD5248 dual 256-position I2C digital rheostat'
    desc = 'Decode AD5248 instruction/data writes and RDAC readbacks.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ad5248']
    tags = ['IC', 'Analog/digital']

    annotations = (
        ('write',   'Write (instruction + data)'),
        ('read',    'RDAC readback'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
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
        ('I2C_READ' | 'I2C_WRITE', (address, bytes)) - the chip has no register pointer."""
        out_py = self.__dict__.get('out_python')
        if out_py is None or self.__dict__.get('_py_span') == (ss, es):
            return
        self._py_span = (ss, es)
        try:
            buf = bytes(b & 0xFF for b in getattr(self, 'databuf', None) or ())
        except TypeError:
            return
        rw = 'READ' if getattr(self, 'is_read', False) else 'WRITE'
        super().put(ss, es, out_py, ('I2C_' + rw, (getattr(self, 'addr', None), buf)))

    def __init__(self):
        self.reset()

    def reset(self):
        self.state        = 'IDLE'
        self.addr         = None
        self.is_read      = False
        self.databuf      = []
        self.ss_block     = None
        self.last_channel = 0   # channel selected by the most recent write instruction

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _decode_write(self, ss, es):
        buf = self.databuf
        if not buf:
            self._warn(ss, es, 'Write with no instruction byte')
            return
        instr = buf[0]
        ch = (instr >> 7) & 1
        sd = (instr >> 6) & 1
        self.last_channel = ch
        sd_txt = ', SD=1 (shutdown)' if sd else ''
        if instr & 0x3F:
            self._warn(ss, es, 'Reserved instruction bits set (0x%02X)' % (instr & 0x3F))
        if len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_WRITE, ['Select ch%d%s (readback)' % (ch + 1, sd_txt),
                                  'Sel ch%d' % (ch + 1)]])
            return
        updates = ', '.join('%d (%.4f)' % (d, d / 256.0) for d in buf[1:])
        self.put(ss, es, self.out_ann,
                 [ANN_WRITE, ['Write ch%d%s: wiper=%s' % (ch + 1, sd_txt, updates),
                              'W ch%d=%d' % (ch + 1, buf[1])]])
        if len(buf) > 2 and sd:
            self._warn(ss, es, 'Data bytes follow an instruction with SD=1')

    def _decode_read(self, ss, es):
        buf = self.databuf
        if not buf:
            self._warn(ss, es, 'Read with no data byte')
            return
        ch = self.last_channel
        self.put(ss, es, self.out_ann,
                 [ANN_READ, ['Read ch%d: wiper=%d (%.4f)' % (ch + 1, buf[0], buf[0] / 256.0),
                             'R ch%d=%d' % (ch + 1, buf[0])]])
        if len(buf) > 1:
            self._warn(ss, es, 'Read longer than 1 byte (%d bytes)' % len(buf))

    def _flush(self, es):
        if self.state == 'GET_DATA':
            if self.is_read:
                self._decode_read(self.ss_block, es)
            else:
                self._decode_write(self.ss_block, es)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT':
                # write-then-read: emit the write phase before the read starts
                self._flush(ss)
            self.databuf  = []
            self.is_read  = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata in ADDRS:
                self.addr    = pdata
                self.is_read = (ptype == 'ADDRESS READ')
                self.state   = 'GET_DATA'
            else:
                self.state = 'IDLE'

        elif ptype in ('DATA READ', 'DATA WRITE') and self.state == 'GET_DATA':
            self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._flush(es)
            self.state   = 'IDLE'
            self.databuf = []
