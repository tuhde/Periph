import sigrokdecode as srd

ADDRS = {0x52, 0x55}

REGS = {
    0x00: 'PART_ID', 0x01: 'HW_ID', 0x02: 'REV_ID', 0x03: 'INFO0', 0x04: 'INFO1',
    0x0A: 'HOSTIN0', 0x0B: 'COMMAND', 0x0F: 'IRQ_ENABLE', 0x10: 'RESPONSE1', 0x11: 'RESPONSE0',
    0x12: 'IRQ_STATUS',
}
for _i in range(26):
    REGS[0x13 + _i] = 'HOSTOUT%d' % _i

PARAMS = {0x00: 'I2C_ADDR', 0x01: 'CHAN_LIST', 0x1A: 'MEASRATE_H', 0x1B: 'MEASRATE_L',
          0x1C: 'MEASCOUNT0', 0x1D: 'MEASCOUNT1', 0x1E: 'MEASCOUNT2', 0x2B: 'BURST'}
for _c in range(6):
    PARAMS[0x02 + 4 * _c] = 'ADCCONFIG%d' % _c
    PARAMS[0x03 + 4 * _c] = 'ADCSENS%d' % _c
    PARAMS[0x04 + 4 * _c] = 'ADCPOST%d' % _c
    PARAMS[0x05 + 4 * _c] = 'MEASCONFIG%d' % _c
for _t in range(3):
    PARAMS[0x25 + 2 * _t] = 'THRESHOLD%d_H' % _t
    PARAMS[0x26 + 2 * _t] = 'THRESHOLD%d_L' % _t

MUX = {0x00: 'small IR', 0x01: 'medium IR', 0x02: 'large IR', 0x0B: 'white', 0x0D: 'large white',
       0x18: 'UV', 0x19: 'deep UV'}
DECIM = {0: '1024 clk', 1: '2048 clk', 2: '4096 clk', 3: '512 clk'}
CMD_NAMES = {0x00: 'RESET_CMD_CTR', 0x01: 'RESET_SW', 0x11: 'FORCE', 0x12: 'PAUSE', 0x13: 'START'}
ERRORS = {0x10: 'invalid command', 0x11: 'invalid parameter address', 0x12: 'ADC saturation / accumulation overflow',
          0x13: 'output buffer overflow'}

ANN_REG_WRITE  = 0
ANN_REG_READ   = 1
ANN_RESULT     = 2
ANN_COMMAND    = 3
ANN_PTR_WRITE  = 4
ANN_STATUS     = 5
ANN_WARNING    = 6


def _param_name(p):
    return PARAMS.get(p, 'param[0x%02X]' % p)


def _decode_param(p, v):
    """Human-readable value of parameter p."""
    if p == 0x01:
        chans = [str(c) for c in range(6) if v & (1 << c)]
        return 'CHAN_LIST 0x%02X: channels %s' % (v, ', '.join(chans) if chans else 'none')
    if 0x02 <= p <= 0x19:
        c, k = (p - 0x02) // 4, (p - 0x02) % 4
        if k == 0:
            mux = v & 0x1F
            return 'ADCCONFIG%d 0x%02X: %s, decimation %s' % (c, v, MUX.get(mux, 'ADCMUX 0x%02X' % mux), DECIM[(v >> 5) & 3])
        if k == 1:
            return 'ADCSENS%d 0x%02X: HW_GAIN %d, SW_GAIN %d (x%d)%s' % (
                c, v, v & 0x0F, (v >> 4) & 7, 1 << ((v >> 4) & 7), ', HSIG (/14.5)' if v & 0x80 else '')
        if k == 2:
            ts = v & 3
            return 'ADCPOST%d 0x%02X: %s, POSTSHIFT %d, %s' % (
                c, v, '24-bit' if v & 0x40 else '16-bit', (v >> 3) & 7,
                'threshold THRESHOLD%d' % (ts - 1) if ts else 'no threshold')
        idx = (v >> 6) & 3
        return 'MEASCONFIG%d 0x%02X: %s' % (c, v, 'MEASCOUNT%d' % (idx - 1) if idx else 'forced only')
    if p == 0x00:
        return 'I2C_ADDR 0x%02X' % v
    if p == 0x2B:
        return 'BURST 0x%02X: %s' % (v, '%d readings' % (v & 0x7F) if v & 0x80 else 'off')
    if p in (0x1A, 0x1B):
        return '%s %d (800 us units)' % (_param_name(p), v)
    return '%s %d' % (_param_name(p), v)


