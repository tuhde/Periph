import sigrokdecode as srd

GENERAL_CALL = 0x00
ADDR_MIN = 0x40
ADDR_MAX = 0x7F
WAKE_US = 500

REG_NAMES = {
    0x00: 'MODE1', 0x01: 'MODE2', 0x02: 'SUBADR1', 0x03: 'SUBADR2', 0x04: 'SUBADR3', 0x05: 'ALLCALLADR',
    0xFA: 'ALL_LED_ON_L', 0xFB: 'ALL_LED_ON_H', 0xFC: 'ALL_LED_OFF_L', 0xFD: 'ALL_LED_OFF_H',
    0xFE: 'PRE_SCALE', 0xFF: 'TestMode',
}
LED_FIELDS = ('ON_L', 'ON_H', 'OFF_L', 'OFF_H')

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_WARNING   = 2
ANN_STATUS    = 3


def _reg_name(reg):
    if 0x06 <= reg <= 0x45:
        return 'LED%d_%s' % ((reg - 0x06) // 4, LED_FIELDS[(reg - 0x06) % 4])
    return REG_NAMES.get(reg, 'Reg[0x%02X]' % reg)


def _mode1(raw):
    flags = [n for bit, n in ((7, 'RESTART'), (6, 'EXTCLK'), (5, 'AI'), (4, 'SLEEP'), (3, 'SUB1'),
                              (2, 'SUB2'), (1, 'SUB3'), (0, 'ALLCALL')) if raw & (1 << bit)]
    return '%s (0x%02X)' % ('|'.join(flags) if flags else 'none', raw)


def _mode2(raw):
    ne = raw & 3
    ne_txt = ('LEDn=0', 'LEDn=1 / high-Z', 'high-Z', 'high-Z')[ne]
    return 'INVRT=%d OCH=%s OUTDRV=%s OUTNE=%s (0x%02X)' % (
        (raw >> 4) & 1, 'ACK' if raw & 8 else 'STOP', 'totem-pole' if raw & 4 else 'open-drain', ne_txt, raw)


def _channel(on_l, on_h, off_l, off_h):
    """Text and (on, off) counts for the four registers of one channel."""
    on = ((on_h & 0x0F) << 8) | on_l
    off = ((off_h & 0x0F) << 8) | off_l
    if off_h & 0x10:
        return 'full OFF', on, off
    if on_h & 0x10:
        return 'full ON', on, off
    duty = ((off - on) % 4096) / 4096.0 * 100.0
    return 'ON=%d OFF=%d duty %.1f%%' % (on, off, duty), on, off


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
    id = 'pca9685'
    name = 'PCA9685'
    longname = 'PCA9685 16-channel 12-bit PWM controller'
    desc = 'Decode PCA9685 I2C register transactions (modes, channels, prescaler, software reset).'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pca9685']
    tags = ['IC', 'LED']

    options = (
        {'id': 'osc_hz', 'desc': 'Oscillator frequency in Hz', 'default': 25000000},
    )

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('warning',   'Warning'),
        ('status',    'Power state'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ)),
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
        self.samplerate = None
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.asleep   = True
        self.wake_sample = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _within_wake(self, ss):
        if self.samplerate is None or self.wake_sample is None:
            return False
        return (ss - self.wake_sample) / float(self.samplerate) * 1e6 < WAKE_US

    def _text_for(self, reg, buf):
        """Human-readable decoding of the registers reg .. reg+len(buf)-1 (auto-increment)."""
        parts = []
        i = 0
        osc = float(self.options['osc_hz'])
        while i < len(buf):
            r = reg + i
            b = buf[i]
            if 0x06 <= r <= 0x45 and (r - 0x06) % 4 == 0 and i + 3 < len(buf):
                txt, on, off = _channel(*buf[i:i + 4])
                parts.append('LED%d %s' % ((r - 0x06) // 4, txt))
                i += 4
                continue
            if r == 0xFA and i + 3 < len(buf):
                txt, on, off = _channel(*buf[i:i + 4])
                parts.append('ALL_LED %s' % txt)
                i += 4
                continue
            name = _reg_name(r)
            if r == 0x00:
                txt = _mode1(b)
            elif r == 0x01:
                txt = _mode2(b)
            elif r in (0x02, 0x03, 0x04, 0x05):
                txt = 'address 0x%02X' % (b >> 1)
            elif r == 0xFE:
                txt = '%d \u2192 %.1f Hz' % (b, osc / (4096.0 * (b + 1)))
            else:
                txt = '0x%02X' % b
            parts.append('%s=%s' % (name, txt))
            i += 1
        return ', '.join(parts)

    def _check_writes(self, ss, es, reg, buf):
        for i, b in enumerate(buf):
            r = reg + i
            if r == 0x00:
                was_asleep = self.asleep
                self.asleep = bool(b & 0x10)
                if was_asleep and not self.asleep:
                    self.wake_sample = es
                    self.put(ss, es, self.out_ann, [ANN_STATUS, ['Awake (oscillator starting)', 'WAKE']])
                elif not was_asleep and self.asleep:
                    self.put(ss, es, self.out_ann, [ANN_STATUS, ['Sleep (oscillator off)', 'SLEEP']])
                if (b & 0x80) and self._within_wake(ss):
                    self._warn(ss, es, 'RESTART written within 500 us of clearing SLEEP')
            elif r == 0xFE and not self.asleep:
                self._warn(ss, es, 'PRE_SCALE written while the chip is awake (write ignored)')
        # ON = OFF check on whole channels / ALL_LED
        i = 0
        while i + 3 < len(buf):
            r = reg + i
            if (0x06 <= r <= 0x45 and (r - 0x06) % 4 == 0) or r == 0xFA:
                txt, on, off = _channel(*buf[i:i + 4])
                if txt.startswith('ON=') and on == off:
                    self._warn(ss, es, 'ON = OFF is not allowed')
                if not self.asleep and self._within_wake(ss):
                    self._warn(ss, es, 'PWM written within 500 us after wake-up')
                i += 4
            else:
                i += 1

    def _finish_transaction(self):
        if self.state == 'GENERAL_CALL':
            if self.databuf:
                ss, es = self.ss_block, self.es
                if self.databuf[0] == 0x06:
                    self.put(ss, es, self.out_ann, [ANN_REG_WRITE, ['Software Reset (general call)', 'SWRST']])
                    self.asleep = True
                else:
                    self.put(ss, es, self.out_ann, [ANN_REG_WRITE,
                             ['General call 0x%02X' % self.databuf[0], 'GC %02X' % self.databuf[0]]])
            return
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        buf = self.databuf
        if reg is None or not buf:
            return
        ss, es = self.ss_block, self.es
        kind = ANN_REG_READ if self.is_read else ANN_REG_WRITE
        text = self._text_for(reg, buf)
        self.put(ss, es, self.out_ann, [kind, [
            '%s %s' % ('Read' if self.is_read else 'Write', text),
            '%s %s' % ('R' if self.is_read else 'W', _reg_name(reg))]])
        if not self.is_read:
            self._check_writes(ss, es, reg, buf)

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
            if addr == GENERAL_CALL and ptype == 'ADDRESS WRITE':
                self.state = 'GENERAL_CALL'
                self.databuf = []
                return
            if not ADDR_MIN <= addr <= ADDR_MAX:
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
            if self.state == 'GENERAL_CALL':
                self.databuf.append(byte)
            elif self.state == 'GET_REG_PTR':
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
