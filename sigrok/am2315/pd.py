import sigrokdecode as srd

ADDRS = {0x5C}

FUNC_READ = 0x03
FUNC_WRITE = 0x10
ERRORS = {
    0x80: 'function code not supported', 0x81: 'read of an illegal address',
    0x82: 'write data out of range', 0x83: 'CRC error', 0x84: 'write disabled',
}

ANN_CMD       = 0
ANN_RESULT    = 1
ANN_WAKE      = 2
ANN_ERROR     = 3
ANN_WARNING   = 4
# Conformance-only annotations (specs/testing_framework.md, "Conformance
# Implementation" and specs/environmental/am2315_timing.conf): read_delay_start
# ends at the STOP of a 0x03 read-command write, read_delay_done starts at the
# following read address byte. Additive to the generic annotations.
ANN_READ_DELAY_START = 5
ANN_READ_DELAY_DONE  = 6


def _crc16(data):
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def _temperature(raw):
    t = (raw & 0x7FFF) / 10.0
    return -t if raw & 0x8000 else t


def _decode_registers(start, data):
    """Describe the register contents of a read response starting at register `start`."""
    parts = []
    regs = {start + i: b for i, b in enumerate(data)}

    def have(r, n):
        return all((r + i) in regs for i in range(n))

    if have(0x00, 2):
        parts.append('RH=%.1f %%RH' % (((regs[0x00] << 8) | regs[0x01]) / 10.0))
    if have(0x02, 2):
        parts.append('T=%.1f \u00b0C' % _temperature((regs[0x02] << 8) | regs[0x03]))
    if have(0x08, 2):
        parts.append('model 0x%04X' % ((regs[0x08] << 8) | regs[0x09]))
    if have(0x0A, 1):
        parts.append('version 0x%02X' % regs[0x0A])
    if have(0x0B, 4):
        parts.append('device id 0x%08X' % ((regs[0x0B] << 24) | (regs[0x0C] << 16) | (regs[0x0D] << 8) | regs[0x0E]))
    if have(0x10, 2):
        parts.append('user1 0x%04X' % ((regs[0x10] << 8) | regs[0x11]))
    if have(0x12, 2):
        parts.append('user2 0x%04X' % ((regs[0x12] << 8) | regs[0x13]))
    return ', '.join(parts)


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
    id = 'am2315'
    name = 'AM2315'
    longname = 'Aosong AM2315 humidity and temperature sensor'
    desc = 'Decode AM2315 I2C Modbus-style frames (wake-up, read/write registers, CRC-16) and temperature / humidity values.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['am2315']
    tags = ['IC', 'Sensor', 'Humidity']

    annotations = (
        ('cmd',     'Command'),
        ('result',  'Response / values'),
        ('wake',    'Wake-up'),
        ('error',   'Sensor error response'),
        ('warning', 'Warning'),
        ('read-delay-start', 'Read delay start (conformance: command written)'),
        ('read-delay-done',  'Read delay done (conformance: read address)'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CMD, ANN_RESULT, ANN_WAKE, ANN_ERROR)),
        ('timing',   'Timing',   (ANN_READ_DELAY_START, ANN_READ_DELAY_DONE)),
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
        self.cmd_buf  = []
        self.ss_block = None
        self.es       = None
        self.addr_nack = False
        self.last_cmd = None          # (function, start, count) of the last read command
        self.awaiting_read = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _finish_write(self, ss_stop, es_stop):
        buf = self.cmd_buf
        self.databuf = list(buf)
        if not buf:
            if self.addr_nack:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WAKE, ['Wake-up: address not acknowledged (sensor asleep)', 'Wake-up', 'WAKE']])
            return
        fn = buf[0]
        if fn == FUNC_READ and len(buf) == 3:
            self.last_cmd = (fn, buf[1], buf[2])
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['Read %d register(s) from 0x%02X' % (buf[2], buf[1]), 'RD 0x%02X' % buf[1]]])
            self.put(ss_stop, es_stop, self.out_ann,
                     [ANN_READ_DELAY_START, ['read_delay_start: command written, wait >= 1.5 ms', 'read_delay_start', 'RD\u25b6']])
            self.awaiting_read = True
        elif fn == FUNC_WRITE and len(buf) >= 5:
            count = buf[2]
            body, crc = buf[:-2], buf[-2] | (buf[-1] << 8)
            ok = len(buf) == count + 5 and _crc16(body) == crc
            self.last_cmd = (fn, buf[1], count)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['Write %d register(s) at 0x%02X: %s (crc %s)' % (
                         count, buf[1], _decode_registers(buf[1], buf[3:3 + count]) or
                         ' '.join('0x%02X' % b for b in buf[3:3 + count]), 'ok' if ok else 'BAD'),
                         'WR 0x%02X' % buf[1]]])
            if not ok:
                self._warn(self.ss_block, self.es, 'CRC mismatch in write frame')
            self.awaiting_read = False
        else:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['Write %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'W %dB' % len(buf)]])
            self._warn(self.ss_block, self.es, 'Unexpected command frame')

    def _finish_read(self):
        buf = self.databuf
        if not buf:
            return
        fn = buf[0]
        if fn & 0x80:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_ERROR, ['Error response 0x%02X: %s' % (fn, ERRORS.get(fn, 'unknown')), 'ERR 0x%02X' % fn]])
            return
        cmd = self.last_cmd
        if fn == FUNC_READ and len(buf) >= 4 and buf[1] == len(buf) - 4:
            ok = _crc16(buf[:-2]) == (buf[-2] | (buf[-1] << 8))
            text = _decode_registers(cmd[1], buf[2:2 + buf[1]]) if cmd and cmd[0] == FUNC_READ else ''
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_RESULT, ['%s (crc %s)' % (text or 'Read response, %d bytes' % buf[1], 'ok' if ok else 'BAD'), 'Result']])
            if not ok:
                self._warn(self.ss_block, self.es, 'CRC mismatch in response')
        elif fn == FUNC_WRITE and len(buf) == 5:
            ok = _crc16(buf[:-2]) == (buf[-2] | (buf[-1] << 8))
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_RESULT, ['Write response: %d register(s) at 0x%02X (crc %s)' % (buf[2], buf[1], 'ok' if ok else 'BAD'), 'Wr OK']])
            if not ok:
                self._warn(self.ss_block, self.es, 'CRC mismatch in response')
        else:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_RESULT, ['Read %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'R %dB' % len(buf)]])
            self._warn(self.ss_block, self.es, 'Unexpected response frame')

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self.databuf = []
            self.cmd_buf = []
            self.is_read = False
            self.addr_nack = False
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.state = 'GET_DATA_READ' if self.is_read else 'GET_DATA_WRITE'
            if self.is_read and self.awaiting_read:
                self.put(ss, es, self.out_ann,
                         [ANN_READ_DELAY_DONE, ['read_delay_done: read address sent', 'read_delay_done', 'RD\u2713']])
                self.awaiting_read = False

        elif ptype == 'NACK':
            if self.state == 'GET_DATA_WRITE' and not self.cmd_buf:
                self.addr_nack = True

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_DATA_WRITE':
                self.cmd_buf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            if self.state == 'GET_DATA_WRITE':
                self.is_read = False
                self.es = es
                self._finish_write(ss, es)
            elif self.state == 'GET_DATA_READ':
                self.is_read = True
                self._finish_read()
            self.state = 'IDLE'
            self.databuf = []
            self.cmd_buf = []