def _decode_resp0(v):
    states = [n for bit, n in ((7, 'RUNNING'), (6, 'SUSPEND'), (5, 'SLEEP')) if v & (1 << bit)]
    state = '/'.join(states) if states else 'no state'
    if v & 0x10:
        code = v & 0x1F
        return 'RESPONSE0 0x%02X: %s, CMD_ERR 0x%02X (%s)' % (v, state, code, ERRORS.get(code, 'unknown'))
    return 'RESPONSE0 0x%02X: %s, counter %d' % (v, state, v & 0x0F)


def _mask(v):
    chans = [str(c) for c in range(6) if v & (1 << c)]
    return 'channels ' + ', '.join(chans) if chans else 'none'


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'part_id' in m:
        return 'ID?'
    if 'cmd_err' in m:
        return 'ERR'
    if 'counter' in m:
        return 'CTR?'
    if 'chan_list' in m:
        return 'CHAN?'
    return 'WARN'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); if a call site supplied
    fewer, derive the missing tier from the existing text so a narrow PulseView zoom still has
    something to show."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings

    def lead(text):
        text = text.strip()
        n = 0
        while n < len(text) and (text[n].isalnum() or text[n] in '_→'):
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
    id = 'si1133'
    name = 'Si1133'
    longname = 'Silicon Labs Si1133 UV index and ambient light sensor'
    desc = 'Decode Si1133 I2C accesses: commands, parameter table, RESPONSE0, IRQ status and HOSTOUT channel results.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['si1133']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write',  'Register write'),
        ('reg-read',   'Register read'),
        ('result',     'Measurement result'),
        ('command',    'Command'),
        ('ptr-write',  'Register pointer write'),
        ('status',     'Status / response'),
        ('warning',    'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_RESULT, ANN_COMMAND, ANN_PTR_WRITE)),
        ('status',   'Status',   (ANN_STATUS,)),
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
        ('REG_READ' | 'REG_WRITE', (register, bytes))."""
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
        if reg is not None:
            super().put(ss, es, out_py, ('REG_' + rw, (reg, buf)))

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.ss       = None
        self.es       = None
        self._reset_chip()

    def _reset_chip(self):
        self.hostin = 0
        self.params = {}          # parameter shadow from PARAM_SET
        self.last_query = None    # parameter of the last PARAM_QUERY
        self.pending = None       # (command name, counter before) awaiting a counter change
        self.last_counter = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _reg_name(self, reg):
        return REGS.get(reg, 'reg[0x%02X]' % reg)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        if reg is None:
            return
        if self.is_read:
            self._finish_read(reg)
        else:
            self._finish_write(reg)

    # --- reads ------------------------------------------------------------

    def _hostout_values(self, buf, start):
        """Split HOSTOUT bytes into per-channel values using the CHAN_LIST / ADCPOST shadow."""
        chan = self.params.get(0x01)
        if chan is None or start != 0:
            return None
        values, pos = [], 0
        for c in range(6):
            if not chan & (1 << c):
                continue
            wide = self.params.get(0x04 + 4 * c, 0) & 0x40
            n = 3 if wide else 2
            if pos + n > len(buf):
                break
            if wide:
                v = (buf[pos] << 16) | (buf[pos + 1] << 8) | buf[pos + 2]
                if v & 0x800000:
                    v -= 0x1000000
            else:
                v = (buf[pos] << 8) | buf[pos + 1]
            mux = self.params.get(0x02 + 4 * c)
            label = MUX.get(mux & 0x1F, 'ch%d' % c) if mux is not None else 'ch%d' % c
            values.append('ch%d (%s) %d' % (c, label, v))
            pos += n
        return values

    def _finish_read(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        if not buf:
            return
        base = reg & 0x3F
        if base >= 0x13:
            values = self._hostout_values(buf, base - 0x13)
            if values:
                self.put(ss, es, self.out_ann, [ANN_RESULT, ['HOSTOUT: ' + ', '.join(values), values[0], 'OUT']])
            else:
                self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read %s: %s' % (self._reg_name(base), ' '.join('%02X' % b for b in buf)), 'R OUT']])
            return
        v = buf[0]
        if base == 0x11:
            self._response0(ss, es, v)
            self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read ' + _decode_resp0(v), 'R RESP0']])
            return
        if base == 0x10:
            if self.last_query is not None:
                text = 'RESPONSE1 (query) ' + _decode_param(self.last_query, v)
            else:
                text = 'RESPONSE1 0x%02X' % v
            self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read ' + text, 'R RESP1']])
            return
        if base == 0x12:
            self.put(ss, es, self.out_ann, [ANN_STATUS, ['IRQ_STATUS 0x%02X: %s ready' % (v, _mask(v)), 'IRQ 0x%02X' % v]])
            self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read IRQ_STATUS 0x%02X' % v, 'R IRQ']])
            return
        if base == 0x00:
            parts = ['PART_ID 0x%02X' % v]
            if len(buf) >= 3:
                parts.append('HW_ID 0x%02X (impl 0x%02X, rev %d)' % (buf[1], buf[1] & 0x1F, buf[1] >> 5))
                parts.append('REV_ID 0x%02X' % buf[2])
            self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read ' + ', '.join(parts), 'R ID']])
            if v != 0x33:
                self._warn(ss, es, 'Unexpected PART_ID: 0x%02X (Si1133 is 0x33)' % v)
            return
        text = '%s %s' % (self._reg_name(base), ' '.join('%02X' % b for b in buf))
        self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read ' + text, 'R 0x%02X' % base]])

    def _response0(self, ss, es, v):
        if v & 0x10:
            self._warn(ss, es, 'CMD_ERR: ' + ERRORS.get(v & 0x1F, 'code 0x%02X' % (v & 0x1F)))
            self.pending = None
            self.put(ss, es, self.out_ann, [ANN_STATUS, [_decode_resp0(v), 'CMD_ERR']])
            return
        counter = v & 0x0F
        if self.pending is not None:
            if counter != self.pending['before']:
                self.pending = None
            else:
                self.pending['polled'] = True
        self.last_counter = counter
        self.put(ss, es, self.out_ann, [ANN_STATUS, [_decode_resp0(v), 'CTR %d' % counter]])

    # --- writes -----------------------------------------------------------

    def _command(self, ss, es, cmd):
        if self.pending is not None and self.pending['polled']:
            self._warn(ss, es, 'No counter change seen after %s' % self.pending['name'])
        if cmd & 0xC0 == 0x80:
            p = cmd & 0x3F
            self.params[p] = self.hostin
            name = 'PARAM_SET ' + _decode_param(p, self.hostin)
            short = 'SET %s' % _param_name(p)
        elif cmd & 0xC0 == 0x40:
            p = cmd & 0x3F
            self.last_query = p
            name = 'PARAM_QUERY %s' % _param_name(p)
            short = 'QUERY'
        else:
            name = CMD_NAMES.get(cmd, 'unknown command 0x%02X' % cmd)
            short = name
            if cmd == 0x11 and self.params.get(0x01) == 0:
                self._warn(ss, es, 'FORCE with an empty CHAN_LIST')
        self.put(ss, es, self.out_ann, [ANN_COMMAND, ['Command ' + name, short, 'CMD']])
        if cmd == 0x01:
            self._reset_chip()
            return
        if cmd == 0x00:
            self.pending = None
            return
        self.pending = None if self.last_counter is None else \
            {'name': name, 'before': self.last_counter, 'polled': False}

    def _finish_write(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        base = reg & 0x3F
        if not buf:
            self.put(ss, es, self.out_ann,
                     [ANN_PTR_WRITE, ['Pointer -> %s (0x%02X)' % (self._reg_name(base), base), 'PTR 0x%02X' % base]])
            return
        autoinc = not reg & 0x40
        for i, b in enumerate(buf):
            r = base + i if autoinc else base
            if r == 0x0A:
                self.hostin = b
                self.put(ss, es, self.out_ann, [ANN_REG_WRITE, ['Write HOSTIN0 0x%02X' % b, 'W HOSTIN0']])
            elif r == 0x0B:
                self._command(ss, es, b)
            elif r == 0x0F:
                self.put(ss, es, self.out_ann, [ANN_REG_WRITE, ['Write IRQ_ENABLE 0x%02X: %s' % (b, _mask(b)), 'W IRQ_EN']])
            else:
                self.put(ss, es, self.out_ann, [ANN_REG_WRITE, ['Write %s 0x%02X' % (self._reg_name(r), b), 'W 0x%02X' % r]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_DATA_WRITE' and not self.databuf:
                pass   # pointer write followed by a repeated-start read: keep reg_ptr
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = pdata
                self.databuf = []
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
