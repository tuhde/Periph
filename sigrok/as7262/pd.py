import struct

import sigrokdecode as srd

ADDRS = {0x49}

HW_STATUS = 0x00
HW_WRITE = 0x01
HW_READ = 0x02

VREGS = {
    0x00: 'HW_VERSION (type)', 0x01: 'HW_VERSION', 0x02: 'FW_VERSION (minor/sub)', 0x03: 'FW_VERSION (major/minor)',
    0x04: 'CONTROL_SETUP', 0x05: 'INT_T', 0x06: 'DEVICE_TEMP', 0x07: 'LED_CONTROL',
}
CHANNELS = ('V', 'B', 'G', 'Y', 'O', 'R')
GAINS = ('1x', '3.7x', '16x', '64x')
BANKS = ('mode 0 (V,B,G,Y)', 'mode 1 (G,Y,O,R)', 'mode 2 (all six, continuous)', 'mode 3 (all six, one-shot)')
DRV_MA = (12.5, 25, 50, 100)
IND_MA = (1, 2, 4, 8)

ANN_DATA = 0
ANN_STATUS = 1
ANN_WARNING = 2
# Named start/end pair for the one-shot conversion time conformance check (see the Timing
# Constraints and Sigrok Decoder sections of the chip spec and as7262_timing.conf).
ANN_CONV_START = 3
ANN_CONV_DONE = 4


