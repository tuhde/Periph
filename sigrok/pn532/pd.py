import sigrokdecode as srd

ADDRS = {0x24}

ACK = (0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00)
NACK = (0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00)

COMMANDS = {
    0x00: 'Diagnose', 0x02: 'GetFirmwareVersion', 0x04: 'GetGeneralStatus', 0x06: 'ReadRegister',
    0x08: 'WriteRegister', 0x0C: 'ReadGPIO', 0x0E: 'WriteGPIO', 0x10: 'SetSerialBaudRate',
    0x12: 'SetParameters', 0x14: 'SAMConfiguration', 0x16: 'PowerDown', 0x32: 'RFConfiguration',
    0x58: 'RFRegulationTest', 0x56: 'InJumpForDEP', 0x46: 'InJumpForPSL', 0x4A: 'InListPassiveTarget',
    0x50: 'InATR', 0x4E: 'InPSL', 0x40: 'InDataExchange', 0x42: 'InCommunicateThru',
    0x44: 'InDeselect', 0x52: 'InRelease', 0x54: 'InSelect', 0x60: 'InAutoPoll',
    0x8C: 'TgInitAsTarget', 0x92: 'TgSetGeneralBytes', 0x86: 'TgGetData', 0x8E: 'TgSetData',
    0x94: 'TgSetMetaData', 0x88: 'TgGetInitiatorCommand', 0x90: 'TgResponseToInitiator',
    0x8A: 'TgGetTargetStatus',
}

# Responses whose first data byte is a Status byte.
STATUS_RESPONSES = {0x41, 0x43, 0x45, 0x47, 0x4F, 0x51, 0x53, 0x55, 0x57, 0x17, 0x87, 0x8F, 0x91, 0x93, 0x95}

STATUS_ERRORS = {
    0x00: 'OK', 0x01: 'timeout', 0x02: 'CRC error', 0x03: 'parity error', 0x04: 'bit count error',
    0x05: 'framing error', 0x06: 'bit collision', 0x07: 'buffer too small', 0x09: 'RF buffer overflow',
    0x0A: 'RF field absent timeout', 0x0B: 'RF protocol error', 0x0D: 'temperature error',
    0x0E: 'internal buffer overflow', 0x10: 'invalid parameter', 0x12: 'DEP command not supported',
    0x13: 'DEP data format mismatch', 0x14: 'MIFARE authentication error', 0x23: 'UID check byte wrong',
    0x25: 'invalid device state', 0x26: 'operation not allowed', 0x27: 'command not acceptable',
    0x29: 'target released by initiator', 0x2A: 'card ID mismatch', 0x2B: 'card discarded',
    0x2C: 'NFCID3 mismatch',
}

BAUDS = {0x00: '106 kbps type A', 0x01: '212 kbps FeliCa', 0x02: '424 kbps FeliCa',
         0x03: '106 kbps type B', 0x04: '106 kbps Jewel'}

MIFARE_OPS = {0x60: 'MIFARE auth key A', 0x61: 'MIFARE auth key B', 0x30: 'READ', 0xA0: 'MIFARE WRITE',
              0xA2: 'NTAG WRITE', 0xC0: 'DECREMENT', 0xC1: 'INCREMENT', 0xC2: 'RESTORE', 0xB0: 'TRANSFER'}

ANN_COMMAND  = 0
ANN_RESPONSE = 1
ANN_ACK      = 2
ANN_STATUS   = 3
ANN_WARNING  = 4


def _hex(data):
    return ' '.join('%02X' % b for b in data)


def _cmd_name(code):
    return COMMANDS.get(code, 'command 0x%02X' % code)


def _status_text(s):
    code = s & 0x3F
    flags = []
    if s & 0x80:
        flags.append('NAD')
    if s & 0x40:
        flags.append('MI')
    text = 'Status 0x%02X: %s' % (s, STATUS_ERRORS.get(code, 'error 0x%02X' % code))
    if flags:
        text += ' (%s)' % ', '.join(flags)
    return text


