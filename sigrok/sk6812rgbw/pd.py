import sigrokdecode as srd

BYTES_PER_PIXEL = 4   # GRBW wire order

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


class Decoder(srd.Decoder):
    api_version = 3
    id = 'sk6812rgbw'
    name = 'SK6812RGBW'
    longname = 'SK6812RGBW RGBW LED'
    desc = ('Decode SK6812RGBW 32-bit GRBW pixel stream. '
            'Stacks on the neopixel transport decoder (set reset_us=80).')
    license = 'gplv2+'
    inputs = ['neopixel']
    outputs = ['sk6812rgbw']
    tags = ['IC', 'LED']

    annotations = (
        ('pixel',   'Pixel'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('pixels',   'Pixels',   (ANN_PIXEL,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.pixel_buf = []
        self.pixel_idx = 0
        self.ss_pixel  = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'BYTE':
            if self.ss_pixel is None:
                self.ss_pixel = ss
            self.pixel_buf.append(pdata)

            if len(self.pixel_buf) == BYTES_PER_PIXEL:
                g, r, b, w = self.pixel_buf
                self.put(self.ss_pixel, es, self.out_ann,
                         [ANN_PIXEL,
                          ['Pixel %d: R=%d G=%d B=%d W=%d #%02X%02X%02X%02X' %
                           (self.pixel_idx, r, g, b, w, r, g, b, w),
                           'P%d #%02X%02X%02X%02X' % (self.pixel_idx, r, g, b, w),
                           'R%d G%d B%d W%d' % (r, g, b, w)]])
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
