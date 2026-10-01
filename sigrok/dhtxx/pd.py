import sigrokdecode as srd

# Protocol thresholds (µs) — see specs/transport_dhtxx.md.
T_START_LOW_MS_MIN = 18
T_START_LOW_MS_MAX = 30
T_RESPONSE_LOW_US  = 81
T_RESPONSE_HIGH_US = 85
T_BIT_LOW_US       = 54
T_BIT_THRESHOLD_US = 40

ANN_START         = 0
ANN_RESPONSE      = 1
ANN_BIT           = 2
ANN_BYTE          = 3
ANN_CHECKSUM_OK   = 4
ANN_WARNING       = 5


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
    id = 'dhtxx'
    name = 'DHTxx'
    longname = 'DHT11 / DHT22 single-wire temperature/humidity protocol'
    desc = 'Decode DHTxx single-wire protocol framing, bits, and bytes.'
    license = 'gplv2+'
    inputs = ['logic']
    outputs = ['dhtxx']
    tags = ['IC', 'Sensor']

    annotations = (
        ('start',         'Host start signal'),
        ('response',      'Sensor response'),
        ('bit',           'Data bit'),
        ('byte',          'Decoded byte'),
        ('checksum',      'Checksum (OK / mismatch)'),
        ('warning',       'Warning'),
    )
    annotation_rows = (
        ('frames',  'Frames',   (ANN_START, ANN_RESPONSE)),
        ('bits',    'Bits',     (ANN_BIT,)),
        ('bytes',   'Bytes',    (ANN_BYTE, ANN_CHECKSUM_OK)),
        ('warnings','Warnings', (ANN_WARNING,)),
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
        self.state          = 'IDLE'
        self.ss_frame       = None
        self.byte_index     = 0
        self.bit_count      = 0
        self.current_byte   = 0
        self.last_edge_ss   = None
        self.last_edge_es   = None
        self.last_level     = 1
        self.frame_bytes    = []

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _edge_us(self, ss, es):
        # sigrok logic edges are point-in-time, so duration is 0. The
        # caller tracks transition timestamps separately. The decoder
        # estimates the duration from the time between consecutive edges
        # (in samples) and the samplerate known to the host (sigrok
        # provides it via self.samplerate). The host-side annotation
        # re-formats with µs; the decoder simply records sample counts.
        return es - ss

    def _us_from_samples(self, samples):
        if self.samplerate is None:
            return 0
        return int(samples * 1_000_000 / self.samplerate)

    def decode(self, ss, es, data):
        # Input is a logic stream — data is the level (0/1) at the edge.
        level = data
        if self.state == 'IDLE':
            # Wait for a falling edge (host start signal).
            if self.last_level == 1 and level == 0:
                self.ss_frame = ss
                self.state = 'START_LOW'
                self.last_edge_ss = ss
                self.last_edge_es = es
                self.last_level = 0
            return

        # Compute duration of the previous pulse.
        pulse_samples = ss - self.last_edge_ss
        pulse_us = self._us_from_samples(pulse_samples)

        if self.state == 'START_LOW':
            if level == 1:
                # End of host start LOW — verify duration.
                if T_START_LOW_MS_MIN * 1000 <= pulse_us <= T_START_LOW_MS_MAX * 1000:
                    self.put(self.ss_frame, es, self.out_ann,
                             [ANN_START, ['Start: {} ms'.format(pulse_us // 1000)]])
                else:
                    self.put(self.ss_frame, es, self.out_ann,
                             [ANN_WARNING, ['Start LOW out of range: {} us'.format(pulse_us)]])
                self.state = 'START_RELEASE'
            self.last_edge_ss = ss
            self.last_level = level
            return

        if self.state == 'START_RELEASE':
            # Brief release gap (~13 µs).
            self.state = 'RESPONSE_LOW'
            self.last_edge_ss = ss
            self.last_level = level
            return

        if self.state == 'RESPONSE_LOW':
            if level == 1:
                # End of sensor response LOW (~83 µs).
                self.put(self.ss_frame, es, self.out_ann,
                         [ANN_RESPONSE, ['Response LOW: {} us'.format(pulse_us)]])
                self.state = 'RESPONSE_HIGH'
            self.last_edge_ss = ss
            self.last_level = level
            return

        if self.state == 'RESPONSE_HIGH':
            if level == 0:
                self.put(self.ss_frame, es, self.out_ann,
                         [ANN_RESPONSE, ['Response HIGH: {} us'.format(pulse_us)]])
                # Begin reading 40 bits.
                self.state = 'BIT_LOW'
                self.byte_index = 0
                self.bit_count = 0
                self.current_byte = 0
                self.frame_bytes = []
            self.last_edge_ss = ss
            self.last_level = level
            return

        if self.state == 'BIT_LOW':
            if level == 1:
                # End of bit-start LOW pulse (~54 µs).
                self.last_bit_low_end_ss = es
                self.state = 'BIT_HIGH'
            self.last_edge_ss = ss
            self.last_level = level
            return

        if self.state == 'BIT_HIGH':
            if level == 0:
                # End of bit HIGH pulse.
                bit_value = 1 if pulse_us > T_BIT_THRESHOLD_US else 0
                self.current_byte = (self.current_byte << 1) | bit_value
                self.bit_count += 1
                self.put(self.last_bit_low_end_ss, es, self.out_ann,
                         [ANN_BIT, ['Bit {}: {}'.format(self.bit_count, bit_value)]])
                if self.bit_count == 8:
                    self.frame_bytes.append(self.current_byte)
                    self.put(self.ss_frame, es, self.out_ann,
                             [ANN_BYTE, ['Byte {}: 0x{:02X} ({})'.format(self.byte_index, self.current_byte, self.current_byte)]])
                    self.current_byte = 0
                    self.bit_count = 0
                    self.byte_index += 1
                    if self.byte_index == 5:
                        # Compute checksum.
                        if len(self.frame_bytes) == 5:
                            cs = sum(self.frame_bytes[:4]) & 0xFF
                            if cs == self.frame_bytes[4]:
                                self.put(self.ss_frame, es, self.out_ann,
                                         [ANN_CHECKSUM_OK, ['Checksum OK: 0x{:02X}'.format(cs)]])
                            else:
                                self.put(self.ss_frame, es, self.out_ann,
                                         [ANN_CHECKSUM_OK, ['Checksum mismatch: 0x{:02X} != 0x{:02X}'.format(cs, self.frame_bytes[4])]])
                        self.state = 'IDLE'
                        self.last_level = 1
                        return
                self.state = 'BIT_LOW'
            self.last_edge_ss = ss
            self.last_level = level
            return