def _decode_request(cmd, p):
    """Human-readable request parameters."""
    if cmd == 0x4A and len(p) >= 2:
        text = 'MaxTg %d, %s' % (p[0], BAUDS.get(p[1], 'BrTy 0x%02X' % p[1]))
        if len(p) > 2:
            text += ', InitiatorData %s' % _hex(p[2:])
        return text
    if cmd == 0x40 and len(p) >= 2:
        tg, op = p[0], p[1]
        name = MIFARE_OPS.get(op)
        if op in (0x60, 0x61) and len(p) >= 12:
            return 'Tg %d, %s block %d, key %s, UID %s' % (tg, name, p[2], _hex(p[3:9]), _hex(p[9:13]))
        if op == 0x30 and len(p) >= 3:
            return 'Tg %d, READ block/page %d' % (tg, p[2])
        if op in (0xA0, 0xA2) and len(p) >= 3:
            return 'Tg %d, %s block/page %d: %s' % (tg, name, p[2], _hex(p[3:]))
        return 'Tg %d, data %s' % (tg, _hex(p[1:]))
    if cmd == 0x42:
        return 'raw %s' % _hex(p)
    if cmd in (0x44, 0x52, 0x54) and p:
        return 'Tg %d%s' % (p[0], ' (all)' if p[0] == 0 else '')
    if cmd == 0x14 and p:
        modes = {1: 'normal', 2: 'virtual card', 3: 'wired card', 4: 'dual card'}
        text = 'mode %s' % modes.get(p[0], '0x%02X' % p[0])
        if len(p) > 1:
            text += ', timeout %d ms' % (p[1] * 50)
        if len(p) > 2:
            text += ', IRQ %s' % ('on' if p[2] else 'off')
        return text
    if cmd == 0x32 and p:
        if p[0] == 0x01 and len(p) > 1:
            return 'RF field %s%s' % ('on' if p[1] & 0x02 else 'off', ', AutoRFCA' if p[1] & 0x01 else '')
        if p[0] == 0x05 and len(p) > 3:
            r = lambda v: 'inf' if v == 0xFF else str(v)
            return 'MaxRetries ATR %s, PSL %s, passive activation %s' % (r(p[1]), r(p[2]), r(p[3]))
        return 'item 0x%02X: %s' % (p[0], _hex(p[1:]))
    if cmd == 0x06:
        return 'addresses ' + ', '.join('0x%02X%02X' % (p[i], p[i + 1]) for i in range(0, len(p) - 1, 2))
    if cmd == 0x08:
        return ', '.join('0x%02X%02X = 0x%02X' % (p[i], p[i + 1], p[i + 2]) for i in range(0, len(p) - 2, 3))
    if cmd == 0x16 and p:
        return 'WakeUpEnable 0x%02X' % p[0]
    if cmd == 0x00 and p:
        tests = {0x00: 'communication line', 0x01: 'ROM', 0x02: 'RAM', 0x04: 'polling', 0x05: 'echo back',
                 0x06: 'attention', 0x07: 'antenna'}
        return 'test %s%s' % (tests.get(p[0], '0x%02X' % p[0]), (', ' + _hex(p[1:])) if len(p) > 1 else '')
    return _hex(p) if p else 'no parameters'


def _decode_list_response(d):
    if not d:
        return 'no data'
    n = d[0]
    if n == 0:
        return 'NbTg 0 (no target)'
    parts = ['NbTg %d' % n]
    i = 1
    for _ in range(n):
        if i + 4 >= len(d):
            break
        tg, sens, sel, ln = d[i], (d[i + 1] << 8) | d[i + 2], d[i + 3], d[i + 4]
        uid = d[i + 5:i + 5 + ln]
        parts.append('Tg %d SENS_RES 0x%04X SEL_RES 0x%02X UID %s' % (tg, sens, sel, _hex(uid)))
        i += 5 + ln
        if sel & 0x20 and i < len(d):
            i += d[i]
    return ', '.join(parts)


def _decode_response(code, d):
    """Human-readable response data (code = command + 1)."""
    if code == 0x03 and len(d) >= 4:
        sup = [n for bit, n in ((0, 'ISO14443A'), (1, 'ISO14443B'), (2, 'ISO18092')) if d[3] & (1 << bit)]
        return 'IC 0x%02X, firmware %d.%d, support 0x%02X (%s)' % (d[0], d[1], d[2], d[3], ', '.join(sup))
    if code == 0x05 and len(d) >= 3:
        return 'Err 0x%02X, field %s, NbTg %d' % (d[0], 'present' if d[1] else 'absent', d[2])
    if code == 0x4B:
        return _decode_list_response(d)
    if code in STATUS_RESPONSES and d:
        text = _status_text(d[0])
        if len(d) > 1:
            text += ', data %s' % _hex(d[1:])
        return text
    if code == 0x07:
        return 'values %s' % _hex(d)
    if code == 0x01:
        return 'result %s' % _hex(d)
    return _hex(d) if d else 'no data'


