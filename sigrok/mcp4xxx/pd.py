import sigrokdecode as srd

ADDRS = set(range(0x28, 0x30)) | set(range(0x58, 0x5C))
GENERAL_CALL = 0x00

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
    id = 'mcp4xxx'
    name = 'MCP4XXX'
    longname = 'Microchip MCP453X-MCP466X I2C digital potentiometer / rheostat family'
    desc = 'Decode MCP4XXX command bytes: wiper / TCON / EEPROM writes, increment / decrement, reads.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mcp4xxx']
    tags = ['IC', 'Analog/digital']

    options = (
        {'id': 'bits', 'desc': 'Resolution (7-bit: 128 steps, 8-bit: 256 steps)',
         'default': '7', 'values': ('7', '8')},
        {'id': 'nonvolatile', 'desc': 'Part has EEPROM (MCP454X / 456X / 464X / 466X)',
         'default': 'no', 'values': ('no', 'yes')},
        {'id': 'channels', 'desc': 'Resistor networks (1: single, 2: dual)',
         'default': '2', 'values': ('1', '2')},
    )

    annotations = (
        ('write',   'Write / command'),
        ('read',    'Read'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    _OPS = ('write', 'increment', 'decrement', 'read')
    _TCON_BITS = (('GCEN', 8), ('R1HW', 7), ('R1A', 6), ('R1W', 5), ('R1B', 4),
                  ('R0HW', 3), ('R0A', 2), ('R0W', 1), ('R0B', 0))
    _EEPROM_WRITE_S = 0.010   # tWC maximum

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
        ('I2C_READ' | 'I2C_WRITE', (address, bytes)) - the command byte is not a flat register pointer."""
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
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.databuf = []
        self.ss_block = None
        self.pointer = 0           # memory address of the last command (the chip retains it)
        self.last_eeprom_stop = None
        self.samplerate = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    @property
    def _max_code(self):
        return 256 if self.options['bits'] == '8' else 128

    @property
    def _nv(self):
        return self.options['nonvolatile'] == 'yes'

    @property
    def _dual(self):
        return self.options['channels'] == '2'

    def _name(self, address):
        if address in (0, 1):
            return 'WIPER%d' % address
        if address in (2, 3):
            return 'NVWIPER%d' % (address - 2)
        if address == 4:
            return 'TCON'
        if address == 5:
            return 'STATUS'
        return 'DATA%d' % (address - 6)

    def _valid(self, address, op):
        """True if no family member accepts the address / operation combination."""
        if address > 0x0F:
            return False
        if op in (1, 2):
            return address in (0, 1, 2, 3, 0x0F)   # 2, 3, 0xF only as high-voltage commands
        if op == 0:
            return address != 5
        return True

    def _tcon_text(self, v):
        return ' '.join('%s=%d' % (n, (v >> b) & 1) for n, b in self._TCON_BITS)

    def _status_text(self, v):
        return 'EEWA=%d WL1=%d WL0=%d WP=%d' % ((v >> 3) & 1, (v >> 2) & 1, (v >> 1) & 1, v & 1)

    def _value_text(self, address, v):
        if address in (0, 1, 2, 3):
            frac = min(v, self._max_code) / float(self._max_code)
            txt = '%d (%.4f of RAB)' % (v, frac)
            if v > self._max_code:
                txt += ', above full scale -> full scale'
            return txt
        if address == 4:
            return self._tcon_text(v)
        if address == 5:
            return self._status_text(v)
        return '0x%03X' % v

    def _decode_write(self, ss, es):
        buf = self.databuf
        if not buf:
            self._warn(ss, es, 'Write with no command byte')
            return
        i = 0
        parts = []
        count = 0
        prev_stop = self.last_eeprom_stop
        busy = (self._nv and prev_stop is not None and self.samplerate
                and (ss - prev_stop) / self.samplerate < self._EEPROM_WRITE_S)
        while i < len(buf):
            cmd = buf[i]
            address = cmd >> 4
            op = (cmd >> 2) & 3
            d9 = (cmd >> 1) & 1
            d8 = cmd & 1
            self.pointer = address
            if d9:
                self._warn(ss, es, 'Reserved D9 bit set in command byte 0x%02X' % cmd)
            if not self._valid(address, op):
                self._warn(ss, es, 'Invalid address / operation combination 0x%02X (reserved)' % cmd)
            if busy and address not in (0, 1, 4, 5):
                self._warn(ss, es, 'Command to a non-volatile address while an EEPROM write cycle (tWC) runs')
            if not self._dual and address in (1, 3):
                self._warn(ss, es, 'Wiper 1 accessed on a single-network part')
            if not self._nv and (address in (2, 3) or address >= 5) and op == 0:
                self._warn(ss, es, 'EEPROM address 0x%X written on a volatile part' % address)
            if op == 0:
                if i + 1 >= len(buf):
                    self._warn(ss, es, 'Write command without a data byte')
                    parts.append('%s write (no data)' % self._name(address))
                    break
                value = (d8 << 8) | buf[i + 1]
                if address in (0, 1) and value > self._max_code:
                    self._warn(ss, es, 'Wiper value %d above full scale' % value)
                if address in (2, 3) or address >= 6:
                    if i + 2 < len(buf):
                        self._warn(ss, es, 'Continuous (multi-byte) write to non-volatile memory')
                    self.last_eeprom_stop = es
                parts.append('%s = %s' % (self._name(address), self._value_text(address, value)))
                i += 2
            elif op in (1, 2):
                sign = '+1' if op == 1 else '-1'
                if address in (2, 3):
                    parts.append('HV: WL%d %s' % (address - 2, 'disable' if op == 1 else 'enable'))
                    self.last_eeprom_stop = es
                elif address == 0x0F:
                    parts.append('HV: WP %s' % ('disable' if op == 1 else 'enable'))
                    self.last_eeprom_stop = es
                else:
                    parts.append('%s %s' % (self._name(address), sign))
                if i + 1 < len(buf) and (buf[i + 1] >> 4) == address and ((buf[i + 1] >> 2) & 3) == op:
                    pass   # continuous run of identical commands
                i += 1
            else:
                parts.append('Read %s' % self._name(address))
                i += 1
            count += 1
        summary = '; '.join(parts)
        self.put(ss, es, self.out_ann, [ANN_WRITE, [summary, parts[0] if parts else 'Write']])

    def _decode_read(self, ss, es):
        buf = self.databuf
        if not buf:
            self._warn(ss, es, 'Read with no data byte')
            return
        if len(buf) < 2:
            self._warn(ss, es, 'Read shorter than the 2 bytes a read returns')
            return
        value = ((buf[0] & 1) << 8) | buf[1]
        if buf[0] & 0xFE:
            self._warn(ss, es, 'Reserved bits set in the high byte of a read (0x%02X)' % buf[0])
        name = self._name(self.pointer)
        self.put(ss, es, self.out_ann,
                 [ANN_READ, ['Read %s: %s' % (name, self._value_text(self.pointer, value)),
                             'R %s=0x%03X' % (name, value)]])
        if len(buf) > 2:
            self._warn(ss, es, 'Read longer than 2 bytes (%d bytes)' % len(buf))

    def _decode_general_call(self, ss, es):
        buf = self.databuf
        if not buf:
            self._warn(ss, es, 'General Call with no command byte')
            return
        b = buf[0]
        names = {0x80: 'write wiper 0', 0x90: 'write wiper 1', 0xC0: 'write TCON'}
        kind = names.get(b & 0xFC) if (b & 0x03) in (0, 1) and (b & 0xFC) in (0x80, 0x90, 0xC0) else None
        if kind is None:
            hi = b >> 2
            kind = {0x21: 'increment wiper 0', 0x25: 'increment wiper 1',
                    0x22: 'decrement wiper 0', 0x26: 'decrement wiper 1'}.get(hi)
        txt = 'General Call: %s' % (kind or '0x%02X' % b)
        if len(buf) > 1:
            txt += ', data ' + ' '.join('0x%02X' % x for x in buf[1:])
        self.put(ss, es, self.out_ann, [ANN_WRITE, [txt, 'GC']])

    def _flush(self, es):
        if self.state == 'GET_DATA':
            if self.addr == GENERAL_CALL:
                self._decode_general_call(self.ss_block, es)
            elif self.is_read:
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
            self.databuf = []
            self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata in ADDRS or pdata == GENERAL_CALL:
                self.addr = pdata
                self.is_read = (ptype == 'ADDRESS READ')
                self.state = 'GET_DATA'
            else:
                self.state = 'IDLE'

        elif ptype in ('DATA READ', 'DATA WRITE') and self.state == 'GET_DATA':
            self.databuf.append(pdata)

        elif ptype == 'NACK' and self.state == 'GET_DATA' and not self.is_read and self.databuf:
            self._warn(ss, es, 'NACK on a command byte: the chip ignores everything until the next START')

        elif ptype == 'STOP':
            self._flush(es)
            self.state = 'IDLE'
            self.databuf = []
