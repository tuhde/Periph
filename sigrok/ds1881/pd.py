import sigrokdecode as srd

ADDRS = tuple(range(0x28, 0x30))

ANN_DATA           = 0
ANN_POWERON_START  = 1
ANN_POWERON_READY  = 2
ANN_WARNING        = 3

MUTE_DB = 80


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


def _pos_to_db(pos, option):
    mute = 63 if option == 1 else 33
    if pos >= mute:
        return None
    if option == 1 or pos <= 12:
        return pos
    if pos <= 24:
        return 12 + 2 * (pos - 12)
    return 36 + 3 * (pos - 24)


def _db_text(pos, option):
    db = _pos_to_db(pos, option)
    return 'MUTE' if db is None else '-%d dB' % db


class Decoder(srd.Decoder):
    api_version = 3
    id = 'ds1881'
    name = 'CAT5171'
    longname = 'DS1881 dual audio digital potentiometer'
    desc = 'Decode DS1881 I2C command bytes (wiper and configuration writes, round-robin reads).'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ds1881']
    tags = ['IC']

    annotations = (
        ('data',          'Command / wiper / config'),
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
        self.state = 'IDLE'     # IDLE | GET_CMD | READ | IGNORE
        self.addr = None
        self.databuf = []
        self.is_read = False
        self.seen_first = False
        self.ss_block = None
        self.in_txn = False
        self.option = 1         # Option 1 assumed until a configuration write is seen
        self.read_index = 0

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _decode_value(self, ss, es, byte, direction, kind):
        """kind: 0/1 = wiper, 2 = configuration (read-back, no command prefix)."""
        if kind in (0, 1):
            pos = byte & 0x3F
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['%s wiper %d: position %d (%s)' % (direction, kind, pos, _db_text(pos, self.option)),
                                 '%s W%d %d %s' % (direction[0], kind, pos, _db_text(pos, self.option)),
                                 'W%d %d' % (kind, pos)]])
            if pos > (63 if self.option == 1 else 33):
                self._warn(ss, es, 'Position %d beyond Option %d range' % (pos, self.option))
        else:
            self._decode_config(ss, es, byte, direction)

    def _decode_config(self, ss, es, byte, direction):
        vnv = 'volatile' if byte & 0x04 else 'nonvolatile'
        zc = 'ZC on' if byte & 0x02 else 'ZC off'
        opt = 2 if byte & 0x01 else 1
        self.put(ss, es, self.out_ann,
                 [ANN_DATA, ['%s config [0x%02X]: %s, %s, Option %d' % (direction, byte, vnv, zc, opt),
                             '%s CFG 0x%02X' % (direction[0], byte), 'CFG']])
        if byte & 0x38:
            self._warn(ss, es, 'Configuration bits 5:3 should be 0 (got 0x%02X)' % (byte & 0x38))
        return opt

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
                self.state = 'GET_CMD'
                self.databuf = []
                self.is_read = False
            else:
                self._warn(ss, es, 'Write to 0x%02X - not a DS1881 (0x28-0x2F)' % pdata)
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata in ADDRS:
                self.addr = pdata
                self.state = 'READ'
                self.databuf = []
                self.is_read = True
                self.read_index = 0
            else:
                self.state = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_CMD':
                self.databuf.append(pdata)
                sel = pdata >> 6
                if sel in (0, 1):
                    self._decode_value(ss, es, pdata, 'Write', sel)
                elif sel == 2:
                    self.option = self._decode_config(ss, es, pdata, 'Write')
                else:
                    self.put(ss, es, self.out_ann,
                             [ANN_DATA, ['Reserved command [0x%02X]' % pdata, 'RSVD 0x%02X' % pdata]])
                    self._warn(ss, es, 'Reserved command byte 0x%02X (11xxxxxx)' % pdata)

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.databuf.append(pdata)
                self._decode_value(ss, es, pdata, 'Read', self.read_index % 3)
                self.read_index += 1

        elif ptype == 'STOP':
            self._first_transaction_done(ss, es)
            self.in_txn = False
            self.state = 'IDLE'
