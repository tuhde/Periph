import sigrokdecode as srd

# High-pulse threshold separating a 0-bit from a 1-bit (600 ns)
T_THRESHOLD_NS = 600

ANN_BIT     = 0
ANN_BYTE    = 1
ANN_RESET   = 2
ANN_WARNING = 3


class SamplerateError(Exception):
    pass


class Decoder(srd.Decoder):
    api_version = 3
    id = 'neopixel'
    name = 'NeoPixel'
    longname = 'NeoPixel NZR single-wire transport'
    desc = ('Decode NeoPixel (WS2812B-compatible) single-wire NZR bit stream '
            'into bytes and reset pulses. Stack ws2812b or sk6812rgbw on top.')
    license = 'gplv2+'
    inputs = ['logic']
    outputs = ['neopixel']
    tags = ['Embedded/Industrial']

    channels = (
        {'id': 'din', 'name': 'DIN', 'desc': 'NeoPixel data in'},
    )
    options = (
        {'id': 'reset_us',
         'desc': 'Reset pulse minimum duration (µs) — 50 for WS2812B, 80 for SK6812RGBW',
         'default': 50},
    )
    annotations = (
        ('bit',     'Bit'),
        ('byte',    'Byte'),
        ('reset',   'Reset'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('bits',     'Bits',     (ANN_BIT,)),
        ('bytes',    'Bytes',    (ANN_BYTE,)),
        ('resets',   'Resets',   (ANN_RESET,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.samplerate        = None
        self.threshold_samples = None
        self.reset_samples     = None

    def start(self):
        self.out_ann    = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate        = value
            self.threshold_samples = int(value * T_THRESHOLD_NS * 1e-9)
            self.reset_samples     = int(value * self.options['reset_us'] * 1e-6)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def decode(self):
        if not self.samplerate:
            raise SamplerateError('NeoPixel decoder requires a sample rate.')

        current_byte = 0
        bit_count    = 0
        byte_count   = 0
        byte_ss      = None

        while True:
            # Rising edge = start of a bit's high pulse
            self.wait({0: 'r'})
            bit_ss = self.samplenum
            if byte_ss is None:
                byte_ss = bit_ss

            # Wait for falling edge; if the reset window expires first it is
            # a protocol error (a valid bit ends well before reset time)
            self.wait([{0: 'f'}, {'skip': self.reset_samples}])

            if not self.matched[0]:
                self._warn(bit_ss, self.samplenum, 'No falling edge within reset window')
                current_byte = 0
                bit_count    = 0
                byte_count   = 0
                byte_ss      = None
                continue

            bit_es   = self.samplenum
            high_dur = bit_es - bit_ss
            bit_val  = 1 if high_dur > self.threshold_samples else 0

            self.put(bit_ss, bit_es, self.out_ann, [ANN_BIT, [str(bit_val)]])

            current_byte = (current_byte << 1) | bit_val
            bit_count   += 1

            if bit_count == 8:
                byte_es = self.samplenum
                self.put(byte_ss, byte_es, self.out_ann,
                         [ANN_BYTE, ['0x%02X' % current_byte, '%d' % current_byte]])
                self.put(byte_ss, byte_es, self.out_python, ['BYTE', current_byte])
                byte_count  += 1
                current_byte = 0
                bit_count    = 0
                byte_ss      = None

            # After the falling edge: wait for the next rising edge (next bit)
            # or a long low (reset / latch pulse)
            self.wait([{0: 'r'}, {'skip': self.reset_samples}])

            if not self.matched[0]:
                # Reset detected
                reset_ss = bit_es
                reset_es = self.samplenum

                if bit_count:
                    self._warn(reset_ss, reset_es,
                               'Reset with %d incomplete bit%s' %
                               (bit_count, 's' if bit_count != 1 else ''))

                self.put(reset_ss, reset_es, self.out_ann,
                         [ANN_RESET,
                          ['Reset — %d byte%s' % (byte_count, 's' if byte_count != 1 else ''),
                           'RST %dB' % byte_count]])
                self.put(reset_ss, reset_es, self.out_python, ['RESET', byte_count])

                current_byte = 0
                bit_count    = 0
                byte_count   = 0
                byte_ss      = None
                # The matched rising edge is the first bit of the next frame;
                # jump straight back to the high-pulse measurement
                bit_ss   = self.samplenum
                byte_ss  = bit_ss
                self.wait([{0: 'f'}, {'skip': self.reset_samples}])
                if not self.matched[0]:
                    self._warn(bit_ss, self.samplenum, 'No falling edge after reset')
                    byte_ss = None
                    continue
                bit_es   = self.samplenum
                high_dur = bit_es - bit_ss
                bit_val  = 1 if high_dur > self.threshold_samples else 0
                self.put(bit_ss, bit_es, self.out_ann, [ANN_BIT, [str(bit_val)]])
                current_byte = bit_val
                bit_count    = 1
                byte_ss      = bit_ss
