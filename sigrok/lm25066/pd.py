"""LM25066 PMBus power monitor sigrok protocol decoder (stacks on the i2c decoder)."""

import sigrokdecode as srd

ADDRS = set(range(0x10, 0x18)) | set(range(0x40, 0x48)) | set(range(0x50, 0x5B))

# code: (name, kind, length)   kind: send | byte | word | block ; length = block payload bytes
CMDS = {
    0x01: ('OPERATION', 'byte', 1),
    0x03: ('CLEAR_FAULTS', 'send', 0),
    0x19: ('CAPABILITY', 'byte', 1),
    0x43: ('VOUT_UV_WARN_LIMIT', 'word', 2),
    0x4F: ('OT_FAULT_LIMIT', 'word', 2),
    0x51: ('OT_WARN_LIMIT', 'word', 2),
    0x57: ('VIN_OV_WARN_LIMIT', 'word', 2),
    0x58: ('VIN_UV_WARN_LIMIT', 'word', 2),
    0x78: ('STATUS_BYTE', 'byte', 1),
    0x79: ('STATUS_WORD', 'word', 2),
    0x7A: ('STATUS_VOUT', 'byte', 1),
    0x7C: ('STATUS_INPUT', 'byte', 1),
    0x7D: ('STATUS_TEMPERATURE', 'byte', 1),
    0x7E: ('STATUS_CML', 'byte', 1),
    0x80: ('STATUS_MFR_SPECIFIC', 'byte', 1),
    0x88: ('READ_VIN', 'word', 2),
    0x8B: ('READ_VOUT', 'word', 2),
    0x8D: ('READ_TEMPERATURE_1', 'word', 2),
    0x99: ('MFR_ID', 'block', 3),
    0x9A: ('MFR_MODEL', 'block', 8),
    0x9B: ('MFR_REVISION', 'block', 2),
    0xD0: ('READ_VAUX', 'word', 2),
    0xD1: ('MFR_READ_IIN', 'word', 2),
    0xD2: ('MFR_READ_PIN', 'word', 2),
    0xD3: ('MFR_IIN_OC_WARN_LIMIT', 'word', 2),
    0xD4: ('MFR_PIN_OP_WARN_LIMIT', 'word', 2),
    0xD5: ('READ_PIN_PEAK', 'word', 2),
    0xD6: ('CLEAR_PIN_PEAK', 'send', 0),
    0xD7: ('GATE_MASK', 'byte', 1),
    0xD8: ('ALERT_MASK', 'word', 2),
    0xD9: ('DEVICE_SETUP', 'byte', 1),
    0xDA: ('BLOCK_READ', 'block', 12),
    0xDB: ('SAMPLES_FOR_AVG', 'byte', 1),
    0xDC: ('READ_AVG_VIN', 'word', 2),
    0xDD: ('READ_AVG_VOUT', 'word', 2),
    0xDE: ('READ_AVG_IIN', 'word', 2),
    0xDF: ('READ_AVG_PIN', 'word', 2),
    0xE0: ('BLACK_BOX_READ', 'block', 12),
    0xE1: ('READ_DIAGNOSTIC_WORD', 'word', 2),
    0xE2: ('AVG_BLOCK_READ', 'block', 12),
}
READ_ONLY = {0x19, 0x78, 0x79, 0x7A, 0x7C, 0x7D, 0x7E, 0x80, 0x88, 0x8B, 0x8D, 0x99, 0x9A, 0x9B,
             0xD0, 0xD1, 0xD2, 0xD5, 0xDA, 0xDC, 0xDD, 0xDE, 0xDF, 0xE0, 0xE1, 0xE2}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3
ANN_STATUS    = 4

# Default coefficients (RS = 1 mohm, CL = GND; the decoder cannot see the pins).
RS = 1.0
VOLT = (22070.0, -1800.0, -2)
VAUX = (3546.0, -3.0, 0)
AMP = (13661.0 * RS, -5200.0, -2)
WATT = (736.0 * RS, -3300.0, -2)
TEMP = (16000.0, 0.0, -3)

