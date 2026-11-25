"""ADS7828 12-bit 8-channel I2C ADC sigrok protocol decoder (see __init__.py)."""

import sigrokdecode as srd

ADDRS = set(range(0x48, 0x4C))   # A1/A0 = 00..11

ANN_COMMAND = 0
ANN_RESULT  = 1
ANN_WARNING = 2

VREF_INTERNAL = 2.5

PD_MODE = {
    0: 'power down between conversions',
    1: 'ref OFF, ADC ON',
    2: 'ref ON, ADC OFF',
    3: 'ref ON, ADC ON',
}


def _se_channel(c):
    """Single-ended channel number for C2:C0 (interleaved encoding)."""
    return ((c & 3) << 1) | (c >> 2)


def _input_desc(cmd):
    """Return (short_desc, channel_label) for a command byte."""
    sd = (cmd >> 7) & 1
    c = (cmd >> 4) & 7
    if sd:
        ch = _se_channel(c)
        return 'SE CH%d/COM' % ch, 'CH%d' % ch
    pair = c & 3
    if c & 4:
        return 'DIFF CH%d+ CH%d-' % (2 * pair + 1, 2 * pair), 'CH%d-CH%d' % (2 * pair + 1, 2 * pair)
    return 'DIFF CH%d+ CH%d-' % (2 * pair, 2 * pair + 1), 'CH%d-CH%d' % (2 * pair, 2 * pair + 1)


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
    id = 'ads7828'
    name = 'ADS7828'
    longname = 'ADS7828 12-bit 8-channel I2C ADC'
    desc = 'Decode ADS7828 command bytes and 12-bit conversion results.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ads7828']
    tags = ['IC', 'ADC']

    annotations = (
        ('command', 'Command'),
        ('result',  'Result'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_COMMAND, ANN_RESULT)),
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
        self.databuf  = []
        self.ss_block = None
        self.cmd      = None
        self.reg_byte = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _finish_phase(self, es):
        """Annotate the write (command) or read (result) phase that just ended."""
        if self.state != 'GET_DATA':
            return
        buf = self.databuf
        if self.is_read:
            if len(buf) < 2:
                self._warn(self.ss_block, es, 'Read shorter than 2 bytes')
                return
            raw = ((buf[0] & 0x0F) << 8) | buf[1]
            volts = raw / 4096.0 * VREF_INTERNAL
            if self.cmd is None:
                self._warn(self.ss_block, es, 'Read without a preceding command: stale data')
                chan = '?'
            else:
                chan = _input_desc(self.cmd)[1]
            self.reg_byte = self.cmd
            self.put(self.ss_block, es, self.out_ann,
                     [ANN_RESULT,
                      ['%s: 0x%03X = %d -> %.4f V (2.5 V ref)' % (chan, raw, raw, volts),
                       '%s %d' % (chan, raw), '%d' % raw]])
        else:
            if not buf:
                self._warn(self.ss_block, es, 'Write with no data bytes')
                return
            cmd = buf[0]
            self.cmd = cmd
            pd = (cmd >> 2) & 3
            desc = _input_desc(cmd)[0]
            if cmd & 0x03:
                self._warn(self.ss_block, es, 'Command has unused X bits set (0x%02X)' % cmd)
            self.databuf = []
            self.reg_byte = cmd
            self.put(self.ss_block, es, self.out_ann,
                     [ANN_COMMAND,
                      ['CMD 0x%02X | %s | PD=%d (%s)' % (cmd, desc, pd, PD_MODE[pd]),
                       'CMD 0x%02X %s' % (cmd, desc), '0x%02X' % cmd]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START':
                self.cmd = None
                self.reg_byte = None
            else:
                self._finish_phase(ss)
            self.ss_block = ss
            self.databuf = []
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.databuf = []
            self.state = 'GET_DATA'

        elif ptype in ('DATA READ', 'DATA WRITE') and self.state == 'GET_DATA':
            self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_phase(es)
            self.state = 'IDLE'
            self.databuf = []
