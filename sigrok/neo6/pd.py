import sigrokdecode as srd

ANN_SENTENCE = 0
ANN_FIELD    = 1
ANN_WARNING  = 2

# Sentence IDs this decoder annotates with field-level detail. Any other
# recognised NMEA sentence (GSA, GSV, GLL, TXT, ...) still gets a
# sentence-level annotation but no per-field breakdown.
_DETAILED = ('GGA', 'RMC', 'VTG')


def nmea_checksum_ok(sentence):
    """Validate the *XX checksum of a $...*XX NMEA sentence (no CR/LF)."""
    try:
        star = sentence.index('*')
    except ValueError:
        return False
    checksum = 0
    for ch in sentence[1:star]:
        checksum ^= ord(ch)
    try:
        expected = int(sentence[star + 1:star + 3], 16)
    except ValueError:
        return False
    return checksum == expected


def nmea_to_degrees(raw, hemisphere):
    """Convert NMEA ddmm.mmmm / dddmm.mmmm to signed decimal degrees."""
    value = float(raw)
    deg = int(value / 100)
    minutes = value - deg * 100
    decimal = deg + minutes / 60.0
    if hemisphere in ('S', 'W'):
        decimal = -decimal
    return decimal


def decode_fields(sentence_id, fields):
    """Return a list of human-readable 'name=value' strings for the
    sentence types this decoder details (GGA, RMC, VTG). Returns an empty
    list for any other sentence ID."""
    out = []
    if sentence_id == 'GGA' and len(fields) >= 15:
        out.append('time=' + fields[1])
        fix = fields[6]
        if fix and fields[2] and fields[4]:
            try:
                lat = nmea_to_degrees(fields[2], fields[3])
                lon = nmea_to_degrees(fields[4], fields[5])
                out.append('lat=%.6f deg' % lat)
                out.append('lon=%.6f deg' % lon)
            except ValueError:
                pass
        out.append('fix=' + (fix or '0'))
        out.append('sats=' + fields[7])
        if fields[8]:
            out.append('hdop=' + fields[8])
        if fields[9]:
            out.append('alt=%s m' % fields[9])
    elif sentence_id == 'RMC' and len(fields) >= 10:
        out.append('time=' + fields[1])
        out.append('status=' + fields[2])
        if fields[7]:
            try:
                out.append('speed=%.3f m/s' % (float(fields[7]) * 0.514444))
            except ValueError:
                pass
        if fields[8]:
            out.append('course=%s deg' % fields[8])
        out.append('date=' + fields[9])
    elif sentence_id == 'VTG' and len(fields) >= 8:
        if fields[1]:
            out.append('course=%s deg' % fields[1])
        if fields[7]:
            try:
                out.append('speed=%.3f m/s' % (float(fields[7]) / 3.6))
            except ValueError:
                pass
    return out


class Decoder(srd.Decoder):
    api_version = 3
    id = 'neo6'
    name = 'NEO-6'
    longname = 'u-blox NEO-6 GNSS NMEA stream'
    desc = 'Decode NMEA 0183 sentences from a NEO-6 GNSS module UART stream.'
    license = 'gplv2+'
    inputs = ['uart']
    outputs = ['neo6']
    tags = ['Sensor', 'IC']

    annotations = (
        ('sentence', 'NMEA sentence'),
        ('field',    'Decoded field'),
        ('warning',  'Warning'),
    )
    annotation_rows = (
        ('sentences', 'Sentences', (ANN_SENTENCE,)),
        ('fields',    'Fields',    (ANN_FIELD,)),
        ('warnings',  'Warnings',  (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.text     = ''
        self.ss_block = None
        self.in_sentence = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def decode(self, ss, es, data):
        ptype, rxtx, pdata = data
        if ptype != 'DATA':
            return

        # 'rxtx' identifies which UART line this byte came from. NEO-6's
        # NMEA output is the module-to-host direction, which the uart PD
        # calls RX (index/name 0 or 'rx' depending on libsigrokdecode
        # version) when the capture's RX channel is wired to the module's
        # TxD pin, per the standard wiring in the transport spec.
        if rxtx not in (0, 'rx'):
            return

        byte = pdata[0]
        char = chr(byte) if 0x20 <= byte < 0x7F else None

        if char == '$':
            self.text = '$'
            self.ss_block = ss
            self.in_sentence = True
            return

        if not self.in_sentence:
            return

        if char is None or byte in (0x0D, 0x0A):
            if byte == 0x0A:
                self._finish_sentence(es)
            return

        self.text += char
        if len(self.text) > 96:
            self.in_sentence = False
            self.text = ''

    def _finish_sentence(self, es):
        sentence = self.text
        self.in_sentence = False
        self.text = ''

        if not nmea_checksum_ok(sentence):
            self.put(self.ss_block, es, self.out_ann,
                     [ANN_WARNING, ['Bad checksum', 'CS err']])
            return

        star = sentence.index('*')
        body = sentence[1:star]
        fields = body.split(',')
        if len(fields[0]) < 5:
            self.put(self.ss_block, es, self.out_ann,
                     [ANN_WARNING, ['Malformed talker/sentence ID', 'ID err']])
            return

        talker = fields[0][:2]
        sentence_id = fields[0][2:5]
        self.put(self.ss_block, es, self.out_ann,
                 [ANN_SENTENCE, ['%s%s: %s' % (talker, sentence_id, body),
                                 sentence_id]])

        if sentence_id in _DETAILED:
            for entry in decode_fields(sentence_id, fields):
                self.put(self.ss_block, es, self.out_ann, [ANN_FIELD, [entry]])
