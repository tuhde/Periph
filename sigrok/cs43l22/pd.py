import sigrokdecode as srd

ADDRS = {0x4A, 0x4B}

ANN_WRITE = 0
ANN_READ = 1
ANN_INIT = 2
ANN_WARNING = 3

REGS = {
    0x00: 'INIT_00', 0x01: 'ID', 0x02: 'POWER_CTL_1', 0x04: 'POWER_CTL_2', 0x05: 'CLOCKING_CTL',
    0x06: 'INTERFACE_CTL_1', 0x07: 'INTERFACE_CTL_2', 0x08: 'PASSTHRU_A_SEL', 0x09: 'PASSTHRU_B_SEL',
    0x0A: 'ANALOG_ZC_SR', 0x0C: 'PASSTHRU_GANG', 0x0D: 'PLAYBACK_CTL_1', 0x0E: 'MISC_CTL',
    0x0F: 'PLAYBACK_CTL_2', 0x14: 'PASSTHRU_A_VOL', 0x15: 'PASSTHRU_B_VOL', 0x1A: 'PCM_A_VOL',
    0x1B: 'PCM_B_VOL', 0x1C: 'BEEP_FREQ_ON', 0x1D: 'BEEP_VOL_OFF', 0x1E: 'BEEP_TONE_CFG',
    0x1F: 'TONE_CTL', 0x20: 'MASTER_A_VOL', 0x21: 'MASTER_B_VOL', 0x22: 'HP_A_VOL', 0x23: 'HP_B_VOL',
    0x24: 'SPK_A_VOL', 0x25: 'SPK_B_VOL', 0x26: 'PCM_MIXER_SWAP', 0x27: 'LIMIT_CTL_1',
    0x28: 'LIMIT_CTL_2', 0x29: 'LIMIT_ATTACK', 0x2E: 'STATUS', 0x2F: 'BATTERY_COMP',
    0x30: 'VP_LEVEL', 0x31: 'SPEAKER_STATUS', 0x32: 'INIT_32', 0x34: 'CHARGE_PUMP_FREQ',
    0x47: 'INIT_47',
}

HP_GAIN = (0.3959, 0.4571, 0.5111, 0.6047, 0.7099, 0.8399, 1.000, 1.1430)
PWR_MODE = ('on when SPKR/HP_SW low', 'on when SPKR/HP_SW high', 'always on', 'always off')
BEEP_FREQ = (260.87, 521.74, 585.37, 666.67, 705.88, 774.19, 888.89, 1000.00,
             1043.48, 1200.00, 1333.33, 1411.76, 1600.00, 1714.29, 2000.00, 2181.82)
SPEED = ('double', 'single', 'half', 'quarter')
RATIO = (128, 125, 132, 136)
FMT = ('left-justified', 'I2S', 'right-justified', 'reserved')
BEEP_MODE = ('off', 'single', 'multiple', 'continuous')
TREB_CF = (5, 7, 10, 15)
BASS_CF = (50, 100, 200, 250)
LIMIT_DB = (0, -3, -6, -9, -12, -18, -24, -30)
SWAP = ('L/R', '(L+R)/2', '(L+R)/2', 'swapped')


def _s8(v):
    return v - 256 if v > 127 else v


def _flags(v, names):
    return ' '.join(n for b, n in names if v & (1 << b))


def _warn_tag(msg):
    m = msg.lower()
    if 'reserved' in m:
        return 'RSVD'
    if 'power' in m:
        return 'PWR?'
    if 'beep' in m:
        return 'BEEP?'
    return 'WARN'


