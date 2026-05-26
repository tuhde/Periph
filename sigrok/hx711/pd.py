import sigrokdecode as srd

# pulse count → (channel, gain) for the NEXT conversion
GAIN_MAP = {25: ('A', 128), 26: ('B', 32), 27: ('A', 64)}

ANN_READY     = 0
ANN_BIT       = 1
ANN_CONV      = 2
ANN_POWERDOWN = 3
ANN_WARNING   = 4
ANN_WAKEUP    = 5

DEFAULT_GAIN = ('A', 128)


class SamplerateError(Exception):
    pass


class Decoder(srd.Decoder):
    api_version = 3
    id = 'hx711'
    name = 'HX711'
    longname = 'HX711 24-bit ADC'
    desc = ('Decode HX711 2-wire bit-bang protocol (DOUT + PD_SCK) into '
            'signed 24-bit ADC conversions with channel and gain.')
    license = 'gplv2+'
    inputs = ['logic']
    outputs = ['hx711']
    tags = ['IC', 'ADC']

    channels = (
        {'id': 'dout', 'name': 'DOUT',   'desc': 'Data output (chip → MCU)'},
        {'id': 'sck',  'name': 'PD_SCK', 'desc': 'Clock / power-down (MCU → chip)'},
    )
    options = (
        {'id': 'inter_pulse_ms',
         'desc': 'Max gap between pulses within one conversion (ms)',
         'default': 5},
        {'id': 'show_wakeup',
         'desc': 'Show wake-up time annotation (PD_SCK LOW → DOUT LOW)',
         'default': 'no', 'values': ('yes', 'no')},
        {'id': 'show_bits',
         'desc': 'Show individual bit annotations',
         'default': 'no', 'values': ('yes', 'no')},
    )
    annotations = (
        ('ready',      'Ready'),
        ('bit',        'Bit'),
        ('conversion', 'Conversion'),
        ('powerdown',  'Power-down'),
        ('warning',    'Warning'),
        ('wakeup',     'Wake-up'),
    )
    annotation_rows = (
        ('ready',       'Ready',       (ANN_READY,)),
        ('bits',        'Bits',        (ANN_BIT,)),
        ('conversions', 'Conversions', (ANN_CONV,)),
        ('power',       'Power',       (ANN_POWERDOWN, ANN_WAKEUP)),
        ('warnings',    'Warnings',    (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.samplerate = None

    def start(self):
        self.out_ann    = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def decode(self):
        if not self.samplerate:
            raise SamplerateError('HX711 decoder requires a sample rate.')

        pd_samples          = int(self.samplerate * 60e-6)
        inter_pulse_samples = int(self.samplerate * self.options['inter_pulse_ms'] * 1e-3)

        current_gain = DEFAULT_GAIN
        wakeup_ss    = None

        while True:
            # Wait for DOUT LOW (level — catches both fresh falling edges and
            # already-LOW cases after a fast inter-conversion gap) or SCK HIGH
            # (power-down detection).
            pins = self.wait([{0: 'l'}, {1: 'r'}])

            if self.matched[1] and not self.matched[0]:
                # SCK rose while DOUT is still HIGH — possible power-down.
                pd_ss = self.samplenum
                self.wait([{1: 'f'}, {'skip': pd_samples}])
                if not self.matched[0]:
                    # SCK held HIGH > 60 µs — confirmed power-down.
                    pd_es = self.samplenum
                    self.put(pd_ss, pd_es, self.out_ann,
                             [ANN_POWERDOWN, ['Power-down', 'PD']])
                    self.put(pd_ss, pd_es, self.out_python, ['POWERDOWN', None])
                    # Wait for SCK to go LOW (power-up); start wake-up timer.
                    self.wait({1: 'f'})
                    wakeup_ss    = self.samplenum
                    current_gain = DEFAULT_GAIN
                continue

            # DOUT fell LOW — conversion result is available.
            ready_ss = self.samplenum

            if wakeup_ss is not None:
                if self.options['show_wakeup'] == 'yes':
                    dur_ms = (ready_ss - wakeup_ss) / self.samplerate * 1000
                    self.put(wakeup_ss, ready_ss, self.out_ann,
                             [ANN_WAKEUP, [
                                 'Wake-up: %.1f ms' % dur_ms,
                                 '%.1f ms' % dur_ms,
                             ]])
                wakeup_ss = None

            # Wait for first SCK rising edge; pin values tell us DOUT at that moment.
            pins = self.wait({1: 'r'})
            ready_es = self.samplenum
            self.put(ready_ss, ready_es, self.out_ann, [ANN_READY, ['Ready', 'RDY']])

            # Clock loop — invariant: samplenum and pins are at a SCK rising edge.
            raw         = 0
            pulse_count = 0
            conv_ss     = self.samplenum
            last_es     = self.samplenum
            aborted     = False

            while True:
                bit_ss = self.samplenum

                # Wait for SCK falling edge; bail if SCK stays HIGH > 60 µs.
                pins = self.wait([{1: 'f'}, {'skip': pd_samples}])
                bit_es   = self.samplenum
                dout_val = pins[0]  # DOUT sampled at the falling edge

                if not self.matched[0]:
                    self._warn(bit_ss, bit_es,
                               'PD_SCK HIGH >60 µs mid-conversion (power-down triggered)')
                    pd_ss = bit_ss
                    self.wait({1: 'f'})
                    self.put(pd_ss, self.samplenum, self.out_ann,
                             [ANN_POWERDOWN, ['Power-down', 'PD']])
                    self.put(pd_ss, self.samplenum, self.out_python,
                             ['POWERDOWN', None])
                    current_gain = DEFAULT_GAIN
                    aborted = True
                    break

                pulse_count += 1
                last_es = bit_es

                if pulse_count <= 24:
                    raw = (raw << 1) | dout_val
                    if self.options['show_bits'] == 'yes':
                        self.put(bit_ss, bit_es, self.out_ann, [ANN_BIT, [str(dout_val)]])

                if pulse_count >= 27:
                    break

                # Wait for next SCK rising edge or end-of-conversion timeout.
                pins = self.wait([{1: 'r'}, {'skip': inter_pulse_samples}])
                if not self.matched[0]:
                    break  # no more pulses — conversion complete

            if aborted:
                continue

            if pulse_count in GAIN_MAP:
                signed       = raw - 0x1000000 if raw >= 0x800000 else raw
                ch, gain     = current_gain
                next_ch, next_gain = GAIN_MAP[pulse_count]
                current_gain = (next_ch, next_gain)

                self.put(conv_ss, last_es, self.out_ann,
                         [ANN_CONV, [
                             '%s-%d: %d (0x%06X)  Next: Channel %s Gain %d' % (
                                 ch, gain, signed, raw & 0xFFFFFF, next_ch, next_gain),
                             '%s-%d: %d (0x%06X)  Next: %s-%d' % (
                                 ch, gain, signed, raw & 0xFFFFFF, next_ch, next_gain),
                             '%s-%d: %d  Next: %s-%d' % (ch, gain, signed, next_ch, next_gain),
                             '%s-%d: %d' % (ch, gain, signed),
                         ]])
                self.put(conv_ss, last_es, self.out_python,
                         ['CONVERSION', (signed, ch, gain)])
            elif pulse_count > 0:
                self._warn(conv_ss, last_es,
                           'Invalid pulse count %d (expected 25, 26, or 27)' % pulse_count)
