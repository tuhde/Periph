import sigrokdecode as srd

ADDRS = {0x10}

BAND_NAMES = {0: 'US/Europe (87-108 MHz)', 1: 'Japan (76-91 MHz)',
              2: 'World wide (76-108 MHz)', 3: 'East Europe (65-76 MHz)'}
BAND_BASE_KHZ = {0: 87000, 1: 76000, 2: 76000, 3: 65000}
SPACE_NAMES = {0: '100 kHz', 1: '200 kHz', 2: '50 kHz', 3: '25 kHz'}
SPACE_KHZ = {0: 100, 1: 200, 2: 50, 3: 25}

# Write-block register offsets from the fixed write pointer 0x02
WRITE_REGS = ['CTRL (0x02)', 'CHAN (0x03)', 'R4 (0x04)', 'R5 (0x05)', 'R6 (0x06)', 'R7 (0x07)']
# Read-block register offsets from the fixed read pointer 0x0A
READ_REGS = ['STATUSA (0x0A)', 'STATUSB (0x0B)', 'RDSA (0x0C)', 'RDSB (0x0D)', 'RDSC (0x0E)', 'RDSD (0x0F)']

ANN_REG_WRITE = 0
ANN_REG_READ = 1
ANN_WARNING = 2


def _freq_mhz(band, space, chan):
    base = BAND_BASE_KHZ.get(band, 0)
    khz = SPACE_KHZ.get(space, 0)
    return (base + chan * khz) / 1000.0


def _decode_ctrl(raw):
    flags = []
    if raw & 0x8000: flags.append('DHIZ')
    if raw & 0x4000: flags.append('DMUTE')
    if raw & 0x2000: flags.append('MONO')
    if raw & 0x1000: flags.append('BASS')
    if raw & 0x0200: flags.append('SEEKUP')
    if raw & 0x0100: flags.append('SEEK')
    if raw & 0x0080: flags.append('SKMODE')
    if raw & 0x0008: flags.append('RDS_EN')
    if raw & 0x0004: flags.append('NEW_METHOD')
    if raw & 0x0002: flags.append('SOFT_RESET')
    if raw & 0x0001: flags.append('ENABLE')
    clk = (raw >> 4) & 0x07
    return 'CTRL 0x%04X [%s] CLK_MODE=%d' % (raw, ','.join(flags) if flags else '-', clk)


def _decode_chan(raw):
    chan = (raw >> 6) & 0x3FF
    tune = (raw >> 4) & 1
    band = (raw >> 2) & 0x03
    space = raw & 0x03
    freq = _freq_mhz(band, space, chan)
    return ('CHAN 0x%04X CHAN=%d TUNE=%d BAND=%s SPACE=%s -> %.2f MHz'
            % (raw, chan, tune, BAND_NAMES.get(band, str(band)), SPACE_NAMES.get(space, str(space)), freq),
            band, space)


def _decode_r4(raw):
    de = (raw >> 11) & 1
    softmute = (raw >> 9) & 1
    afcd = (raw >> 8) & 1
    return 'R4 0x%04X DE=%s SOFTMUTE_EN=%d AFCD=%d' % (raw, '50us' if de else '75us', softmute, afcd)


def _decode_r5(raw):
    int_mode = (raw >> 15) & 1
    seekth = (raw >> 8) & 0x0F
    volume = raw & 0x0F
    return 'R5 0x%04X INT_MODE=%d SEEKTH=%d VOLUME=%d' % (raw, int_mode, seekth, volume)


def _decode_r7(raw):
    th_sofrblend = (raw >> 10) & 0x1F
    band_65m_50m = (raw >> 9) & 1
    softblend_en = (raw >> 1) & 1
    freq_mode = raw & 1
    return ('R7 0x%04X TH_SOFRBLEND=%d BAND_65M_50M=%s SOFTBLEND_EN=%d FREQ_MODE=%d'
            % (raw, th_sofrblend, '65-76MHz' if band_65m_50m else '50MHz-based', softblend_en, freq_mode))


