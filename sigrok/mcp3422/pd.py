import sigrokdecode as srd

ADDR_FIRST = 0x68
ADDR_LAST = 0x6F
ADDR_GENERAL_CALL = 0x00
GC_RESET = 0x06
GC_CONVERT = 0x08

CFG_RDY = 0x80
CFG_CONT = 0x10

BITS = (12, 14, 16, 18)
RATES = ('240', '60', '15', '3.75')   # SPS
GAINS = (1, 2, 4, 8)
VREF = 2.048

ANN_DATA          = 0
ANN_CONFIG        = 1
ANN_GENERAL_CALL  = 2
ANN_CONV_START    = 3
ANN_CONV_DONE     = 4
ANN_WARNING       = 5


def _warn_tag(msg):
    return msg.split()[0][:8].upper()


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


def _channel_name(code):
    return ('CH1', 'CH2', 'CH3?', 'CH4?')[code & 3]


def _decode_config(byte):
    """Return (text, bits, gain, ready, continuous) for a configuration byte."""
    ch = (byte >> 5) & 3
    bits = BITS[(byte >> 2) & 3]
    rate = RATES[(byte >> 2) & 3]
    gain = GAINS[byte & 3]
    cont = bool(byte & CFG_CONT)
    ready = not byte & CFG_RDY          # RDY = 0 means a fresh result / ready
    text = '%s %s %d bit %s SPS x%d RDY=%d' % (
        _channel_name(ch), 'continuous' if cont else 'one-shot', bits, rate, gain, 1 - int(ready))
    return text, bits, gain, ready, cont


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mcp3422'
    name = 'MCP3422'
    longname = 'MCP3422 2-channel 18-bit delta-sigma ADC'
    desc = 'Decode MCP3422 I2C configuration writes, general calls and conversion results.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mcp3422']
    tags = ['IC', 'Analog/digital']

    annotations = (
        ('data',             'Conversion result'),
        ('config',           'Configuration byte'),
        ('general-call',     'General call'),
        ('conversion-start', 'Conversion start'),
        ('conversion-done',  'Conversion done'),
        ('warning',          'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA, ANN_CONFIG, ANN_GENERAL_CALL)),
        ('timing',   'Timing',   (ANN_CONV_START, ANN_CONV_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann and data[0] in (ANN_DATA, ANN_CONFIG, ANN_GENERAL_CALL):
            data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'     # IDLE | CONFIG | GENERAL | READ | IGNORE
        self.addr = None
        self.is_read = False
        self.bits = 12          # power-on default until a configuration write is seen
        self.gain = 1
        self.pending = False    # a conversion_start is waiting for its conversion_done
        self.databuf = []
        self.spans = []
        self.seg_ss = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _nbytes(self):
        return 3 if self.bits == 18 else 2

    def _flush(self, es):
        """End of a transaction segment: report an incomplete read, mirror it to OUTPUT_PYTHON."""
        if self.state == 'READ' and 0 < len(self.databuf) < self._nbytes() + 1:
            self._warn(self.spans[0][0], es, 'Read of %d bytes, a result frame is %d bytes' % (
                len(self.databuf), self._nbytes() + 1))
        if self.addr is not None and self.databuf and self.seg_ss is not None:
            self.put(self.seg_ss, es, self.out_python,
                     ('I2C_READ' if self.is_read else 'I2C_WRITE', (self.addr, bytes(self.databuf))))
        self.databuf = []
        self.spans = []
        self.seg_ss = None
        self.state = 'IDLE'

    def _config_write(self, ss, es, byte):
        text, bits, gain, _ready, cont = _decode_config(byte)
        self.bits = bits
        self.gain = gain
        self.put(ss, es, self.out_ann, [ANN_CONFIG, ['Config [0x%02X] %s' % (byte, text), 'CFG 0x%02X' % byte]])
        if (byte >> 5) & 2:
            self._warn(ss, es, 'Channel code %d%d is MCP3424 only' % ((byte >> 6) & 1, (byte >> 5) & 1))
        if byte & CFG_RDY and not cont:
            self._start_conversion(ss, es, 'Config write with RDY=1, O/C=0')

    def _start_conversion(self, ss, es, why):
        self.pending = True
        self.put(ss, es, self.out_ann, [ANN_CONV_START, ['conversion_start: ' + why, 'conversion_start', '→CONV']])

    def _finish_read(self, es):
        n = self._nbytes()
        if len(self.databuf) < n:
            return
        raw = 0
        for b in self.databuf[:n]:
            raw = (raw << 8) | b
        span = 1 << self.bits
        raw &= span - 1
        if raw >= span // 2:
            raw -= span
        lsb = 2.0 * VREF / span
        volts = raw * lsb / self.gain
        self.put(self.spans[0][0], self.spans[n - 1][1], self.out_ann,
                 [ANN_DATA, ['Read %d bit code [%s] %d = %.6f V' % (
                                 self.bits, ' '.join('0x%02X' % b for b in self.databuf[:n]), raw, volts),
                             'Code %d' % raw]])
        if len(self.databuf) > n:
            cfg = self.databuf[n]
            text, _b, _g, ready, _c = _decode_config(cfg)
            ss, es_cfg = self.spans[n]
            self.put(ss, es_cfg, self.out_ann, [ANN_CONFIG, ['Read config [0x%02X] %s' % (cfg, text), 'CFG 0x%02X' % cfg]])
            if ready and self.pending:
                self.pending = False
                self.put(ss, es_cfg, self.out_ann,
                         [ANN_CONV_DONE, ['conversion_done: first read after the start with RDY=0',
                                          'conversion_done', 'CONV✓']])

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.databuf = []
            self.spans = []
            self.seg_ss = ss

        elif ptype == 'START REPEAT':
            self._flush(ss)
            self.seg_ss = ss

        elif ptype in ('ADDRESS WRITE', 'ADDRESS READ'):
            self.is_read = ptype == 'ADDRESS READ'
            if ADDR_FIRST <= pdata <= ADDR_LAST:
                self.addr = pdata
                self.state = 'READ' if self.is_read else 'CONFIG'
            elif pdata == ADDR_GENERAL_CALL and not self.is_read:
                self.addr = pdata
                self.state = 'GENERAL'
            else:
                self.state = 'IGNORE'
                if not self.is_read:
                    self._warn(ss, es, 'Write to 0x%02X - not a MCP3422 (0x68-0x6F)' % pdata)

        elif ptype == 'DATA WRITE':
            if self.state == 'CONFIG':
                self.databuf.append(pdata)
                self._config_write(ss, es, pdata)
            elif self.state == 'GENERAL':
                self.databuf.append(pdata)
                if pdata == GC_RESET:
                    self.bits = 12
                    self.gain = 1
                    self.pending = False
                    self.put(ss, es, self.out_ann, [ANN_GENERAL_CALL,
                             ['General call reset [0x06] -> 12 bit, x1, continuous', 'GC reset']])
                elif pdata == GC_CONVERT:
                    self.put(ss, es, self.out_ann, [ANN_GENERAL_CALL,
                             ['General call convert [0x08] -> one-shot on all devices', 'GC convert']])
                    self._start_conversion(ss, es, 'General call 0x08')
                else:
                    self._warn(ss, es, 'Unknown general call byte 0x%02X' % pdata)

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.databuf.append(pdata)
                self.spans.append((ss, es))
                if len(self.databuf) == self._nbytes() + 1:
                    self._finish_read(es)

        elif ptype == 'STOP':
            if self.state == 'READ' and len(self.databuf) == self._nbytes():
                self._finish_read(es)     # result without the trailing config byte
            self._flush(es)
