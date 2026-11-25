import sigrokdecode as srd

ADDRS = {0x54}

CMD_NAMES = {
    0xE3: 'Hold temperature measurement',
    0xF3: 'No-hold temperature measurement',
    0xE5: 'Hold humidity + temperature measurement',
    0xF5: 'No-hold humidity + temperature measurement',
}
CMD_READ_REG = 0xA7
CMD_WRITE_REG = 0xA6
CMD_STOP_PERIODIC = 0x30
CMD_READ_ID = 0xD7
CMD_SOFT_RESET = 0xFE

REG_NAMES = {
    0x00: 'resolution', 0x02: 'periodic / polarity', 0x03: 'alert enables',
    0x05: 'T high reset (low byte)', 0x06: 'T high reset (high byte)',
    0x07: 'T high trigger (low byte)', 0x08: 'T high trigger (high byte)',
    0x09: 'T low trigger (low byte)', 0x0A: 'T low trigger (high byte)',
    0x0B: 'T low reset (low byte)', 0x0C: 'T low reset (high byte)',
    0x0D: 'RH high reset (low byte)', 0x0E: 'RH high reset (high byte)',
    0x0F: 'RH high trigger (low byte)', 0x10: 'RH high trigger (high byte)',
    0x11: 'RH low trigger (low byte)', 0x12: 'RH low trigger (high byte)',
    0x13: 'RH low reset (low byte)', 0x14: 'RH low reset (high byte)',
}
RES_BITS = {0: 8, 1: 10, 2: 12, 3: 14}
PERIODS = {0: '0.5 s', 1: '1 s', 2: '2.5 s'}
ALERT_NAMES = ((0x01, 'RH low'), (0x02, 'RH high'), (0x04, 'T low'), (0x08, 'T high'))

ANN_CMD     = 0
ANN_RESULT  = 1
ANN_STATUS  = 2
ANN_WARNING = 3


def crc8(data):
    crc = 0xFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1D) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def _humidity(raw):
    return raw / 16383.0 * 100.0


def _temperature(raw):
    return raw / 16383.0 * 165.0 - 40.0


