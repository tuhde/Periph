import sigrokdecode as srd

ADDRS = {0x40}

# Single-byte commands and two-byte commands (first byte 0xFA / 0xFC / 0x84).
CMDS = {
    0xE5: 'measure RH (hold master)', 0xF5: 'measure RH (no hold master)',
    0xE3: 'measure temperature (hold master)', 0xF3: 'measure temperature (no hold master)',
    0xE0: 'read temperature from previous RH measurement', 0xFE: 'software reset',
    0xE6: 'write user register 1', 0xE7: 'read user register 1',
    0xFA0F: 'read electronic ID, first access', 0xFCC9: 'read electronic ID, second access',
    0x84B8: 'read firmware revision',
}
TWO_BYTE_FIRST = {0xFA, 0xFC, 0x84}
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
# Implementation" and specs/humidity/si7021_timing.conf): conversion_start at
# the STOP after a no-hold-master measurement command (0xF5 / 0xF3),
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
    parts = ['RES %s bit (RH/T)' % RESOLUTIONS[res], 'VDD low' if v & 0x40 else 'VDD ok',
             'heater on' if v & 0x04 else 'heater off']
    return 'User register 0x%02X: %s' % (v, ', '.join(parts))


def _decode_result(cmd, data):
    """Decode a 3-byte RH or temperature result; returns (text, crc_ok)."""
    crc_ok = _crc8(data[0:2]) == data[2]
    code = (data[0] << 8) | data[1]
    crc = 'ok' if crc_ok else 'BAD'
    if cmd in RH_RESULT:
        rh = min(100.0, max(0.0, 125.0 * code / 65536.0 - 6.0))
        return 'RH=%.1f %%RH (code 0x%04X, crc %s)' % (rh, code, crc), crc_ok
    t = 175.72 * code / 65536.0 - 46.85
    return 'T=%.2f \u00b0C (code 0x%04X, crc %s)' % (t, code, crc), crc_ok


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
    id = 'si7021'
    name = 'Si7021'
    longname = 'Silicon Labs Si7021 humidity and temperature sensor'
    desc = 'Decode Si7021 I2C commands, RH and temperature results with CRC check, user register, electronic ID and measurement polling.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['si7021']
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
            elif cmd == 0xE0 and len(buf) >= 2:
                code = (buf[0] << 8) | buf[1]
                t = 175.72 * code / 65536.0 - 46.85
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_RESULT, ['T=%.2f \u00b0C (code 0x%04X, no crc)' % (t, code), 'Result']])
                return
            elif cmd == 0xE7 and len(buf) == 1:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_STATUS, [_decode_user_reg(buf[0]), 'User 0x%02X' % buf[0]]])
                return
            elif cmd == 0xFA0F and len(buf) == 8:
                sna = bytes([buf[0], buf[2], buf[4], buf[6]])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_RESULT, ['ID SNA=%s' % sna.hex(), 'SNA']])
                return
            elif cmd == 0xFCC9 and len(buf) == 6:
                snb = bytes([buf[0], buf[1], buf[3], buf[4]])
                kind = {0x15: 'Si7021', 0x14: 'Si7020', 0x0D: 'Si7013'}.get(buf[0], 'unknown device')
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_RESULT, ['ID SNB=%s (%s)' % (snb.hex(), kind), 'SNB']])
                return
            elif cmd == 0x84B8 and len(buf) == 1:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_RESULT, ['Firmware 0x%02X%s' % (buf[0], ' (1.0)' if buf[0] == 0xFF else ''), 'FW']])
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