STATUS_BYTE_BITS = {6: 'OFF', 3: 'VIN_UV', 2: 'TEMP', 1: 'CML', 0: 'OTHER'}
STATUS_WORD_BITS = {15: 'VOUT', 13: 'INPUT', 12: 'MFR', 11: 'PG#'}
STATUS_INPUT_BITS = {7: 'VIN_OV_FAULT', 6: 'VIN_OV_WARN', 5: 'VIN_UV_WARN', 4: 'VIN_UV_FAULT', 2: 'IIN_OC', 0: 'PIN_OP'}
STATUS_TEMP_BITS = {7: 'OT_FAULT', 6: 'OT_WARN'}
STATUS_MFR_BITS = {7: 'CB', 6: 'MOSFET_SHORT', 4: 'DEFAULTS_LOADED'}
DIAG_BITS = {15: 'VOUT_UV_WARN', 14: 'IIN_OP_WARN', 13: 'VIN_UV_WARN', 12: 'VIN_OV_WARN', 11: 'POWER_GOOD',
             10: 'OT_WARN', 9: 'TIMER_LATCHED_OFF', 8: 'EXT_MOSFET_SHORTED', 7: 'CONFIG_PRESET', 6: 'DEVICE_OFF',
             5: 'VIN_UV_FAULT', 4: 'VIN_OV_FAULT', 3: 'IIN_OC/PFET_OP_FAULT', 2: 'OT_FAULT', 1: 'CML_FAULT',
             0: 'CB_FAULT'}
ALERT_BITS = {15: 'VOUT_UV_WARN', 14: 'IIN_WARN', 13: 'VIN_UV_WARN', 12: 'VIN_OV_WARN', 11: 'POWER_GOOD',
              10: 'OT_WARN', 8: 'PIN_OP_WARN', 6: 'MOSFET_SHORTED', 5: 'VIN_UV_FAULT', 4: 'VIN_OV_FAULT',
              3: 'IIN_FAULT', 2: 'OT_FAULT', 1: 'CML_FAULT', 0: 'CB_FAULT'}
RETRY = {0: 'pin', 1: 'none', 2: '1', 3: '2', 4: '4', 5: '8', 6: '16', 7: 'unlimited'}


def _x(y, coef):
    m, b, r = coef
    return (y * (10.0 ** (-r)) - b) / m


def _bits(value, table):
    names = [table[b] for b in sorted(table, reverse=True) if value & (1 << b)]
    return ', '.join(names) if names else 'none'


def _temp(word):
    return (word - 0x10000 if word & 0x8000 else word) / 16.0


def _decode(cmd, raw):
    """Describe the payload `raw` (int) of command `cmd`."""
    if cmd == 0x01:
        return 'ON' if raw == 0x80 else ('OFF' if raw == 0x00 else '0x%02X' % raw)
    if cmd == 0x19:
        return 'PEC %s, 400 kHz %s, SMBA %s' % ('yes' if raw & 0x80 else 'no', 'yes' if raw & 0x20 else 'no', 'yes' if raw & 0x10 else 'no')
    if cmd in (0x43, 0x57, 0x58, 0x88, 0x8B, 0xDC, 0xDD):
        y = raw & 0x0FFF
        return '%d = %.3f V' % (y, _x(y, VOLT))
    if cmd in (0x4F, 0x51, 0x8D):
        return '0x%04X = %.2f \u00b0C' % (raw, _temp(raw))
    if cmd == 0x78:
        return _bits(raw, STATUS_BYTE_BITS)
    if cmd == 0x79:
        return '%s | %s' % (_bits(raw >> 8 << 8, STATUS_WORD_BITS), _bits(raw & 0xFF, STATUS_BYTE_BITS))
    if cmd == 0x7A:
        return 'VOUT_UV_WARN' if raw & 0x20 else 'none'
    if cmd == 0x7C:
        return _bits(raw, STATUS_INPUT_BITS)
    if cmd == 0x7D:
        return _bits(raw, STATUS_TEMP_BITS)
    if cmd == 0x7E:
        return 'CML 0x%02X' % raw
    if cmd == 0x80:
        return _bits(raw, STATUS_MFR_BITS)
    if cmd == 0xD0:
        y = raw & 0x0FFF
        return '%d = %.3f V' % (y, _x(y, VAUX))
    if cmd in (0xD1, 0xD3, 0xDE):
        y = raw & 0x0FFF
        return '%d = %.3f A' % (y, _x(y, AMP))
    if cmd in (0xD2, 0xD4, 0xD5, 0xDF):
        y = raw & 0x0FFF
        return '%d = %.3f W' % (y, _x(y, WATT))
    if cmd == 0xD7:
        return 'masked faults: %s' % (_bits(raw, {5: 'VIN_UV', 4: 'VIN_OV', 2: 'OT'}))
    if cmd == 0xD8:
        return 'masked: %s' % _bits(raw, ALERT_BITS)
    if cmd == 0xD9:
        return 'retry=%s, CL %s (%s), CB %s (%s)' % (
            RETRY[raw >> 5], '46 mV' if raw & 0x10 else '25 mV', 'SMBus' if raw & 0x04 else 'pin',
            '3.6x' if raw & 0x08 else '1.8x', 'SMBus' if raw & 0x02 else 'pin')
    if cmd == 0xDB:
        return 'AVGN=%d (%d samples)' % (raw, 1 << raw) if raw <= 12 else 'AVGN=%d (invalid, 0-12)' % raw
    if cmd == 0xE1:
        return _bits(raw, DIAG_BITS)
    return '0x%X' % raw