class Decoder(srd.Decoder):
    api_version = 3
    id = 'pn532'
    name = 'PN532'
    longname = 'NXP PN532 NFC/RFID controller'
    desc = 'Decode PN532 I2C command/response frames, ACK/NACK, status bytes and target data.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pn532']
    tags = ['IC', 'RFID']

    annotations = (
        ('command',  'Command frame'),
        ('response', 'Response frame'),
        ('ack',      'ACK / NACK frame'),
        ('status',   'Status byte'),
        ('warning',  'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_COMMAND, ANN_RESPONSE, ANN_ACK)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.is_read = False
        self.databuf = []
        self.ss_block = None
        self.pending = None      # command code awaiting ACK / response
        self.acked = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _put(self, cls, texts, py=None):
        self.put(self.ss_block, self.es, self.out_ann, [cls, texts])
        if py is not None:
            self.put(self.ss_block, self.es, self.out_python, py)

    def _warn(self, msg, tag):
        self._put(ANN_WARNING, [msg, tag])

    def _frame_body(self, f):
        """Validate a normal frame; return (tfi, data) or None."""
        if len(f) < 6 or tuple(f[:3]) != (0x00, 0x00, 0xFF):
            self._warn('Bad frame preamble: %s' % _hex(f[:3]), 'PREAMBLE')
            return None
        ln, lcs = f[3], f[4]
        if (ln + lcs) & 0xFF:
            self._warn('Bad length checksum LEN 0x%02X LCS 0x%02X' % (ln, lcs), 'LCS')
            return None
        if len(f) < 6 + ln:
            return None
        body = f[5:5 + ln]
        dcs = f[5 + ln]
        if (sum(body) + dcs) & 0xFF:
            self._warn('Bad data checksum DCS 0x%02X' % dcs, 'DCS')
        tfi = body[0]
        if tfi not in (0xD4, 0xD5, 0x7F):
            self._warn('Unexpected TFI 0x%02X' % tfi, 'TFI')
        return tfi, body[1:]

    def _finish_write(self):
        f = self.databuf
        if tuple(f) == ACK:
            self._put(ANN_ACK, ['ACK frame (abort the running command)', 'ACK abort', 'ACK'], ('ABORT', None))
            self.pending = None
            return
        if tuple(f) == NACK:
            self._put(ANN_ACK, ['NACK frame (resend the last response)', 'NACK', 'NAK'], ('NACK', None))
            return
        r = self._frame_body(f)
        if r is None:
            return
        tfi, data = r
        if tfi != 0xD4 or not data:
            return
        cmd, params = data[0], data[1:]
        name = _cmd_name(cmd)
        self._put(ANN_COMMAND, ['%s: %s' % (name, _decode_request(cmd, params)), name, 'C%02X' % cmd],
                  ('COMMAND', (cmd, bytes(params))))
        self.pending = cmd
        self.acked = False

    def _finish_read(self):
        buf = self.databuf
        if not buf:
            return
        st = buf[0]
        ready = bool(st & 0x01)
        if len(buf) == 1:
            self._put(ANN_STATUS, ['Status byte 0x%02X: %s' % (st, 'frame ready' if ready else 'not ready'),
                                   'ready' if ready else 'busy', 'RDY' if ready else 'BSY'], ('STATUS', ready))
            return
        if not ready:
            self._put(ANN_STATUS, ['Status byte 0x%02X: not ready (%d bytes ignored)' % (st, len(buf) - 1),
                                   'busy', 'BSY'], ('STATUS', False))
            return
        f = buf[1:]
        if tuple(f[:6]) == ACK:
            if self.pending is None:
                self._warn('ACK read without a preceding command', 'ACK?')
            self._put(ANN_ACK, ['ACK frame for %s' % (_cmd_name(self.pending) if self.pending is not None else '?'),
                                'ACK', 'ACK'], ('ACK', self.pending))
            self.acked = True
            return
        if len(f) >= 5 and tuple(f[:3]) == (0x00, 0x00, 0xFF) and len(f) < 6 + f[3]:
            self._put(ANN_RESPONSE, ['Response header: LEN %d (the host resends a NACK and reads the frame)' % f[3],
                                     'Header LEN %d' % f[3], 'HDR'], ('HEADER', f[3]))
            return
        if self.pending is None:
            self._warn('Frame read without a preceding command', 'NO CMD')
        elif not self.acked:
            self._warn('Response frame without an ACK', 'NO ACK')
        r = self._frame_body(f)
        if r is None:
            return
        tfi, data = r
        if tfi == 0x7F:
            self._put(ANN_RESPONSE, ['Error frame (application-level error)', 'Error frame', 'ERR'], ('ERROR_FRAME', None))
            self._warn('PN532 error frame', 'ERR FRAME')
            self.pending = None
            return
        if tfi != 0xD5 or not data:
            return
        code, d = data[0], data[1:]
        name = _cmd_name(code - 1)
        if self.pending is not None and code != self.pending + 1:
            self._warn('Response 0x%02X does not match %s' % (code, _cmd_name(self.pending)), 'CODE')
        text = _decode_response(code, d)
        self._put(ANN_RESPONSE, ['%s response: %s' % (name, text), '%s resp' % name, 'R%02X' % code],
                  ('RESPONSE', (code, bytes(d))))
        if code in STATUS_RESPONSES and d and d[0] & 0x3F:
            self._warn('%s: %s' % (name, _status_text(d[0])), 'STATUS')
        self.pending = None

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ') or self.ss_block is None:
            return
        if self.is_read:
            self._finish_read()
        else:
            self._finish_write()

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf = []
            self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'
        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if self.state != 'GET_ADDR' or pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.is_read = ptype == 'ADDRESS READ'
            self.state = 'GET_DATA_READ' if self.is_read else 'GET_DATA_WRITE'
        elif ptype == 'DATA WRITE' and self.state == 'GET_DATA_WRITE':
            self.databuf.append(pdata)
        elif ptype == 'DATA READ' and self.state == 'GET_DATA_READ':
            self.databuf.append(pdata)
        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
