import sigrokdecode as srd

ADDRS = {0x44, 0x45}

SINGLE = {
    0x2400: 'single shot, high repeatability', 0x240B: 'single shot, medium repeatability', 0x2416: 'single shot, low repeatability',
    0x2C06: 'single shot (clock stretching), high repeatability', 0x2C0D: 'single shot (clock stretching), medium repeatability',
    0x2C10: 'single shot (clock stretching), low repeatability',
}
PERIODIC = {
    0x2032: '0.5 mps high', 0x2024: '0.5 mps medium', 0x202F: '0.5 mps low',
    0x2130: '1 mps high', 0x2126: '1 mps medium', 0x212D: '1 mps low',
    0x2236: '2 mps high', 0x2220: '2 mps medium', 0x222B: '2 mps low',
    0x2334: '4 mps high', 0x2322: '4 mps medium', 0x2329: '4 mps low',
    0x2737: '10 mps high', 0x2721: '10 mps medium', 0x272A: '10 mps low',
}
OTHER = {
    0x2B32: 'periodic ART (4 mps)', 0xE000: 'fetch data', 0x3093: 'break (stop periodic)', 0x30A2: 'soft reset',
    0x306D: 'heater enable', 0x3066: 'heater disable', 0xF32D: 'read status', 0x3041: 'clear status',
}
CMDS = {}
for _d in (SINGLE, PERIODIC, OTHER):
    CMDS.update(_d)

ANN_CMD       = 0
ANN_RESULT    = 1
ANN_STATUS    = 2
ANN_POLL      = 3
ANN_WARNING   = 4
# Conformance-only annotations (specs/testing_framework.md, "Conformance
# Implementation" and specs/humidity/sht31_timing.conf): conversion_start at
# the STOP after a single-shot command, conversion_done at the first read
# header the chip ACKs afterwards. Additive to the generic annotations.
ANN_CONVERSION_START = 5
ANN_CONVERSION_DONE  = 6


def _crc8(data):
    crc = 0xFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ 0x31) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def _decode_status(v):
    parts = []
    if v & 0x8000: parts.append('alert pending')
    if v & 0x2000: parts.append('heater on')
    if v & 0x0800: parts.append('RH alert')
    if v & 0x0400: parts.append('T alert')
    if v & 0x0010: parts.append('reset detected')
    if v & 0x0002: parts.append('command failed')
    if v & 0x0001: parts.append('write checksum failed')
    return 'Status 0x%04X: %s' % (v, ', '.join(parts) if parts else 'none')


def _decode_result(data):
    t_crc = 'ok' if _crc8(data[0:2]) == data[2] else 'BAD'
    h_crc = 'ok' if _crc8(data[3:5]) == data[5] else 'BAD'
    raw_t = (data[0] << 8) | data[1]
    raw_h = (data[3] << 8) | data[4]
    t = -45.0 + 175.0 * raw_t / 65535.0
    h = 100.0 * raw_h / 65535.0
    return 'T=%.2f \u00b0C (crc %s) RH=%.1f %%RH (crc %s)' % (t, t_crc, h, h_crc), (t_crc == 'ok' and h_crc == 'ok')


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
    id = 'sht31'
    name = 'SHT31'
    longname = 'Sensirion SHT31 temperature and humidity sensor'
    desc = 'Decode SHT31 I2C commands, 6-byte results with CRC check, status register and measurement polling.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['sht31']
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
        self.cmd_hi = None
        self.cmd_bytes = 0

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_ADDR_DONE'):
            return
        buf = self.databuf
        if self.is_read:
            if not buf:
                return
            cmd = self.reg_ptr
            if len(buf) >= 6:
                text, ok = _decode_result(buf)
                self.put(self.ss_block, self.es, self.out_ann, [ANN_RESULT, [text, 'Result']])
                if not ok:
                    self._warn(self.ss_block, self.es, 'CRC mismatch in result')
            elif len(buf) == 3 and cmd == 0xF32D:
                v = (buf[0] << 8) | buf[1]
                self.put(self.ss_block, self.es, self.out_ann, [ANN_STATUS, [_decode_status(v), 'Status 0x%04X' % v]])
                if _crc8(buf[0:2]) != buf[2]:
                    self._warn(self.ss_block, self.es, 'CRC mismatch in status')
            else:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_RESULT, ['Read %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'R %dB' % len(buf)]])
            return
        # write phase
        code = self.reg_ptr
        if code is None:
            return
        if code in CMDS:
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, ['Command 0x%04X: %s' % (code, CMDS[code]), 'CMD 0x%04X' % code]])
        else:
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, ['Command 0x%04X: unknown' % code, 'CMD 0x%04X' % code]])
            self._warn(self.ss_block, self.es, 'Unknown command 0x%04X' % code)
        if buf:
            self._warn(self.ss_block, self.es, 'Unexpected extra bytes after command')
        if code in SINGLE:
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
            self.cmd_hi = None
            self.cmd_bytes = 0
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
                self.reg_ptr_new = None
                self.state = 'GET_DATA_WRITE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_DATA_WRITE':
                if self.cmd_bytes == 0:
                    self.cmd_hi = pdata
                    self.cmd_bytes = 1
                elif self.cmd_bytes == 1:
                    self.reg_ptr = (self.cmd_hi << 8) | pdata
                    self.cmd_bytes = 2
                else:
                    self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