def _decode_status_a(raw, band, space):
    rdsr = (raw >> 15) & 1
    stc = (raw >> 14) & 1
    sf = (raw >> 13) & 1
    rdss = (raw >> 12) & 1
    st = (raw >> 10) & 1
    readchan = raw & 0x03FF
    freq = _freq_mhz(band, space, readchan)
    flags = []
    if rdsr: flags.append('RDSR')
    if stc: flags.append('STC')
    if sf: flags.append('SF')
    if rdss: flags.append('RDSS')
    if st: flags.append('ST(stereo)')
    return ('STATUSA 0x%04X [%s] READCHAN=%d -> %.2f MHz'
            % (raw, ','.join(flags) if flags else '-', readchan, freq))


def _decode_status_b(raw):
    rssi = (raw >> 9) & 0x7F
    fm_true = (raw >> 8) & 1
    fm_ready = (raw >> 7) & 1
    abcd_e = (raw >> 4) & 1
    blera = (raw >> 2) & 0x03
    blerb = raw & 0x03
    return ('STATUSB 0x%04X RSSI=%d FM_TRUE=%d FM_READY=%d ABCD_E=%d BLERA=%d BLERB=%d'
            % (raw, rssi, fm_true, fm_ready, abcd_e, blera, blerb))


def _ascii_pair(raw):
    hi = (raw >> 8) & 0xFF
    lo = raw & 0xFF
    c1 = chr(hi) if 0x20 <= hi < 0x7F else '.'
    c2 = chr(lo) if 0x20 <= lo < 0x7F else '.'
    return c1 + c2


class Decoder(srd.Decoder):
    api_version = 3
    id = 'rda5807m'
    name = 'RDA5807M'
    longname = 'RDA5807M FM stereo radio tuner'
    desc = 'Decode RDA5807M I2C fixed-pointer register block writes/reads.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['rda5807m']
    tags = ['IC', 'Radio']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_REG_WRITE, ANN_REG_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.is_read = False
        self.databuf = []
        self.ss_block = None
        # Last band/space seen in a write, used to interpret READCHAN on reads.
        # Defaults match this project's driver defaults (world wide, 100 kHz).
        self.last_band = 2
        self.last_space = 0

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _emit(self, ann_idx, ss, es, texts):
        self.put(ss, es, self.out_ann, [ann_idx, texts])

    def _finish_transaction(self):
        if not self.databuf:
            return

        # Group raw bytes into big-endian 16-bit words.
        words = []
        for i in range(0, len(self.databuf) - 1, 2):
            words.append((self.databuf[i] << 8) | self.databuf[i + 1])
        if len(self.databuf) % 2 != 0:
            self._warn(self.ss_block, self.es, 'Odd byte count %d, trailing byte dropped' % len(self.databuf))

        if self.is_read:
            names = READ_REGS
            lines = []
            for i, raw in enumerate(words):
                if i >= len(names):
                    self._warn(self.ss_block, self.es, 'Read past known registers at word %d' % i)
                    break
                if i == 0:
                    lines.append(_decode_status_a(raw, self.last_band, self.last_space))
                elif i == 1:
                    lines.append(_decode_status_b(raw))
                else:
                    lines.append('%s = 0x%04X ("%s")' % (names[i], raw, _ascii_pair(raw)))
            summary = ' | '.join(lines)
            self._emit(ANN_REG_READ, self.ss_block, self.es,
                       ['Read: %s' % summary, 'R %d words' % len(words)])
        else:
            names = WRITE_REGS
            lines = []
            for i, raw in enumerate(words):
                if i >= len(names):
                    self._warn(self.ss_block, self.es, 'Write past known registers at word %d' % i)
                    break
                if i == 0:
                    lines.append(_decode_ctrl(raw))
                elif i == 1:
                    text, band, space = _decode_chan(raw)
                    lines.append(text)
                    self.last_band, self.last_space = band, space
                elif i == 2:
                    lines.append(_decode_r4(raw))
                elif i == 3:
                    lines.append(_decode_r5(raw))
                elif i == 4:
                    lines.append('R6 (0x06) = 0x%04X (OPEN_MODE=%d)' % (raw, (raw >> 13) & 0x03))
                elif i == 5:
                    lines.append(_decode_r7(raw))
            summary = ' | '.join(lines)
            self._emit(ANN_REG_WRITE, self.ss_block, self.es,
                       ['Write: %s' % summary, 'W %d words' % len(words)])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf = []
            self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.is_read = (ptype == 'ADDRESS READ')
            self.databuf = []
            self.state = 'GET_DATA_READ' if self.is_read else 'GET_DATA_WRITE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            if self.state in ('GET_DATA_WRITE', 'GET_DATA_READ'):
                self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