def _decode_block(cmd, payload):
    if cmd in (0x99, 0x9A, 0x9B):
        return '"%s"' % ''.join(chr(c) for c in payload if c)
    w = [payload[2 * i] | (payload[2 * i + 1] << 8) for i in range(len(payload) // 2)]
    if len(w) != 6:
        return ' '.join('%02X' % c for c in payload)
    return 'diag [%s], IIN %.3f A, VOUT %.3f V, VIN %.3f V, PIN %.3f W, T %.2f \u00b0C' % (
        _bits(w[0], DIAG_BITS), _x(w[1] & 0xFFF, AMP), _x(w[2] & 0xFFF, VOLT), _x(w[3] & 0xFFF, VOLT),
        _x(w[4] & 0xFFF, WATT), _temp(w[5]))


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
    id = 'lm25066'
    name = 'LM25066'
    longname = 'LM25066 hot-swap controller / PMBus power monitor'
    desc = 'Decode LM25066 PMBus transactions: telemetry, status, limits and control commands.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['lm25066']
    tags = ['IC', 'Power']

    annotations = (
        ('reg-write', 'Command write'),
        ('reg-read',  'Command read'),
        ('ptr-write', 'Send byte / command pointer'),
        ('warning',   'Warning'),
        ('status',    'Status'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE)),
        ('status',   'Status',   (ANN_STATUS,)),
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
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        if reg is None:
            return
        info = CMDS.get(reg)
        name = info[0] if info else 'CMD_0x%02X' % reg
        if info is None:
            self._warn(self.ss_block, self.es, 'Unknown command code 0x%02X' % reg)
        buf = self.databuf

        if self.is_read:
            if not buf:
                return
            if info is None:
                self.put(self.ss_block, self.es, self.out_ann, [ANN_REG_READ, ['Read %s: %s' % (name, ' '.join('%02X' % b for b in buf)), 'R %s' % name]])
                return
            kind, length = info[1], info[2]
            if kind == 'block':
                if buf[0] != length:
                    self._warn(self.ss_block, self.es, 'Unexpected block count %d for %s (expected %d)' % (buf[0], name, length))
                text = _decode_block(reg, buf[1:])
                self.put(self.ss_block, self.es, self.out_ann, [ANN_REG_READ, ['Read %s: %s' % (name, text), 'R %s' % name]])
            else:
                if len(buf) != length:
                    self._warn(self.ss_block, self.es, 'Unexpected read length %d for %s' % (len(buf), name))
                    return
                raw = buf[0] | (buf[1] << 8) if length == 2 else buf[0]
                self.put(self.ss_block, self.es, self.out_ann, [ANN_REG_READ, ['Read %s: %s' % (name, _decode(reg, raw)), 'R %s 0x%X' % (name, raw)]])
            return

        # write transactions
        if not buf:
            if info is not None and info[1] == 'send':
                self.put(self.ss_block, self.es, self.out_ann, [ANN_PTR_WRITE, ['Send byte %s (0x%02X)' % (name, reg), 'SEND %s' % name]])
            else:
                self.put(self.ss_block, self.es, self.out_ann, [ANN_PTR_WRITE, ['Command \u2192 %s (0x%02X)' % (name, reg), 'CMD 0x%02X' % reg]])
            return
        if info is None:
            self.put(self.ss_block, self.es, self.out_ann, [ANN_REG_WRITE, ['Write %s: %s' % (name, ' '.join('%02X' % b for b in buf)), 'W %s' % name]])
            return
        kind, length = info[1], info[2]
        if reg in READ_ONLY:
            self._warn(self.ss_block, self.es, 'Write to read-only command %s' % name)
        if kind not in ('byte', 'word') or len(buf) != length:
            self._warn(self.ss_block, self.es, 'Unexpected write length %d for %s' % (len(buf), name))
            return
        raw = buf[0] | (buf[1] << 8) if length == 2 else buf[0]
        self.put(self.ss_block, self.es, self.out_ann, [ANN_REG_WRITE, ['Write %s: %s' % (name, _decode(reg, raw)), 'W %s 0x%X' % (name, raw)]])
        if reg == 0x01 and raw == 0x00:
            self._warn(self.ss_block, self.es, 'OPERATION off: the load MOSFET is switched OFF')
        if reg == 0xD7:
            self._warn(self.ss_block, self.es, 'GATE_MASK written: fault shut-off of the MOSFET may be disabled')

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass  # pointer already set; don't reset databuf
            else:
                self._finish_transaction()
                self.databuf  = []
                self.is_read  = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = addr
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state   = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = byte
                self.databuf = []
                self.state   = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []
