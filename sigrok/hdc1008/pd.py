"""HDC1008 humidity and temperature sensor (Texas Instruments) sigrok protocol decoder."""
import sigrokdecode as srd

ADDRS = {0x40, 0x41, 0x42, 0x43}

REGS = {0x00: 'Temperature', 0x01: 'Humidity', 0x02: 'Configuration',
        0xFB: 'Serial ID 1', 0xFC: 'Serial ID 2', 0xFD: 'Serial ID 3'}

ANN_POINTER = 0
ANN_CONFIG  = 1
ANN_RESULT  = 2
ANN_SERIAL  = 3
ANN_POLL    = 4
ANN_WARNING = 5

HRES = {0: '14', 1: '11', 2: '8'}


def _decode_config(v):
    parts = []
    if v & 0x8000: parts.append('RST')
    parts.append('heater on' if v & 0x2000 else 'heater off')
    parts.append('MODE sequence' if v & 0x1000 else 'MODE single')
    parts.append('BTST VDD<2.8 V' if v & 0x0800 else 'BTST ok')
    parts.append('T %s bit' % ('11' if v & 0x0400 else '14'))
    parts.append('RH %s bit' % HRES.get((v >> 8) & 3, 'reserved'))
    return 'Config 0x%04X: %s' % (v, ', '.join(parts))


def _t(raw):
    return raw / 65536.0 * 165.0 - 40.0


def _rh(raw):
    return raw / 65536.0 * 100.0


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
    id = 'hdc1008'
    name = 'HDC1008'
    longname = 'Texas Instruments HDC1008 humidity and temperature sensor'
    desc = 'Decode HDC1008 I2C pointer writes, configuration, temperature/humidity results and serial ID.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['hdc1008']
    tags = ['IC', 'Sensor', 'Humidity']

    annotations = (
        ('pointer', 'Register pointer'),
        ('config',  'Configuration'),
        ('result',  'Measurement result'),
        ('serial',  'Serial ID word'),
        ('poll',    'Measurement polling'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_POINTER, ANN_CONFIG, ANN_RESULT, ANN_SERIAL, ANN_POLL)),
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
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.databuf = []
        self.cmd_buf = []
        self.ss_block = None
        self.ss_addr = None
        self.poll_pending = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ'):
            return
        ss, es = self.ss_block, self.es
        if self.is_read:
            buf = self.databuf
            ptr = self.reg_ptr
            if not buf:
                return
            if ptr == 0x00 and len(buf) == 4:
                self.put(ss, es, self.out_ann, [ANN_RESULT, ['T=%.2f \u00b0C, RH=%.1f %%RH' % (_t((buf[0] << 8) | buf[1]), _rh((buf[2] << 8) | buf[3])), 'T/RH']])
            elif ptr == 0x00 and len(buf) == 2:
                self.put(ss, es, self.out_ann, [ANN_RESULT, ['T=%.2f \u00b0C' % _t((buf[0] << 8) | buf[1]), 'T']])
            elif ptr == 0x01 and len(buf) == 2:
                self.put(ss, es, self.out_ann, [ANN_RESULT, ['RH=%.1f %%RH' % _rh((buf[0] << 8) | buf[1]), 'RH']])
            elif ptr == 0x02 and len(buf) == 2:
                v = (buf[0] << 8) | buf[1]
                self.put(ss, es, self.out_ann, [ANN_CONFIG, [_decode_config(v), 'Cfg 0x%04X' % v]])
                if v & 0x40FF:
                    self._warn(ss, es, 'Reserved configuration bits set')
            elif ptr in (0xFB, 0xFC, 0xFD) and len(buf) == 2:
                v = (buf[0] << 8) | buf[1]
                self.put(ss, es, self.out_ann, [ANN_SERIAL, ['%s word 0x%04X' % (REGS[ptr], v), 'SN 0x%04X' % v]])
            else:
                self.put(ss, es, self.out_ann, [ANN_RESULT, ['Read %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'R %dB' % len(buf)]])
            return
        buf = self.cmd_buf
        if not buf:
            return
        ptr = buf[0]
        self.reg_ptr = ptr
        name = REGS.get(ptr)
        if name is None:
            self.put(ss, es, self.out_ann, [ANN_POINTER, ['Pointer 0x%02X: unknown' % ptr, 'PTR 0x%02X' % ptr]])
            self._warn(ss, es, 'Unknown register pointer 0x%02X' % ptr)
            return
        note = ' (triggers measurement)' if ptr in (0x00, 0x01) else ''
        self.put(ss, es, self.out_ann, [ANN_POINTER, ['Pointer 0x%02X: %s%s' % (ptr, name, note), 'PTR 0x%02X' % ptr]])
        extra = buf[1:]
        if ptr == 0x02 and len(extra) == 2:
            v = (extra[0] << 8) | extra[1]
            self.put(ss, es, self.out_ann, [ANN_CONFIG, [_decode_config(v), 'Cfg 0x%04X' % v]])
            if v & 0x40FF:
                self._warn(ss, es, 'Reserved configuration bits set')
        elif extra:
            self._warn(ss, es, 'Unexpected data after pointer write (read-only register or wrong length)')

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es
        if ptype == 'ACK' and self.poll_pending:
            self.poll_pending = False
            return
        if ptype == 'NACK' and self.poll_pending:
            self.poll_pending = False
            self.put(self.ss_addr, es, self.out_ann, [ANN_WARNING, ['Read header NACKed (data not ready)', 'NACK']])
            return
        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf = []
            self.cmd_buf = []
            self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'
        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.ss_addr = ss
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.poll_pending = True
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
