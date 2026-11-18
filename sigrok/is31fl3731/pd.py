import sigrokdecode as srd

ADDRS = {0x74, 0x75, 0x76, 0x77}

PAGE_FUNCTION = 0x0B

FN_REGS = {
    0x00: 'CONFIG', 0x01: 'PICTURE', 0x02: 'AUTOPLAY1', 0x03: 'AUTOPLAY2', 0x04: 'reserved',
    0x05: 'DISPLAY', 0x06: 'AUDIO_SYNC', 0x07: 'FRAME_STATE', 0x08: 'BREATH1', 0x09: 'BREATH2',
    0x0A: 'SHUTDOWN', 0x0B: 'AGC', 0x0C: 'AUDIO_RATE',
}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PAGE      = 2
ANN_WARNING   = 3
ANN_STATUS    = 4


def _led_name(offset):
    """Cx-y name of the LED at PWM offset 0..143."""
    return 'C%d-%d' % (offset // 16 + 1, offset % 16 + 1)


def _mode(raw):
    m = (raw >> 3) & 3
    if m == 0:
        return 'Picture mode'
    if m == 1:
        return 'Auto Frame Play from frame %d' % ((raw & 7) + 1)
    return 'Audio Frame Play'


def _decode_function(reg, raw):
    """Human-readable decoding of a function-page register value."""
    if reg == 0x00:
        return '%s (0x%02X)' % (_mode(raw), raw)
    if reg == 0x01:
        return 'picture frame %d' % ((raw & 7) + 1)
    if reg == 0x02:
        loops = (raw >> 4) & 7
        frames = raw & 7
        return '%s, %s' % ('endless loops' if loops == 0 else '%d loop(s)' % loops,
                           'all frames' if frames == 0 else '%d frame(s)' % frames)
    if reg == 0x03:
        a = raw & 0x3F
        return 'frame delay %d ms' % (11 * (a if a else 64))
    if reg == 0x05:
        return 'IC=%d, blink %s, period %.2f s' % ((raw >> 5) & 1, 'on' if raw & 8 else 'off', 0.27 * (raw & 7))
    if reg == 0x06:
        return 'audio sync %s' % ('on' if raw & 1 else 'off')
    if reg == 0x07:
        return 'movie finished=%d, current frame %d' % ((raw >> 4) & 1, (raw & 7) + 1)
    if reg == 0x08:
        return 'fade out %.0f ms, fade in %.0f ms' % (26 * 2 ** ((raw >> 4) & 7), 26 * 2 ** (raw & 7))
    if reg == 0x09:
        return 'breath %s, extinguish %.1f ms' % ('on' if raw & 0x10 else 'off', 3.5 * 2 ** (raw & 7))
    if reg == 0x0A:
        return 'normal operation' if raw & 1 else 'software shutdown'
    if reg == 0x0B:
        return 'gain %d dB, AGC %s (%s)' % (3 * (raw & 7), 'on' if raw & 8 else 'off', 'fast' if raw & 0x10 else 'slow')
    if reg == 0x0C:
        a = raw & 0xFF
        return 'ADC interval %d us' % (46 * (a if a else 256))
    return '0x%02X' % raw


def _pwm_runs(start, data):
    """Compress PWM bytes into 'Cx-y..Cx-y: v' runs of equal values."""
    runs = []
    i = 0
    while i < len(data):
        j = i
        while j + 1 < len(data) and data[j + 1] == data[i]:
            j += 1
        if i == j:
            runs.append('%s=%d' % (_led_name(start + i), data[i]))
        else:
            runs.append('%s..%s=%d' % (_led_name(start + i), _led_name(start + j), data[i]))
        i = j + 1
    return runs


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
    id = 'is31fl3731'
    name = 'IS31FL3731'
    longname = 'IS31FL3731 144-LED matrix driver'
    desc = 'Decode IS31FL3731 I2C paged register transactions (frames and function page).'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['is31fl3731']
    tags = ['IC', 'Display', 'LED']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('page',      'Page select'),
        ('warning',   'Warning'),
        ('status',    'FRAME_STATE'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PAGE)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid != 'warnings' for c in classes}
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
        self.page     = None
        self.databuf  = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _page_name(self, page):
        if page == PAGE_FUNCTION:
            return 'function page'
        if page is not None and 0 <= page <= 7:
            return 'frame %d' % (page + 1)
        return 'unknown page'

    def _describe_frame(self, reg, buf):
        """Label for a write/read of frame-page registers starting at reg."""
        if reg <= 0x11:
            parts = ['row %d %s=0x%02X' % ((reg + i) // 2 + 1, 'B' if (reg + i) % 2 else 'A', b)
                     for i, b in enumerate(buf)]
            return 'LED control ' + ', '.join(parts)
        if reg <= 0x23:
            o = reg - 0x12
            parts = ['row %d %s=0x%02X' % ((o + i) // 2 + 1, 'B' if (o + i) % 2 else 'A', b)
                     for i, b in enumerate(buf)]
            return 'blink control ' + ', '.join(parts)
        if reg <= 0xB3:
            return 'PWM ' + ', '.join(_pwm_runs(reg - 0x24, buf))
        return 'register 0x%02X' % reg

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        if reg is None:
            return
        ss, es = self.ss_block, self.es
        buf = self.databuf
        if reg == 0xFD:
            if self.is_read:
                if buf:
                    self.put(ss, es, self.out_ann, [ANN_REG_READ,
                             ['Read COMMAND: %s' % self._page_name(buf[0]), 'R COMMAND']])
            elif buf:
                page = buf[0]
                self.put(ss, es, self.out_ann, [ANN_PAGE,
                         ['Page \u2192 %s (0x%02X)' % (self._page_name(page), page), 'PAGE %02X' % page]])
                if not (0 <= page <= 7 or page == PAGE_FUNCTION):
                    self._warn(ss, es, 'Unknown page number 0x%02X' % page)
                self.page = page
            return
        if self.page == PAGE_FUNCTION:
            if reg > 0x0C:
                if buf:
                    self._warn(ss, es, 'Frame-register access 0x%02X while the function page is selected' % reg)
                return
            if not buf:
                return
            for i, b in enumerate(buf):
                r = reg + i
                name = FN_REGS.get(r, 'Reg[0x%02X]' % r)
                text = _decode_function(r, b)
                kind = ANN_REG_READ if self.is_read else ANN_REG_WRITE
                self.put(ss, es, self.out_ann, [kind, [
                    '%s %s: %s' % ('Read' if self.is_read else 'Write', name, text),
                    '%s %s 0x%02X' % ('R' if self.is_read else 'W', name, b)]])
                if r == 0x07 and self.is_read:
                    self.put(ss, es, self.out_ann, [ANN_STATUS, [
                        'FRAME_STATE: movie finished=%d, frame %d' % ((b >> 4) & 1, (b & 7) + 1),
                        'INT=%d' % ((b >> 4) & 1)]])
                if r == 0x04 and not self.is_read:
                    self._warn(ss, es, 'Write to reserved function register 0x04')
                if r == 0x07 and not self.is_read:
                    self._warn(ss, es, 'Write to read-only register FRAME_STATE')
            return
        if self.page is not None and 0 <= self.page <= 7:
            if not buf:
                return
            if reg + len(buf) - 1 > 0xB3:
                self._warn(ss, es, 'Frame register block 0x%02X + %d bytes runs past 0xB3' % (reg, len(buf)))
            kind = ANN_REG_READ if self.is_read else ANN_REG_WRITE
            self.put(ss, es, self.out_ann, [kind, [
                '%s frame %d: %s' % ('Read' if self.is_read else 'Write', self.page + 1, self._describe_frame(reg, buf)),
                '%s %02X[%d]' % ('R' if self.is_read else 'W', reg, len(buf))]])
            return
        if buf:
            kind = ANN_REG_READ if self.is_read else ANN_REG_WRITE
            self.put(ss, es, self.out_ann, [kind, [
                '%s register 0x%02X (page unknown): %s' % ('Read' if self.is_read else 'Write', reg,
                                                            ' '.join('0x%02X' % b for b in buf)),
                '%s %02X' % ('R' if self.is_read else 'W', reg)]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass  # pointer already set; keep it for the read that follows
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
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