def _decode_reg(reg, value):
    if reg == 0x00:
        return 'resolution: RH %d bit, T %d bit' % (RES_BITS[(value >> 2) & 3], RES_BITS[value & 3])
    if reg == 0x02:
        return 'periodic %s, period %s, ALERT active %s' % (
            'ON' if value & 0x80 else 'off', PERIODS.get((value >> 4) & 3, '?'), 'high' if value & 1 else 'low')
    if reg == 0x03:
        names = [n for bit, n in ALERT_NAMES if value & bit]
        return 'alert enables: ' + (', '.join(names) if names else 'none')
    return REG_NAMES.get(reg, 'register') + ' = 0x%02X' % value


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
    id = 'hs40xx'
    name = 'HS40xx'
    longname = 'Renesas HS40xx humidity and temperature sensor'
    desc = 'Decode HS4001/HS4003 I2C commands, register access, CRC-checked measurement frames and periodic mode.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['hs40xx']
    tags = ['IC', 'Sensor', 'Humidity']

    annotations = (
        ('cmd',     'Command'),
        ('result',  'Measurement result'),
        ('status',  'Register / ID'),
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
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.cmd_buf  = []
        self.context  = None       # ('M', cmd) | ('R', reg) | 'ID' — set by the last write transaction
        self.regs     = {}         # last known register values
        self.periodic = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _alerts_enabled(self):
        return bool(self.regs.get(0x03, 0) & 0x0F)

    def _decode_measurement(self, cmd, buf):
        expected = 3 if cmd in (0xE3, 0xF3) else 5
        if len(buf) != expected:
            self._warn(self.ss_block, self.es, 'Unexpected result length %d (expected %d bytes)' % (len(buf), expected))
            return
        self._decode_frame(buf, expected == 3)

    def _decode_frame(self, buf, temp_only, status=None):
        if temp_only:
            data = [0, 0, buf[0], buf[1]]
            parts = ['T=%.2f \u00b0C' % _temperature(((buf[0] & 0x3F) << 8) | buf[1])]
        else:
            data = list(buf[:4])
            parts = ['RH=%.2f %%RH' % _humidity(((buf[0] & 0x3F) << 8) | buf[1]),
                     'T=%.2f \u00b0C' % _temperature(((buf[2] & 0x3F) << 8) | buf[3])]
        ok = crc8(data) == buf[-1]
        parts.append('CRC ok' if ok else 'CRC BAD')
        if status is not None:
            names = [n for bit, n in ALERT_NAMES if status & bit]
            parts.insert(0, 'alerts: ' + (', '.join(names) if names else 'none'))
        self.put(self.ss_block, self.es, self.out_ann, [ANN_RESULT, [', '.join(parts), 'Result']])
        if not ok:
            self._warn(self.ss_block, self.es, 'CRC mismatch (got 0x%02X, expected 0x%02X)' % (buf[-1], crc8(data)))

    def _decode_read(self, buf):
        ctx = self.context
        if not buf:
            if isinstance(ctx, tuple) and ctx[0] == 'M':
                self._warn(self.ss_block, self.es, 'No-hold read not acknowledged (data not ready)')
            return
        if isinstance(ctx, tuple) and ctx[0] == 'M':
            self._decode_measurement(ctx[1], buf)
        elif isinstance(ctx, tuple) and ctx[0] == 'R':
            self.reg_ptr = ctx[1]
            value = buf[0]
            self.regs[ctx[1]] = value
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_STATUS, ['Register 0x%02X read: %s' % (ctx[1], _decode_reg(ctx[1], value)), 'R 0x%02X' % ctx[1]]])
        elif ctx == 'ID':
            if len(buf) != 4:
                self._warn(self.ss_block, self.es, 'ID frame needs 4 bytes, got %d' % len(buf))
                return
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_STATUS, ['Sensor ID 0x%08X' % int.from_bytes(bytes(buf), 'big'), 'ID']])
        elif self.periodic:
            n = 6 if self._alerts_enabled() else 5
            if len(buf) != n:
                self._warn(self.ss_block, self.es, 'Unexpected periodic frame length %d (expected %d bytes)' % (len(buf), n))
                return
            if n == 6:
                self._decode_frame(buf[1:], False, buf[0] & 0x0F)
            else:
                self._decode_frame(buf, False)
        else:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_RESULT, ['Read %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'R %dB' % len(buf)]])
            self._warn(self.ss_block, self.es, 'Read without a preceding command or periodic mode')

    def _decode_write(self, cmd):
        b0 = cmd[0]
        self.databuf = list(cmd)
        self.reg_ptr = None
        if self.periodic and b0 != CMD_STOP_PERIODIC:
            self._warn(self.ss_block, self.es, 'Command 0x%02X sent while periodic mode is active (only data fetch and 0x30 are accepted)' % b0)
        if b0 in CMD_NAMES:
            self.context = ('M', b0)
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, [CMD_NAMES[b0] + ' (0x%02X)' % b0, 'CMD 0x%02X' % b0]])
        elif b0 == CMD_READ_REG:
            if len(cmd) != 2:
                self._warn(self.ss_block, self.es, 'Register read needs an address byte')
                self.context = None
                return
            self.context = ('R', cmd[1])
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['Read register 0x%02X (%s)' % (cmd[1], REG_NAMES.get(cmd[1], 'undocumented')), 'RR 0x%02X' % cmd[1]]])
        elif b0 == CMD_WRITE_REG:
            if len(cmd) != 3:
                self._warn(self.ss_block, self.es, 'Register write needs an address and a data byte')
                self.context = None
                return
            reg, value = cmd[1], cmd[2]
            self.reg_ptr = reg
            self.databuf = [value]
            self.regs[reg] = value
            if reg == 0x02:
                self.periodic = bool(value & 0x80)
            self.context = None
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_STATUS, ['Write register 0x%02X: %s' % (reg, _decode_reg(reg, value)), 'W 0x%02X' % reg]])
        elif b0 == CMD_STOP_PERIODIC:
            self.periodic = False
            self.context = None
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, ['Stop periodic measurements (0x30)', 'STOP 0x30']])
        elif b0 == CMD_READ_ID:
            self.context = 'ID'
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, ['Read sensor ID (0xD7)', 'ID 0xD7']])
        elif b0 == CMD_SOFT_RESET:
            self.periodic = False
            self.regs = {}
            self.context = None
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, ['Soft reset (0xFE)', 'RESET']])
        else:
            self.context = None
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, ['Command 0x%02X: unknown' % b0, 'CMD 0x%02X' % b0]])
            self._warn(self.ss_block, self.es, 'Undocumented command 0x%02X' % b0)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ'):
            return
        if self.is_read:
            self.databuf = list(self.databuf)
            self._decode_read(self.databuf)
            return
        if not self.cmd_buf:
            self._warn(self.ss_block, self.es, 'Address-only write: the HS40xx expects a command byte')
            return
        self._decode_write(self.cmd_buf)

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
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.databuf = []
            self.state = 'GET_DATA_READ' if self.is_read else 'GET_DATA_WRITE'

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