def _with_short(strings):
    """Keep >= 3 tiers (long, medium, short) so a narrow zoom still shows something."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings
    first = strings[0]
    words = first.split()
    for cand in (' '.join(words[:2]).rstrip(':,'), words[0].rstrip(':,') if words else '', first[:8]):
        if len(strings) >= 3:
            break
        if cand and cand not in strings and len(cand) < len(first):
            i = 0
            while i < len(strings) and len(strings[i]) > len(cand):
                i += 1
            strings.insert(i, cand)
    return strings


def _beep_db(code):
    s = code if code <= 6 else code - 32
    return 2 * s - 6


def _decode(reg, v):
    """Decoded field text for a register value, or None for a plain hex fallback."""
    if reg == 0x01:
        return 'ID 0x%02X: chip %s rev %d' % (v, 'CS43L22' if (v & 0xF8) == 0xE0 else 'unknown (expected 0xE0)', v & 7)
    if reg == 0x02:
        state = {0x01: 'powered down', 0x9F: 'powered down (sequence)', 0x9E: 'powered up'}.get(v, 'reserved value')
        return 'POWER_CTL_1 0x%02X: %s' % (v, state)
    if reg == 0x04:
        return 'POWER_CTL_2 0x%02X: HPB=%s HPA=%s SPKB=%s SPKA=%s' % (
            v, PWR_MODE[v >> 6], PWR_MODE[(v >> 4) & 3], PWR_MODE[(v >> 2) & 3], PWR_MODE[v & 3])
    if reg == 0x05:
        return 'CLOCKING_CTL 0x%02X: AUTO=%d speed=%s%s%s ratio=%d%s' % (
            v, v >> 7, SPEED[(v >> 5) & 3], ' 32kGROUP' if v & 0x10 else '', ' VIDEOCLK' if v & 0x08 else '',
            RATIO[(v >> 1) & 3], ' MCLK/2' if v & 1 else '')
    if reg == 0x06:
        return 'INTERFACE_CTL_1 0x%02X: %s%s%s %s AWL=%d' % (
            v, 'master' if v & 0x80 else 'slave', ' INV_SCLK' if v & 0x40 else '', ' DSP' if v & 0x10 else '',
            FMT[(v >> 2) & 3], v & 3)
    if reg == 0x07:
        return 'INTERFACE_CTL_2 0x%02X: %s' % (v, _flags(v, [(6, 'SCLK=MCLK'), (3, 'INV_SWCH')]) or 'defaults')
    if reg in (0x08, 0x09):
        ins = [str(i + 1) for i in range(4) if v & (1 << i)]
        return '%s 0x%02X: AIN%s%s' % (REGS[reg], v, '/'.join(ins) or 'none', 'A' if reg == 0x08 else 'B')
    if reg == 0x0A:
        return 'ANALOG_ZC_SR 0x%02X: %s' % (v, _flags(v, [(3, 'SFTB'), (2, 'ZCB'), (1, 'SFTA'), (0, 'ZCA')]) or 'none')
    if reg == 0x0C:
        return 'PASSTHRU_GANG 0x%02X: PASSB=A %s' % (v, 'on' if v & 0x80 else 'off')
    if reg == 0x0D:
        return 'PLAYBACK_CTL_1 0x%02X: HP gain G=%.4f %s' % (
            v, HP_GAIN[v >> 5], _flags(v, [(4, 'PLYBCKB=A'), (3, 'INV_PCMB'), (2, 'INV_PCMA'), (1, 'MSTBMUTE'),
                                           (0, 'MSTAMUTE')]) or 'no flags')
    if reg == 0x0E:
        return 'MISC_CTL 0x%02X: %s' % (
            v, _flags(v, [(7, 'PASSTHRUB'), (6, 'PASSTHRUA'), (5, 'PASSBMUTE'), (4, 'PASSAMUTE'), (3, 'FREEZE'),
                          (2, 'DEEMPH'), (1, 'DIGSFT'), (0, 'DIGZC')]) or 'none')
    if reg == 0x0F:
        return 'PLAYBACK_CTL_2 0x%02X: %s' % (
            v, _flags(v, [(7, 'HPBMUTE'), (6, 'HPAMUTE'), (5, 'SPKBMUTE'), (4, 'SPKAMUTE'), (3, 'SPKB=A'),
                          (2, 'SPKSWAP'), (1, 'SPKMONO'), (0, 'MUTE50/50')]) or 'none')
    if reg in (0x14, 0x15, 0x20, 0x21):
        return '%s 0x%02X: %+.1f dB' % (REGS[reg], v, _s8(v) / 2.0)
    if reg in (0x22, 0x23, 0x24, 0x25):
        return '%s 0x%02X: %s' % (REGS[reg], v, 'muted' if v == 0x01 else '%+.1f dB' % (_s8(v) / 2.0))
    if reg in (0x1A, 0x1B):
        s7 = (v & 0x7F) - 128 if v & 0x40 else v & 0x7F
        return '%s 0x%02X: %s%+.1f dB' % (REGS[reg], v, 'MUTE ' if v & 0x80 else '', s7 / 2.0)
    if reg == 0x1C:
        return 'BEEP_FREQ_ON 0x%02X: %.2f Hz on-time code %d' % (v, BEEP_FREQ[v >> 4], v & 0x0F)
    if reg == 0x1D:
        return 'BEEP_VOL_OFF 0x%02X: off-time code %d volume %+d dB' % (v, v >> 5, _beep_db(v & 0x1F))
    if reg == 0x1E:
        return 'BEEP_TONE_CFG 0x%02X: beep=%s%s treble corner %d kHz bass corner %d Hz tone %s' % (
            v, BEEP_MODE[v >> 6], ' mix off' if v & 0x20 else '', TREB_CF[(v >> 3) & 3], BASS_CF[(v >> 1) & 3],
            'on' if v & 1 else 'off')
    if reg == 0x1F:
        return 'TONE_CTL 0x%02X: treble %+.1f dB bass %+.1f dB' % (v, (8 - (v >> 4)) * 1.5, (8 - (v & 0x0F)) * 1.5)
    if reg == 0x26:
        return 'PCM_MIXER_SWAP 0x%02X: A=%s B=%s' % (v, SWAP[v >> 6], SWAP[(v >> 4) & 3])
    if reg == 0x27:
        return 'LIMIT_CTL_1 0x%02X: max %d dB cushion %d dB%s%s' % (
            v, LIMIT_DB[v >> 5], LIMIT_DB[(v >> 2) & 7], ' LIMSRDIS' if v & 2 else '', ' LIMZCDIS' if v & 1 else '')
    if reg == 0x28:
        return 'LIMIT_CTL_2 0x%02X: limiter %s%s release %d' % (
            v, 'on' if v & 0x80 else 'off', ' limit-all' if v & 0x40 else '', v & 0x3F)
    if reg == 0x29:
        return 'LIMIT_ATTACK 0x%02X: attack %d' % (v, v & 0x3F)
    if reg == 0x2E:
        return 'STATUS 0x%02X: %s' % (
            v, _flags(v, [(6, 'SPCLKERR'), (5, 'DSPAOVFL'), (4, 'DSPBOVFL'), (3, 'PCMAOVFL'), (2, 'PCMBOVFL')]) or 'ok')
    if reg == 0x2F:
        return 'BATTERY_COMP 0x%02X: %s%s VPREF=%d' % (
            v, 'BATTCMP ' if v & 0x80 else '', 'VPMONITOR' if v & 0x40 else '', v & 0x0F)
    if reg == 0x30:
        return 'VP_LEVEL 0x%02X: %.2f V (VA = 1.8 V) / %.2f V (VA = 2.5 V)' % (v, v * 1.8 / 63.3, v * 2.5 / 63.3)
    if reg == 0x31:
        return 'SPEAKER_STATUS 0x%02X: %s' % (
            v, _flags(v, [(5, 'SPKASHRT'), (4, 'SPKBSHRT'), (3, 'SPKR/HP=high')]) or 'ok')
    if reg == 0x34:
        return 'CHARGE_PUMP_FREQ 0x%02X: N=%d' % (v, v >> 4)
    return None


class Decoder(srd.Decoder):
    api_version = 3
    id = 'cs43l22'
    name = 'CS43L22'
    longname = 'Cirrus Logic CS43L22 stereo DAC'
    desc = 'Decode CS43L22 I2C control-port register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['cs43l22']
    tags = ['IC', 'Audio']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('init-block', 'Required initialization block'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_WRITE, ANN_READ, ANN_INIT)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.map_reg = None       # last memory address pointer
        self.incr = False
        self.databuf = []
        self.ss_block = None
        self.es = None
        self.init_step = 0        # progress through the required-initialization block
        self.init_ss = None
        self.init_done = False
        self.beep_mode = 0
        self.reg32 = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _put(self, ann, strings, ss=None, es=None):
        self.put(self.ss_block if ss is None else ss, self.es if es is None else es, self.out_ann,
                 [ann, _with_short(strings) if ann != ANN_WARNING else strings])

    def _warn(self, msg):
        self.put(self.ss_block, self.es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _mirror(self):
        if self.map_reg is None:
            return
        rw = 'READ' if self.is_read else 'WRITE'
        self.put(self.ss_block, self.es, self.out_python,
                 ('REG_' + rw, (self.map_reg, bytes(b & 0xFF for b in self.databuf))))

    def _track_init(self, reg, v):
        """Follow the required-initialization block; emit one group annotation when it completes."""
        steps = [(0x00, lambda x: x == 0x99), (0x47, lambda x: x == 0x80),
                 (0x32, lambda x: x & 0x80), (0x32, lambda x: not x & 0x80), (0x00, lambda x: x == 0x00)]
        if self.init_step < len(steps) and reg == steps[self.init_step][0] and steps[self.init_step][1](v):
            if self.init_step == 0:
                self.init_ss = self.ss_block
            self.init_step += 1
            if self.init_step == len(steps):
                self.init_step = 0
                self.init_done = True
                self._put(ANN_INIT, ['Required initialization block (datasheet 4.11) complete',
                                     'Required init', 'INIT'], ss=self.init_ss, es=self.es)
        else:
            self.init_step = 1 if (reg == 0x00 and v == 0x99) else 0
            if self.init_step == 1:
                self.init_ss = self.ss_block

    def _check(self, reg, v):
        if reg == 0x02:
            if v not in (0x01, 0x9E, 0x9F):
                self._warn('POWER_CTL_1 value 0x%02X is reserved (use 0x01, 0x9E or 0x9F)' % v)
            elif v == 0x9E and not self.init_done:
                self._warn('POWER_CTL_1 = 0x9E (power up) before the required initialization block')
        elif reg == 0x1E:
            self.beep_mode = v >> 6
        elif reg in (0x1C, 0x1D) and self.beep_mode != 0:
            self._warn('Beep timing register %s written while a beep mode is active' % REGS[reg])
        elif reg not in REGS:
            self._warn('Write to reserved register 0x%02X' % reg)
        self._track_init(reg, v)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_MAP') or self.map_reg is None:
            return
        reg = self.map_reg
        if self.is_read:
            if not self.databuf:
                return
            texts = []
            for i, v in enumerate(self.databuf):
                r = reg + (i if self.incr else 0)
                texts.append(_decode(r, v) or '%s 0x%02X' % (REGS.get(r, 'reg 0x%02X' % r), v))
            self._put(ANN_READ, ['Read ' + '; '.join(texts), 'R %s 0x%02X' % (REGS.get(reg, 'reg'), self.databuf[0])])
            if reg == 0x01 and (self.databuf[0] & 0xF8) != 0xE0:
                self._warn('Chip ID 0x%02X is not a CS43L22 (expected 0xE0 in bits 7:3)' % self.databuf[0])
            self._mirror()
            return
        if not self.databuf:
            self._put(ANN_WRITE, ['Pointer -> %s (0x%02X)%s' % (REGS.get(reg, 'reg 0x%02X' % reg), reg,
                                                              ' INCR' if self.incr else ''),
                                  'PTR 0x%02X' % reg])
            self._mirror()
            return
        texts = []
        for i, v in enumerate(self.databuf):
            r = reg + (i if self.incr else 0)
            texts.append(_decode(r, v) or '%s 0x%02X' % (REGS.get(r, 'reg 0x%02X' % r), v))
        self._put(ANN_WRITE, ['Write %s%s' % ('INCR ' if self.incr else '', '; '.join(texts)),
                              'W %s 0x%02X' % (REGS.get(reg, 'reg'), self.databuf[0]) + ('..' if len(self.databuf) > 1 else '')])
        for i, v in enumerate(self.databuf):
            self._check(reg + (i if self.incr else 0), v)
        self._mirror()

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.es = es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_MAP':
                pass
            else:
                self._finish_transaction()
                self.databuf = []
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state = 'GET_DATA_READ'
            else:
                self.state = 'GET_MAP'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_MAP':
                self.incr = bool(pdata & 0x80)
                self.map_reg = pdata & 0x7F
                self.databuf = []
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
