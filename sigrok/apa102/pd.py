import sigrokdecode as srd

BYTES_PER_PIXEL = 4   # BGR + hardware brightness: [0xE0|brightness, B, G, R]

ANN_PIXEL   = 0
ANN_START   = 1
ANN_END     = 2
ANN_WARNING = 3


class Decoder(srd.Decoder):
    api_version = 3
    id = 'apa102'
    name = 'APA102'
    longname = 'APA102 RGB LED'
    desc = ('Decode APA102 synchronous SPI frame into pixel data with hardware brightness. '
            'Stacks on the spi transport decoder.')
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['apa102']
    tags = ['IC', 'LED']

    annotations = (
        ('pixel',   'Pixel'),
        ('start',   'Start Frame'),
        ('end',     'End Frame'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('pixels',   'Pixels',   (ANN_PIXEL,)),
        ('frames',   'Frames',   (ANN_START, ANN_END)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state       = 'IDLE'
        self.pixel_buf   = []
        self.pixel_idx   = 0
        self.ss_pixel    = None
        self.ss_start    = None
        self.ss_end      = None
        self.pixels_seen = 0

    def start(self):
        self.out_ann    = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _finish_end_frame(self, es):
        if self.ss_end is not None:
            self.put(self.ss_end, es, self.out_ann,
                     [ANN_END,
                      ['End frame (%d bytes)' % self.end_ff_count,
                       'End %dB' % self.end_ff_count,
                       'E%d' % self.end_ff_count]])
            self.put(self.ss_end, es, self.out_python, ['END', self.end_ff_count])
            self.ss_end = None

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype != 'BYTE':
            return

        byte = pdata[0] if isinstance(pdata, (bytes, bytearray)) else pdata

        if self.state == 'IDLE':
            if byte == 0x00:
                self.state = 'START'
                self.ss_start = ss
                self.start_zero_count = 1
            else:
                self._warn(ss, es, 'Unexpected byte 0x%02X before start frame' % byte)
        elif self.state == 'START':
            if byte == 0x00:
                self.start_zero_count += 1
                if self.start_zero_count == 4:
                    self.state = 'PIXEL'
                    self.pixels_seen = 0
                    self.put(self.ss_start, es, self.out_ann,
                             [ANN_START, ['Start frame', 'Start', 'S']])
                    self.put(self.ss_start, es, self.out_python, ['START', 4])
                    self.ss_start = None
            else:
                self._warn(ss, es, 'Invalid start frame byte 0x%02X' % byte)
                self.state = 'IDLE'
        elif self.state == 'PIXEL':
            self.pixel_buf.append(byte)
            if self.ss_pixel is None:
                self.ss_pixel = ss

            if len(self.pixel_buf) == BYTES_PER_PIXEL:
                brightness_byte, b, g, r = self.pixel_buf
                hw_brightness = brightness_byte & 0x1F
                if (brightness_byte & 0xE0) != 0xE0:
                    self._warn(self.ss_pixel, es,
                               'Invalid brightness byte 0x%02X (preamble bits not 111)' % brightness_byte)
                self.put(self.ss_pixel, es, self.out_ann,
                         [ANN_PIXEL,
                          ['Pixel %d: brightness=%d R=%d G=%d B=%d #%02X%02X%02X' %
                           (self.pixel_idx, hw_brightness, r, g, b, r, g, b),
                           'P%d #%02X%02X%02X' % (self.pixel_idx, r, g, b),
                           '#%02X%02X%02X' % (r, g, b)]])
                self.put(self.ss_pixel, es, self.out_python, ['PIXEL', (self.pixel_idx, hw_brightness, r, g, b)])
                self.pixel_idx += 1
                self.pixels_seen += 1
                self.pixel_buf = []
                self.ss_pixel = None

                if byte == 0xFF:
                    self.state = 'END'
                    self.ss_end = ss
                    self.end_ff_count = 1
        elif self.state == 'END':
            if byte == 0xFF:
                self.end_ff_count += 1
            else:
                self._finish_end_frame(es)
                self.state = 'PIXEL'
                self.pixel_buf = [byte]
                self.ss_pixel = ss

                if len(self.pixel_buf) == BYTES_PER_PIXEL:
                    brightness_byte, b, g, r = self.pixel_buf
                    hw_brightness = brightness_byte & 0x1F
                    if (brightness_byte & 0xE0) != 0xE0:
                        self._warn(self.ss_pixel, es,
                                   'Invalid brightness byte 0x%02X (preamble bits not 111)' % brightness_byte)
                    self.put(self.ss_pixel, es, self.out_ann,
                             [ANN_PIXEL,
                              ['Pixel %d: brightness=%d R=%d G=%d B=%d #%02X%02X%02X' %
                               (self.pixel_idx, hw_brightness, r, g, b, r, g, b),
                               'P%d #%02X%02X%02X' % (self.pixel_idx, r, g, b),
                               '#%02X%02X%02X' % (r, g, b)]])
                    self.put(self.ss_pixel, es, self.out_python, ['PIXEL', (self.pixel_idx, hw_brightness, r, g, b)])
                    self.pixel_idx += 1
                    self.pixels_seen += 1
                    self.pixel_buf = []
                    self.ss_pixel = None
                    if byte == 0xFF:
                        self.state = 'END'
                        self.ss_end = ss
                        self.end_ff_count = 1