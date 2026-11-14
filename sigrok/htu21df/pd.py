import sigrokdecode as srd

ADDRS = {0x40}

# Single-byte commands only (the chip has no two-byte commands).
CMDS = {
    0xE3: 'trigger temperature measurement (hold master)', 0xE5: 'trigger humidity measurement (hold master)',
    0xF3: 'trigger temperature measurement (no hold master)', 0xF5: 'trigger humidity measurement (no hold master)',
    0xE6: 'write user register', 0xE7: 'read user register', 0xFE: 'soft reset',
}
TWO_BYTE_FIRST = set()
# Commands after which the chip NACKs the read header until the conversion is done.
POLLED = {0xF5, 0xF3}
RH_RESULT = {0xE5, 0xF5}
T_RESULT = {0xE3, 0xF3}

ANN_CMD       = 0
ANN_RESULT    = 1
ANN_STATUS    = 2
ANN_POLL      = 3
ANN_WARNING   = 4
# Conformance-only annotations (specs/testing_framework.md, "Conformance
# Implementation" and specs/humidity/htu21df_timing.conf): conversion_start at
# the STOP after a no-hold-master trigger command (0xF3 / 0xF5),
# conversion_done at the first read header the chip ACKs afterwards. Additive
# to the generic annotations.
ANN_CONVERSION_START = 5
ANN_CONVERSION_DONE  = 6

RESOLUTIONS = {0: '12/14', 1: '8/12', 2: '10/13', 3: '11/11'}


def _crc8(data):
    crc = 0x00
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ 0x31) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def _decode_user_reg(v):
    res = (((v >> 7) & 1) << 1) | (v & 1)
    parts = ['RES %s bit (RH/T)' % RESOLUTIONS[res], 'VDD low (<2.25 V)' if v & 0x40 else 'VDD ok',
             'heater on' if v & 0x04 else 'heater off']
    return 'User register 0x%02X: %s' % (v, ', '.join(parts))


def _decode_result(cmd, data):
    """Decode a 3-byte temperature or humidity result; returns (text, crc_ok)."""
    crc_ok = _crc8(data[0:2]) == data[2]
    raw = (data[0] << 8) | data[1]
    code = raw & 0xFFFC
    crc = 'ok' if crc_ok else 'BAD'
    is_rh = bool(raw & 0x02)
    if cmd in RH_RESULT:
        text = 'RH=%.1f %%RH (code 0x%04X, crc %s)' % (-6.0 + 125.0 * code / 65536.0, code, crc)
    else:
        text = 'T=%.2f \u00b0C (code 0x%04X, crc %s)' % (-46.85 + 175.72 * code / 65536.0, code, crc)
    if is_rh != (cmd in RH_RESULT):
        text += ' [status bit 1 mismatch]'
    return text, crc_ok


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
    id = 'htu21df'
    name = 'HTU21D-F'
    longname = 'Measurement Specialties HTU21D-F humidity and temperature sensor'
    desc = 'Decode HTU21D-F I2C commands, temperature and humidity results with CRC check, user register and measurement polling.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['htu21df']
    tags = ['IC', 'Sensor', 'Humidity']

    annotations = (
        ('cmd',     'Command'),
        ('result',  'Measurement result'),
        ('status',  'Status register'),
        ('poll',    'Measurement polling'),
        ('warning', 'Warning'),
        ('conversion-start', 'Conversion start (conformance: conversion start)'),
        ('conversion-done',  'Conversion done (conformance: conversion end)'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CMD, ANN_RESULT, ANN_STATUS, ANN_POLL)),
        ('timing',   'Timing',   (ANN_CONVERSION_START, ANN_CONVERSION_DONE)),
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
        self.reg_ptr  = None       # last command code (used to interpret reads)
        self.databuf  = []
        self.ss_block = None
        self.ss_addr  = None
        self.awaiting_conversion = False
        self.poll_pending = False
        self.poll_noted = False
        self.cmd_buf = []

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _command_code(self):
        """Command code of the write phase: one byte, or two bytes for the ID / firmware commands."""
        b = self.cmd_buf
        if not b:
            return None
        if b[0] in TWO_BYTE_FIRST:
            return ((b[0] << 8) | b[1]) if len(b) > 1 else None
        return b[0]

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_ADDR_DONE'):
            return
        buf = self.databuf
        if self.is_read:
            if not buf:
                return
            cmd = self.reg_ptr
            if cmd in RH_RESULT or cmd in T_RESULT:
                if len(buf) >= 3:
                    text, ok = _decode_result(cmd, buf)
                    self.put(self.ss_block, self.es, self.out_ann, [ANN_RESULT, [text, 'Result']])
                    if not ok:
                        self._warn(self.ss_block, self.es, 'CRC mismatch in result')
                    return
            elif cmd == 0xE7 and len(buf) == 1:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_STATUS, [_decode_user_reg(buf[0]), 'User 0x%02X' % buf[0]]])
                return
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_RESULT, ['Read %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'R %dB' % len(buf)]])
            return
        # write phase
        code = self._command_code()
        if code is None:
            if self.cmd_buf:
                self._warn(self.ss_block, self.es, 'Incomplete command')
            return
        self.reg_ptr = code
        if code in CMDS:
            text = 'Command 0x%02X: %s' % (code, CMDS[code]) if code < 0x100 else 'Command 0x%04X: %s' % (code, CMDS[code])
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, [text, 'CMD 0x%02X' % (code & 0xFF)]])
        else:
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, ['Command 0x%02X: unknown' % (code & 0xFF), 'CMD 0x%02X' % (code & 0xFF)]])
            self._warn(self.ss_block, self.es, 'Undocumented command 0x%02X' % (code & 0xFF))
        extra = self.cmd_buf[(2 if code > 0xFF else 1):]
        if code == 0xE6 and len(extra) == 1:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_STATUS, [_decode_user_reg(extra[0]), 'User 0x%02X' % extra[0]]])
        elif code == 0xE6:
            self._warn(self.ss_block, self.es, 'Write user register needs exactly one data byte')
        elif extra:
            self._warn(self.ss_block, self.es, 'Unexpected extra bytes after command')
        if code in POLLED:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CONVERSION_START, ['conversion_start: STOP issued, measurement begins', 'conversion_start', 'CV\u25b6']])
            self.awaiting_conversion = True

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype == 'ACK' and self.poll_pending:
            self.poll_pending = False
            self.poll_noted = True
            if self.awaiting_conversion:
                self.put(self.ss_addr, es, self.out_ann,
                         [ANN_CONVERSION_DONE, ['conversion_done: read header ACKed', 'conversion_done', 'CV\u2713']])
                self.awaiting_conversion = False
            return
        if ptype == 'NACK' and self.poll_pending:
            self.poll_pending = False
            self.poll_noted = True
            self.put(self.ss_addr, es, self.out_ann, [ANN_POLL, ['Poll: read header NACKed (not ready)', 'poll NACK', 'NACK']])
            return

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
            self.ss_addr = ss
            self.poll_noted = False
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
