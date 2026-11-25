import sigrokdecode as srd

ADDRS = {0x44}

CMD_ENTER_PROG = 0xA0
CMD_EXIT_PROG = 0x80
NVM_READ_REGS = {0x06: 'humidity resolution', 0x11: 'temperature resolution',
                 0x1E: 'sensor ID (high 16 bits)', 0x1F: 'sensor ID (low 16 bits)'}
NVM_WRITE_REGS = {0x46: ('humidity resolution', 0x06), 0x51: ('temperature resolution', 0x11)}
RES_BITS = {0: 8, 1: 10, 2: 12, 3: 14}
RES_MASK = 0x0C00

ANN_CMD     = 0
ANN_RESULT  = 1
ANN_STATUS  = 2
ANN_WARNING = 3


def _resolution(value):
    return RES_BITS[(value >> 10) & 3]


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
    id = 'hs30xx'
    name = 'HS30xx'
    longname = 'Renesas HS30xx humidity and temperature sensor'
    desc = 'Decode HS3001/HS3002/HS3003 I2C measurement requests, data fetches with status check and programming-mode NVM access.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['hs30xx']
    tags = ['IC', 'Sensor', 'Humidity']

    annotations = (
        ('cmd',     'Command'),
        ('result',  'Measurement result'),
        ('status',  'NVM register'),
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
        self.reg_ptr  = None       # NVM register of the current transaction (None for MR / DF)
        self.databuf  = []
        self.ss_block = None
        self.cmd_buf  = []
        self.context  = None       # 'DF' after a measurement request, ('NVM', reg) after an NVM read request
        self.nvm_last = {}         # last value read from each NVM read register

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _decode_fetch(self, buf):
        status = buf[0] >> 6
        parts = []
        if len(buf) >= 2:
            raw_h = ((buf[0] & 0x3F) << 8) | buf[1]
            parts.append('RH=%.2f %%RH' % (raw_h / 16383.0 * 100.0))
        if len(buf) >= 4:
            raw_t = (buf[2] << 6) | (buf[3] >> 2)
            parts.append('T=%.2f \u00b0C' % (raw_t / 16383.0 * 165.0 - 40.0))
        parts.append('valid' if status == 0 else 'STALE')
        self.put(self.ss_block, self.es, self.out_ann, [ANN_RESULT, [', '.join(parts), 'Result']])
        if status != 0:
            self._warn(self.ss_block, self.es, 'Stale data (status %d): fetched before the measurement completed' % status)
        if len(buf) not in (2, 4):
            self._warn(self.ss_block, self.es, 'Unexpected fetch length %d (expected 2 or 4 bytes)' % len(buf))

    def _decode_nvm_read(self, reg, buf):
        if len(buf) != 3:
            self._warn(self.ss_block, self.es, 'NVM read needs 3 bytes (status, high, low), got %d' % len(buf))
            return
        value = (buf[1] << 8) | buf[2]
        if buf[0] != 0x81:
            self._warn(self.ss_block, self.es, 'NVM read status 0x%02X, expected 0x81' % buf[0])
        self.nvm_last[reg] = value
        if reg in (0x06, 0x11):
            text = 'NVM 0x%02X = 0x%04X: %d bit' % (reg, value, _resolution(value))
        else:
            text = 'NVM 0x%02X = 0x%04X (%s)' % (reg, value, NVM_READ_REGS[reg])
        self.put(self.ss_block, self.es, self.out_ann, [ANN_STATUS, [text, 'NVM 0x%02X' % reg]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ'):
            return
        buf = self.databuf
        if self.is_read:
            if not buf:
                return
            ctx = self.context
            if ctx == 'DF':
                self._decode_fetch(buf)
            elif isinstance(ctx, tuple):
                self.reg_ptr = ctx[1]
                self._decode_nvm_read(ctx[1], buf)
            else:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_RESULT, ['Read %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'R %dB' % len(buf)]])
                self._warn(self.ss_block, self.es, 'Read without a preceding measurement request or NVM read')
            return
        # write phase
        cmd = self.cmd_buf
        self.databuf = list(cmd)
        self.reg_ptr = None
        if not cmd:
            self.context = 'DF'
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['Measurement request (MR)', 'MR']])
            return
        b0 = cmd[0]
        if len(cmd) != 3:
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, ['Write %d bytes' % len(cmd), 'W']])
            self._warn(self.ss_block, self.es, 'Unexpected write length %d (commands are address-only or 3 bytes)' % len(cmd))
            return
        if b0 == CMD_ENTER_PROG or b0 == CMD_EXIT_PROG:
            name = 'Enter programming mode' if b0 == CMD_ENTER_PROG else 'Leave programming mode'
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, [name, 'PROG' if b0 == CMD_ENTER_PROG else 'EXIT']])
            if cmd[1] or cmd[2]:
                self._warn(self.ss_block, self.es, 'Programming command bytes 1 and 2 should be 0x00')
            self.context = None
        elif b0 in NVM_READ_REGS:
            self.reg_ptr = b0
            self.databuf = list(cmd[1:])
            self.context = ('NVM', b0)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_CMD, ['NVM read request 0x%02X (%s)' % (b0, NVM_READ_REGS[b0]), 'NVM R 0x%02X' % b0]])
            if cmd[1] or cmd[2]:
                self._warn(self.ss_block, self.es, 'NVM read request bytes 1 and 2 should be 0x00')
        elif b0 in NVM_WRITE_REGS:
            name, rreg = NVM_WRITE_REGS[b0]
            value = (cmd[1] << 8) | cmd[2]
            self.reg_ptr = b0
            self.databuf = list(cmd[1:])
            self.context = None
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_STATUS, ['NVM write 0x%02X (%s) = 0x%04X: %d bit' % (b0, name, value, _resolution(value)),
                                   'NVM W 0x%02X' % b0]])
            last = self.nvm_last.get(rreg)
            if last is not None and (last ^ value) & ~RES_MASK & 0xFFFF:
                self._warn(self.ss_block, self.es, 'NVM write changes bits other than 11:10 (read 0x%04X, write 0x%04X)' % (last, value))
        else:
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CMD, ['Command 0x%02X: unknown' % b0, 'CMD 0x%02X' % b0]])
            self._warn(self.ss_block, self.es, 'Undocumented command 0x%02X' % b0)
            self.context = None

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