def _name(reg):
    if reg in VREGS:
        return VREGS[reg]
    if 0x08 <= reg <= 0x13:
        return 'RAW_%s_%s' % (CHANNELS[(reg - 0x08) // 2], 'H' if (reg - 0x08) % 2 == 0 else 'L')
    if 0x14 <= reg <= 0x2B:
        return 'CAL_%s_B%d' % (CHANNELS[(reg - 0x14) // 4], (reg - 0x14) % 4)
    return 'reg 0x%02X' % reg


def _control(v):
    return ('RST=%d INT=%d GAIN=%s BANK=%s DATA_RDY=%d'
            % ((v >> 7) & 1, (v >> 6) & 1, GAINS[(v >> 4) & 3], BANKS[(v >> 2) & 3], (v >> 1) & 1))


def _led(v):
    return ('LED_DRV %s (%s mA), LED_IND %s (%s mA)'
            % ('on' if v & 0x08 else 'off', DRV_MA[(v >> 4) & 3], 'on' if v & 0x01 else 'off', IND_MA[(v >> 1) & 3]))


def _decode(reg, value):
    if reg == 0x04:
        return _control(value)
    if reg == 0x05:
        return 'integration time %.1f ms' % (value * 2.8)
    if reg == 0x06:
        return '%d \u00b0C' % value
    if reg == 0x07:
        return _led(value)
    if reg == 0x00:
        return 'device type 0x%02X' % value
    return '0x%02X' % value


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
    id = 'as7262'
    name = 'AS7262'
    longname = 'ams OSRAM AS7262 6-channel visible spectral sensor'
    desc = 'Decode AS7262 I2C virtual-register accesses (STATUS / WRITE / READ handshake).'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['as7262']
    tags = ['IC', 'Sensor', 'Optical']

    annotations = (
        ('data',                 'Virtual register'),
        ('status',               'STATUS'),
        ('warning',              'Warning'),
        ('conversion-start',     'Conversion start'),
        ('conversion-done',      'Conversion done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_CONV_START, ANN_CONV_DONE)),
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
        self.state = 'IDLE'         # IDLE | GET_PTR | WRITE | READ | IGNORE
        self.ptr = None             # hardware register pointer of the current transaction
        self.buf = []
        self.direction = None
        self.pending_write = None   # virtual address waiting for its data byte
        self.read_addr = None       # virtual address selected for the next READ data byte
        self.tx_busy = False        # last STATUS poll showed TX_VALID = 1
        self.conv_pending = False   # a one-shot start awaits its DATA_RDY read
        self.cal = {}               # virtual register -> byte (multi-byte reassembly)

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg, tag):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, tag]])

    def _virtual_write(self, ss, es, reg, value):
        self.put(ss, es, self.out_ann,
                 [ANN_DATA, ['W %s [0x%02X] = 0x%02X: %s' % (_name(reg), reg, value, _decode(reg, value)),
                             'W %s 0x%02X' % (_name(reg), value), 'W 0x%02X' % reg]])
        self.put(ss, es, self.out_python, ('VREG_WRITE', (reg, value)))
        if reg == 0x04 and ((value >> 2) & 3) == 3:
            self.put(ss, es, self.out_ann,
                     [ANN_CONV_START, ['conversion_start: CONTROL_SETUP write with BANK = 11', 'conversion_start', '\u2192CONV']])
            self.conv_pending = True

    def _virtual_read(self, ss, es, reg, value):
        self.cal[reg] = value
        self.put(ss, es, self.out_python, ('VREG_READ', (reg, value)))
        if 0x08 <= reg <= 0x13:
            if (reg - 0x08) % 2 == 1:
                hi = self.cal.get(reg - 1, 0)
                ch = CHANNELS[(reg - 0x08) // 2]
                word = (hi << 8) | value
                self.put(ss, es, self.out_ann, [ANN_DATA, ['R RAW_%s = %d' % (ch, word), 'RAW_%s %d' % (ch, word), '%d' % word]])
            return
        if 0x14 <= reg <= 0x2B:
            if (reg - 0x14) % 4 == 3:
                first = reg - 3
                raw = bytes(self.cal.get(first + i, 0) for i in range(4))
                f = struct.unpack('>f', raw)[0]
                ch = CHANNELS[(first - 0x14) // 4]
                self.put(ss, es, self.out_ann, [ANN_DATA, ['R CAL_%s = %.4f' % (ch, f), 'CAL_%s %.2f' % (ch, f), '%.2f' % f]])
            return
        self.put(ss, es, self.out_ann,
                 [ANN_DATA, ['R %s [0x%02X] = 0x%02X: %s' % (_name(reg), reg, value, _decode(reg, value)),
                             'R %s 0x%02X' % (_name(reg), value), 'R 0x%02X' % reg]])
        if reg == 0x04 and value & 0x02 and self.conv_pending:
            self.put(ss, es, self.out_ann,
                     [ANN_CONV_DONE, ['conversion_done: first CONTROL_SETUP read with DATA_RDY = 1', 'conversion_done', 'CONV\u2713']])
            self.conv_pending = False

    def _flush(self):
        buf, self.buf = self.buf, []
        if not buf or self.ptr is None:
            return
        ss, es = buf[0][0], buf[-1][1]
        value = buf[0][2]
        if self.ptr == HW_STATUS and self.direction == 'R':
            self.tx_busy = bool(value & 0x02)
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['STATUS TX_VALID=%d RX_VALID=%d' % ((value >> 1) & 1, value & 1),
                                   'ST TX%d RX%d' % ((value >> 1) & 1, value & 1), 'ST']])
        elif self.ptr == HW_WRITE and self.direction == 'W':
            if self.tx_busy:
                self._warn(ss, es, 'Write while TX_VALID was set (WRITE register still occupied)', 'BUSY')
                self.tx_busy = False
            if self.pending_write is not None:
                reg, self.pending_write = self.pending_write, None
                self._virtual_write(ss, es, reg, value)
            elif value & 0x80:
                self.pending_write = value & 0x7F
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Write address \u2192 %s (0x%02X)' % (_name(value & 0x7F), value & 0x7F),
                                     'WA 0x%02X' % (value & 0x7F), 'WA']])
            else:
                self.read_addr = value
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Read address \u2192 %s (0x%02X)' % (_name(value), value), 'RA 0x%02X' % value, 'RA']])
        elif self.ptr == HW_READ and self.direction == 'R':
            if self.read_addr is None:
                self._warn(ss, es, 'READ data byte without a preceding virtual read address', 'ADDR?')
            else:
                reg = self.read_addr
                # multi-byte values are read at ascending addresses without re-addressing
                self.read_addr = reg + 1 if 0x08 <= reg <= 0x2B else None
                self._virtual_read(ss, es, reg, value)
        else:
            self._warn(ss, es, '%s of hardware register 0x%02X' % ('Write' if self.direction == 'W' else 'Read', self.ptr), 'REG?')

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.buf = []

        elif ptype == 'START REPEAT':
            self._flush()
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata in ADDRS:
                self.state = 'GET_PTR'
                self.direction = 'W'
            else:
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata in ADDRS:
                self.state = 'READ'
                self.direction = 'R'
            else:
                self.state = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_PTR':
                self.ptr = pdata
                self.state = 'WRITE'
            elif self.state == 'WRITE':
                self.buf.append((ss, es, pdata))

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.buf.append((ss, es, pdata))

        elif ptype == 'STOP':
            self._flush()
            self.state = 'IDLE'
