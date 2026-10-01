import sigrokdecode as srd

CH_SER_IN = 0
CH_SRCK   = 1
CH_RCK    = 2
CH_SRCLR  = 3
CH_G      = 4

ANN_BYTE      = 0
ANN_LATCH     = 1
ANN_CLEAR     = 2
ANN_DISABLED  = 3
ANN_WARNING   = 4


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
    id = 'sipo'
    name = 'SiPo'
    longname = 'Serial-in/parallel-out shift register'
    desc = ('Decode the SER IN/SRCK/RCK/SRCLR/G control sequence used by '
            'cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) '
            'into latched output-register writes.')
    license = 'gplv2+'
    inputs = ['logic']
    outputs = ['sipo']
    tags = ['Embedded/Industrial']

    channels = (
        {'id': 'ser_in', 'name': 'SER IN', 'desc': 'Serial data in (SPI MOSI)'},
        {'id': 'srck',   'name': 'SRCK',   'desc': 'Shift register clock (SPI SCK)'},
        {'id': 'rck',    'name': 'RCK',    'desc': 'Register clock (latch)'},
    )
    optional_channels = (
        {'id': 'srclr', 'name': 'SRCLR', 'desc': 'Active-low shift register clear'},
        {'id': 'g',     'name': 'G',     'desc': 'Active-low output enable'},
    )
    annotations = (
        ('byte',     'Byte'),
        ('latch',    'Latch'),
        ('clear',    'Clear'),
        ('disabled', 'Outputs disabled'),
        ('warning',  'Warning'),
    )
    annotation_rows = (
        ('bytes',     'Bytes',            (ANN_BYTE,)),
        ('latches',   'Latches',          (ANN_LATCH,)),
        ('clears',    'Clears',           (ANN_CLEAR,)),
        ('disableds', 'Outputs disabled', (ANN_DISABLED,)),
        ('warnings',  'Warnings',         (ANN_WARNING,)),
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
        self.current_byte = 0
        self.bit_count    = 0
        self.byte_ss      = None
        self.latch_bytes  = bytearray()
        self.latch_ss     = None
        self.srclr_ss     = None
        self.g_ss         = None

    def start(self):
        self.out_ann    = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def decode(self):
        have_srclr = self.has_channel(CH_SRCLR)
        have_g     = self.has_channel(CH_G)

        while True:
            conditions = [{CH_SRCK: 'r'}, {CH_RCK: 'r'}]
            if have_srclr:
                conditions.append({CH_SRCLR: 'e'})
            if have_g:
                conditions.append({CH_G: 'e'})

            pins = self.wait(conditions)
            idx = 2

            if self.matched[0]:
                # SRCK rising edge — sample SER IN (MSB-first, 8 bits per byte).
                if self.byte_ss is None:
                    self.byte_ss = self.samplenum
                if self.latch_ss is None:
                    self.latch_ss = self.samplenum
                self.current_byte = (self.current_byte << 1) | pins[CH_SER_IN]
                self.bit_count += 1
                if self.bit_count == 8:
                    self.put(self.byte_ss, self.samplenum, self.out_ann,
                             [ANN_BYTE, ['0x%02X' % self.current_byte, '%d' % self.current_byte]])
                    self.latch_bytes.append(self.current_byte)
                    self.current_byte = 0
                    self.bit_count = 0
                    self.byte_ss = None

            if self.matched[1]:
                # RCK rising edge — latch buffered bytes into the output register.
                ss = self.latch_ss if self.latch_ss is not None else self.samplenum
                if self.bit_count:
                    self.put(ss, self.samplenum, self.out_ann,
                             [ANN_WARNING, ['Latch with %d stray bit(s) not forming a full byte' % self.bit_count,
                                            'Stray bits: %d' % self.bit_count, 'BITS?']])
                self.put(ss, self.samplenum, self.out_ann,
                         [ANN_LATCH, [
                             'Latch: ' + ' '.join('%02X' % b for b in self.latch_bytes),
                             'Latch %dB' % len(self.latch_bytes),
                         ]])
                self.put(ss, self.samplenum, self.out_python, ('LATCH', bytes(self.latch_bytes)))
                self.latch_bytes = bytearray()
                self.latch_ss = None

            if have_srclr and self.matched[idx]:
                if pins[CH_SRCLR] == 0:
                    self.srclr_ss = self.samplenum
                elif self.srclr_ss is not None:
                    self.put(self.srclr_ss, self.samplenum, self.out_ann,
                             [ANN_CLEAR, ['Clear', 'CLR']])
                    self.put(self.srclr_ss, self.samplenum, self.out_python, ('CLEAR', None))
                    self.srclr_ss = None
            if have_srclr:
                idx += 1

            if have_g and self.matched[idx]:
                if pins[CH_G] == 1:
                    self.g_ss = self.samplenum
                elif self.g_ss is not None:
                    self.put(self.g_ss, self.samplenum, self.out_ann,
                             [ANN_DISABLED, ['Outputs disabled', 'DISABLED']])
                    self.g_ss = None
