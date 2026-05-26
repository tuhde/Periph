import sigrokdecode as srd

ANN_START    = 0
ANN_RESPONSE = 1
ANN_BIT      = 2
ANN_DATA     = 3
ANN_WARNING  = 4

THRESHOLD_HIGH = 40
T_HOST_LOW_MIN = 18000
T_HOST_LOW_MAX = 30000
T_RESPONSE_LOW_MIN = 81
T_RESPONSE_LOW_MAX = 85
T_RESPONSE_HIGH_MIN = 85
T_RESPONSE_HIGH_MAX = 88
T_BIT_LOW_MIN = 52
T_BIT_LOW_MAX = 56
T_BIT0_HIGH_MIN = 23
T_BIT0_HIGH_MAX = 27
T_BIT1_HIGH_MIN = 68
T_BIT1_HIGH_MAX = 74


class Decoder(srd.Decoder):
    api_version = 3
    id = 'dht11'
    name = 'DHT11'
    longname = 'DHT11 temperature/humidity sensor'
    desc = 'Decode DHT11 single-wire bit-bang protocol for temperature and humidity.'
    license = 'gplv2+'
    inputs = ['logic']
    outputs = ['dht11']
    tags = ['IC', 'Humidity']

    channels = (
        {'id': 'data', 'name': 'DATA', 'desc': 'Single-wire data line'},
    )

    annotations = (
        ('start',       'Start signal'),
        ('response',    'Sensor response'),
        ('bit',         'Bit'),
        ('data',        'Data frame'),
        ('warning',     'Warning'),
    )
    annotation_rows = (
        ('signals',  'Signals',  (ANN_START, ANN_RESPONSE)),
        ('bits',     'Bits',     (ANN_BIT,)),
        ('data',     'Data',     (ANN_DATA,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.bits = []
        self.bit_ss = None
        self.data_buf = []

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _emit_bit(self, ss, es, bit):
        val = '1' if bit else '0'
        self.put(ss, es, self.out_ann, [ANN_BIT, [val, val]])

    def _emit_data(self, ss, es, text):
        self.put(ss, es, self.out_ann, [ANN_DATA, [text]])

    def _emit_start(self, ss, es):
        self.put(ss, es, self.out_ann, [ANN_START, ['START', 'S']])

    def _emit_response(self, ss, es):
        self.put(ss, es, self.out_ann, [ANN_RESPONSE, ['RESP', 'R']])

    def decode(self):
        while True:
            if self.state == 'IDLE':
                self.wait([{0: 'f'}])
                self.start_ss = self.samplenum
                self.state = 'HOST_LOW'

            elif self.state == 'HOST_LOW':
                self.wait([{0: 'r'}])
                t_host_low = self.samplenum - self.start_ss
                if t_host_low < T_HOST_LOW_MIN * 1000 or t_host_low > T_HOST_LOW_MAX * 1000:
                    self._warn(self.start_ss, self.samplenum,
                               'Host start LOW %d us (expected 18000-30000 us)' % (t_host_low / 1000))
                self.state = 'WAIT_RESPONSE'

            elif self.state == 'WAIT_RESPONSE':
                self.wait([{0: 'f'}])
                self.resp_low_ss = self.samplenum
                self.state = 'RESP_LOW'

            elif self.state == 'RESP_LOW':
                self.wait([{0: 'r'}])
                t_low = self.samplenum - self.resp_low_ss
                if t_low < T_RESPONSE_LOW_MIN * 1000 or t_low > T_RESPONSE_LOW_MAX * 1000:
                    self._warn(self.resp_low_ss, self.samplenum,
                               'Response LOW %d us (expected 81-85 us)' % (t_low / 1000))
                self.state = 'RESP_HIGH'

            elif self.state == 'RESP_HIGH':
                self.wait([{0: 'f'}])
                t_high = self.samplenum - self.resp_low_ss - t_low
                if t_high < T_RESPONSE_HIGH_MIN * 1000 or t_high > T_RESPONSE_HIGH_MAX * 1000:
                    self._warn(self.resp_low_ss, self.samplenum,
                               'Response HIGH %d us (expected 85-88 us)' % (t_high / 1000))
                self._emit_response(self.resp_low_ss, self.samplenum)
                self.state = 'BITS'
                self.bits = []
                self.bit_idx = 0

            elif self.state == 'BITS':
                self.wait([{0: 'r'}])
                bit_low_ss = self.samplenum
                bit_low_width = 0
                self.wait([{0: 'f'}])
                bit_low_width = self.samplenum - bit_low_ss
                if bit_low_width < T_BIT_LOW_MIN * 1000 or bit_low_width > T_BIT_LOW_MAX * 1000:
                    self._warn(bit_low_ss, self.samplenum,
                               'Bit LOW %d us (expected 52-56 us)' % (bit_low_width / 1000))

                bit_high_ss = self.samplenum
                self.wait([{0: 'r'}, {0: 'f'}])
                if self.matched[0]:
                    self.wait([{0: 'f'}])
                bit_high_width = self.samplenum - bit_high_ss

                if bit_high_width >= THRESHOLD_HIGH * 1000:
                    bit_val = 1
                else:
                    bit_val = 0

                self._emit_bit(bit_low_ss, self.samplenum, bit_val)
                self.bits.append(bit_val)
                self.bit_idx += 1

                if self.bit_idx == 40:
                    self.state = 'DATA'

            elif self.state == 'DATA':
                result = 0
                for b in self.bits:
                    result = (result << 1) | b

                hum_int = (result >> 32) & 0xFF
                hum_dec = (result >> 24) & 0xFF
                temp_int = (result >> 16) & 0xFF
                temp_dec = (result >> 8) & 0xFF
                checksum = result & 0xFF

                sum_ok = (hum_int + hum_dec + temp_int + temp_dec) & 0xFF == checksum

                sign = '-' if (temp_dec & 0x80) else '+'
                temp_dec_val = temp_dec & 0x7F
                temp_str = '%s%d.%d' % (sign, temp_int, temp_dec_val)
                hum_str = '%d.%d' % (hum_int, hum_dec)
                checksum_str = 'OK' if sum_ok else 'FAIL'

                frame_str = 'H=%s%% T=%sC checksum=%s' % (hum_str, temp_str, checksum_str)
                self._emit_data(self.bits[0], self.samplenum, frame_str)

                if not sum_ok:
                    self._warn(self.bits[0], self.samplenum, 'Checksum mismatch')

                self.state = 'IDLE'
