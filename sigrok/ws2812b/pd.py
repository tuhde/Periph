import sigrokdecode as srd

BYTES_PER_PIXEL = 3   # GRB wire order

ANN_PIXEL   = 0
ANN_WARNING = 1


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
    id = 'ws2812b'
    name = 'WS2812B'
    longname = 'WS2812B RGB LED'
    desc = ('Decode WS2812B 24-bit GRB pixel stream. '
            'Stacks on the neopixel transport decoder.')
    license = 'gplv2+'
    inputs = ['neopixel']
    outputs = ['ws2812b']
    tags = ['IC', 'LED']

    annotations = (
        ('pixel',   'Pixel'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('pixels',   'Pixels',   (ANN_PIXEL,)),
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
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.pixel_buf = []
        self.pixel_idx = 0
        self.ss_pixel  = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'BYTE':
            if self.ss_pixel is None:
                self.ss_pixel = ss
            self.pixel_buf.append(pdata)

            if len(self.pixel_buf) == BYTES_PER_PIXEL:
                g, r, b = self.pixel_buf
                self.put(self.ss_pixel, es, self.out_ann,
                         [ANN_PIXEL,
                          ['Pixel %d: R=%d G=%d B=%d #%02X%02X%02X' %
                           (self.pixel_idx, r, g, b, r, g, b),
                           'P%d #%02X%02X%02X' % (self.pixel_idx, r, g, b),
                           '#%02X%02X%02X' % (r, g, b)]])
                self.put(self.ss_pixel, es, self.out_python, ('PIXEL', (self.pixel_idx, r, g, b)))
                self.pixel_idx += 1
                self.pixel_buf  = []
                self.ss_pixel   = None

        elif ptype == 'RESET':
            if self.pixel_buf:
                self._warn(ss, es,
                           'Reset with incomplete pixel (%d of %d bytes)' %
                           (len(self.pixel_buf), BYTES_PER_PIXEL))
            self.pixel_buf = []
            self.pixel_idx = 0
            self.ss_pixel  = None
