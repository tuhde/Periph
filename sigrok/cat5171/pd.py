import sigrokdecode as srd

ADDRS = (0x2C, 0x2D)

INSTR_RS = 0x40
INSTR_SD = 0x20
INSTR_RESERVED = 0x9F   # bit 7 and bits 4:0 must be 0

ANN_DATA           = 0
ANN_POWERON_START  = 1
ANN_POWERON_READY  = 2
ANN_WARNING        = 3


def _decode_instr(byte):
    flags = ('RS' if byte & INSTR_RS else '') + (' SD' if byte & INSTR_SD else '')
    return flags.strip() or 'normal'


def _warn_tag(msg):
    return msg.split()[0][:8].upper()


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
    id = 'cat5171'
    name = 'CAT5171'
    longname = 'CAT5171 256-position I2C digital potentiometer'
    desc = 'Decode CAT5171 I2C instruction/data transactions (wiper writes and reads).'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['cat5171']
    tags = ['IC']

    annotations = (
        ('data',          'Instruction / wiper'),
        ('poweron-start', 'Capture start (power-on marker)'),
        ('poweron-ready', 'First transaction after power-on'),
        ('warning',       'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('timing',   'Timing',   (ANN_POWERON_START, ANN_POWERON_READY)),
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
        self.state = 'IDLE'     # IDLE | GET_INSTR | GET_DATA | READ | IGNORE
        self.addr = None
        self.instr = None
        self.ndata = 0
        self.databuf = []
        self.is_read = False
        self.seen_first = False
        self.ss_block = None
        self.in_txn = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _emit_code(self, ss, es, direction, code, flags=''):
        self.put(ss, es, self.out_ann,
                 [ANN_DATA, ['%s wiper [0x%02X] code=%d ratio=%.4f%s' % (direction, code, code, code / 256.0, flags),
                             '%s wiper 0x%02X' % (direction[0], code)]])

    def _first_transaction_done(self, ss, es):
        if not self.seen_first and self.in_txn:
            self.seen_first = True
            self.put(self.ss_block if self.ss_block is not None else ss, es, self.out_ann,
                     [ANN_POWERON_READY, ['poweron_ready: first transaction after power-on',
                                          'poweron_ready', '\u26a1']])

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            if not self.seen_first:
                # marker spanning capture start up to the first START (the power-on wait)
                self.put(0, ss, self.out_ann,
                         [ANN_POWERON_START, ['poweron_start: capture start (power-on)',
                                              'poweron_start', 'P\u25b6']])
            if not self.in_txn:
                self.ss_block = ss
            self.in_txn = True
            self.state = 'IDLE'

        elif ptype == 'START REPEAT':
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata in ADDRS:
                self.addr = pdata
                self.state = 'GET_INSTR'
                self.ndata = 0
                self.databuf = []
                self.is_read = False
            else:
                self._warn(ss, es, 'Write to 0x%02X - not a CAT5171 (0x2C/0x2D)' % pdata)
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata in ADDRS:
                self.addr = pdata
                self.state = 'READ'
                self.ndata = 0
                self.databuf = []
                self.is_read = True
            else:
                self.state = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_INSTR':
                self.instr = pdata
                self.databuf = []
                self.state = 'GET_DATA'
                flags = _decode_instr(pdata)
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Instruction [0x%02X] %s%s%s' % (
                                         pdata, flags,
                                         ' -> midscale reset' if pdata & INSTR_RS else '',
                                         ', shutdown' if pdata & INSTR_SD else ''),
                                     'INSTR 0x%02X' % pdata]])
                if pdata & INSTR_RESERVED:
                    self._warn(ss, es, 'Instruction bits 7 and 4:0 should be 0 (got 0x%02X)' % (pdata & INSTR_RESERVED))
            elif self.state == 'GET_DATA':
                self.ndata += 1
                self.databuf.append(pdata)
                flags = ''
                if self.instr is not None:
                    flags = (' RS' if self.instr & INSTR_RS else '') + (' SD' if self.instr & INSTR_SD else '')
                self._emit_code(ss, es, 'Write', pdata, flags)

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.ndata += 1
                self.databuf.append(pdata)
                self._emit_code(ss, es, 'Read', pdata)
                if self.ndata > 1:
                    self._warn(ss, es, 'Read of more than 1 byte')

        elif ptype == 'STOP':
            if self.state == 'GET_DATA' and self.ndata == 0:
                self._warn(ss, es, 'Write without a data byte after the instruction')
            self._first_transaction_done(ss, es)
            self.in_txn = False
            self.state = 'IDLE'
