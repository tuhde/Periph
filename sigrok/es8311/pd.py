import sigrokdecode as srd

ADDRS = {0x18, 0x19}

REGS = {
    0x00: 'RESET', 0x01: 'CLK_MANAGER1', 0x02: 'CLK_MANAGER2', 0x03: 'CLK_MANAGER3',
    0x04: 'CLK_MANAGER4', 0x05: 'CLK_MANAGER5', 0x06: 'CLK_MANAGER6', 0x07: 'CLK_MANAGER7',
    0x08: 'CLK_MANAGER8', 0x09: 'SDP_IN', 0x0A: 'SDP_OUT', 0x0D: 'SYSTEM_PWR',
    0x0E: 'SYSTEM_ADC', 0x0F: 'SYSTEM_LP', 0x14: 'SYSTEM_MIC', 0x15: 'ADC_RAMP',
    0x16: 'ADC_SCALE', 0x17: 'ADC_VOLUME', 0x18: 'ADC_ALC1', 0x19: 'ADC_ALC2',
    0x1A: 'ADC_AUTOMUTE', 0x1B: 'ADC_HPF1', 0x1C: 'ADC_HPF2_EQ', 0x31: 'DAC_MUTE',
    0x32: 'DAC_VOLUME', 0x34: 'DAC_DRC1', 0x35: 'DAC_DRC2', 0x37: 'DAC_RAMP_EQ',
    0x44: 'GPIO', 0xFD: 'CHIP_ID1', 0xFE: 'CHIP_ID2', 0xFF: 'CHIP_VERSION',
}
# Registers the user guide does not document; only the vendor driver values are written.
VENDOR = {0x0B: 'SYSTEM_0B', 0x0C: 'SYSTEM_0C', 0x10: 'SYSTEM_10', 0x11: 'SYSTEM_11',
          0x12: 'SYSTEM_DAC', 0x13: 'SYSTEM_HP'}

# Equalizer coefficient registers: (first register, name), four bytes each.
EQ_ADC = [(0x1D, 'ADCEQ_B0'), (0x21, 'ADCEQ_A1'), (0x25, 'ADCEQ_A2'), (0x29, 'ADCEQ_B1'), (0x2D, 'ADCEQ_B2')]
EQ_DAC = [(0x38, 'DACEQ_B0'), (0x3C, 'DACEQ_B1'), (0x40, 'DACEQ_A1')]

WORD_LENGTH = {0: 24, 1: 20, 2: 18, 3: 16, 4: 32}
SDP_FORMAT = {0: 'I2S', 1: 'left-justified', 2: 'reserved', 3: 'DSP/PCM'}
ADCDAT_SEL = {0: 'ADC+ADC', 1: 'ADC+0', 2: '0+ADC', 4: 'DACL+ADC', 5: 'ADC+DACR', 6: 'DACL+DACR'}
ALC_LEVELS = [-30.1, -24.1, -20.6, -18.1, -16.1, -14.5, -13.2, -12.0,
              -11.0, -10.1, -9.3, -8.5, -7.8, -7.2, -6.6, -6.0]
NOISE_GATE = [-96, -90, -84, -78, -72, -66, -60, -54, -51, -48, -45, -42, -39, -36, -33, -30]
BCLK_DIV = {20: 22, 21: 24, 22: 25, 23: 30, 24: 32, 25: 33, 26: 34, 27: 36, 28: 44, 29: 48, 30: 66, 31: 72}
VMID = {0: 'off', 1: 'start-up (normal)', 2: 'normal', 3: 'start-up (fast)'}

ANN_WRITE = 0
ANN_READ = 1
ANN_WARNING = 2


def _name(reg):
    if reg in REGS:
        return REGS[reg]
    if reg in VENDOR:
        return VENDOR[reg]
    for base, name in EQ_ADC + EQ_DAC:
        if base <= reg < base + 4:
            return '%s[%d]' % (name, reg - base)
    return 'reg 0x%02X' % reg


def _flags(raw, names):
    """names: list of (bit, name); returns the set flags joined by spaces."""
    return ' '.join(n for b, n in names if raw & (1 << b))


def _volume_db(raw):
    return (raw - 191) * 0.5


def _decode(reg, v):
    """Decoded field text for a register value, or None for a plain hex fallback."""
    if reg == 0x00:
        return 'RESET 0x%02X: CSM_ON=%d %s %s' % (
            v, v >> 7 & 1, 'master' if v & 0x40 else 'slave',
            _flags(v, [(4, 'RST_DIG'), (3, 'RST_CMG'), (2, 'RST_MST'), (1, 'RST_ADC_DIG'), (0, 'RST_DAC_DIG')]) or 'no resets')
    if reg == 0x01:
        return 'CLK_MANAGER1 0x%02X: MCLK_SEL=%s %s' % (
            v, 'SCLK' if v & 0x80 else 'MCLK pin',
            _flags(v, [(6, 'MCLK_INV'), (5, 'MCLK_ON'), (4, 'BCLK_ON'), (3, 'CLKADC_ON'), (2, 'CLKDAC_ON')]) or 'all clocks off')
    if reg == 0x02:
        return 'CLK_MANAGER2 0x%02X: MCLK /%d x%d' % (v, (v >> 5) + 1, (1, 2, 4, 8)[(v >> 3) & 3])
    if reg == 0x03:
        osr = v & 0x3F
        speed = 'double' if v & 0x40 else 'single'
        return 'CLK_MANAGER3 0x%02X: ADC %s speed OSR code %d (%s)' % (
            v, speed, osr, ('%dxfs' % (osr * (2 if v & 0x40 else 4))) if osr >= 15 else 'not allowed')
    if reg == 0x04:
        return 'CLK_MANAGER4 0x%02X: DAC OSR code %d' % (v, v & 0x3F)
    if reg == 0x05:
        return 'CLK_MANAGER5 0x%02X: ADC clk /%d DAC clk /%d' % (v, (v >> 4) + 1, (v & 0x0F) + 1)
    if reg == 0x06:
        d = v & 0x1F
        return 'CLK_MANAGER6 0x%02X: BCLK /%d %s%s' % (
            v, BCLK_DIV.get(d, d + 1), 'stopped-when-idle ' if v & 0x40 else '', 'inverted' if v & 0x20 else '')
    if reg == 0x07:
        return 'CLK_MANAGER7 0x%02X: tri-state BCLK/LRCK=%d ADCDAT=%d LRCK[11:8]=%d' % (
            v, v >> 5 & 1, v >> 4 & 1, v & 0x0F)
    if reg == 0x08:
        return 'CLK_MANAGER8 0x%02X: LRCK[7:0]=%d' % (v, v)
    if reg in (0x09, 0x0A):
        fmt = v & 3
        wl = WORD_LENGTH.get((v >> 2) & 7, '?')
        extra = (' DAC=%s' % ('right' if v & 0x80 else 'left')) if reg == 0x09 else ''
        return '%s 0x%02X:%s %s%s %s-bit %s' % (
            REGS[reg], v, extra, 'MUTE ' if v & 0x40 else '', 'LRP' if v & 0x20 else 'LRP=0', wl, SDP_FORMAT[fmt])
    if reg == 0x0D:
        return 'SYSTEM_PWR 0x%02X: %s VMID=%s' % (
            v, _flags(v, [(7, 'PDN_ANA'), (6, 'PDN_IBIASGEN'), (5, 'PDN_ADCBIASGEN'), (4, 'PDN_ADCVREFGEN'),
                          (3, 'PDN_DACVREFGEN'), (2, 'PDN_VREF')]) or 'all powered', VMID[v & 3])
    if reg == 0x0E:
        return 'SYSTEM_ADC 0x%02X: %s' % (v, _flags(v, [(6, 'PDN_PGA'), (5, 'PDN_MOD'), (4, 'RST_MOD')]) or 'PGA+MOD on')
    if reg == 0x0F:
        return 'SYSTEM_LP 0x%02X: %s' % (v, _flags(v, [(7, 'LPDAC'), (6, 'LPPGA'), (5, 'LPPGAOUT'), (4, 'LPVCMMOD'),
                                                        (3, 'LPADCVRP'), (2, 'LPDACVRP'), (1, 'LPFLASH'), (0, 'LPINT1')]) or 'normal power')
    if reg == 0x14:
        sel = ('none', 'MIC1P-MIC1N', 'MIC2P-MIC2N', 'both')[(v >> 4) & 3]
        gain = v & 0x0F
        return 'SYSTEM_MIC 0x%02X: %s input=%s PGA=%s' % (
            v, 'DMIC' if v & 0x40 else 'analog', sel, ('%d dB' % (gain * 3)) if gain <= 10 else 'invalid')
    if reg == 0x15:
        rate = v >> 4
        return 'ADC_RAMP 0x%02X: ramp=%s DMIC latch=%s' % (
            v, 'off' if rate == 0 else '0.25dB/%d LRCK' % (1 << (rate + 1)), 'falling' if v & 1 else 'rising')
    if reg == 0x16:
        return 'ADC_SCALE 0x%02X: scale +%d dB%s' % (v, 6 * (v & 7), ' RAM_CLR' if v & 0x08 else '')
    if reg == 0x17:
        return 'ADC_VOLUME 0x%02X: %+.1f dB' % (v, _volume_db(v))
    if reg == 0x18:
        return 'ADC_ALC1 0x%02X: ALC=%s automute=%s window=%d' % (v, 'on' if v & 0x80 else 'off', 'on' if v & 0x40 else 'off', v & 0x0F)
    if reg == 0x19:
        return 'ADC_ALC2 0x%02X: max=%.1f dB min=%.1f dB' % (v, ALC_LEVELS[v >> 4], ALC_LEVELS[v & 0x0F])
    if reg == 0x1A:
        return 'ADC_AUTOMUTE 0x%02X: window=%d samples noise gate=%d dB' % (v, 2048 * ((v >> 4) + 1), NOISE_GATE[v & 0x0F])
    if reg == 0x1B:
        return 'ADC_HPF1 0x%02X: stage-1 coeff=%d' % (v, v & 0x1F)
    if reg == 0x1C:
        return 'ADC_HPF2_EQ 0x%02X: EQ %s HPF %s stage-2 coeff=%d' % (
            v, 'bypassed' if v & 0x40 else 'enabled', 'dynamic' if v & 0x20 else 'frozen', v & 0x1F)
    if reg == 0x31:
        return 'DAC_MUTE 0x%02X: %s' % (v, _flags(v, [(7, 'DSM_TARGET'), (6, 'DSM_MUTE'), (5, 'DEM_MUTE')]) or 'unmuted')
    if reg == 0x32:
        return 'DAC_VOLUME 0x%02X: %+.1f dB' % (v, _volume_db(v))
    if reg == 0x34:
        return 'DAC_DRC1 0x%02X: DRC=%s window=%d' % (v, 'on' if v & 0x80 else 'off', v & 0x0F)
    if reg == 0x35:
        return 'DAC_DRC2 0x%02X: max=%.1f dB min=%.1f dB' % (v, ALC_LEVELS[v >> 4], ALC_LEVELS[v & 0x0F])
    if reg == 0x37:
        rate = v >> 4
        return 'DAC_RAMP_EQ 0x%02X: ramp=%s EQ %s' % (
            v, 'off' if rate == 0 else '0.25dB/%d LRCK' % (1 << (rate + 1)), 'bypassed' if v & 0x08 else 'enabled')
    if reg == 0x44:
        return 'GPIO 0x%02X: loopback=%s ADCDAT=%s' % (v, 'on' if v & 0x80 else 'off', ADCDAT_SEL.get((v >> 4) & 7, 'reserved'))
    return None


def _warn_tag(msg):
    m = msg.lower()
    if 'clocks off' in m:
        return 'CLK?'
    if any(k in m for k in ('length', 'byte')):
        return 'LEN?'
    if any(k in m for k in ('not allowed', 'invalid', 'unexpected')):
        return 'BAD?'
    return 'WARN'


def _with_short(strings):
    """Keep >= 3 tiers (long, medium, short) for data annotations so a narrow zoom still shows something."""
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


class Decoder(srd.Decoder):
    api_version = 3
    id = 'es8311'
    name = 'ES8311'
    longname = 'Everest ES8311 low-power mono audio codec'
    desc = 'Decode ES8311 I2C control-port register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['es8311']
    tags = ['IC', 'Audio']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_WRITE, ANN_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.databuf = []
        self.ss_block = None
        self.last_clk1 = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _put(self, ann, strings):
        self.put(self.ss_block, self.es, self.out_ann, [ann, _with_short(strings) if ann != ANN_WARNING else strings])

    def _warn(self, msg):
        self.put(self.ss_block, self.es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _mirror(self):
        rw = 'READ' if self.is_read else 'WRITE'
        if self.reg_ptr is not None:
            self.put(self.ss_block, self.es, self.out_python,
                     ('REG_' + rw, (self.reg_ptr, bytes(b & 0xFF for b in self.databuf))))

    def _eq_text(self, reg, data):
        """Assemble 30-bit equalizer coefficients when the burst covers whole 4-byte groups."""
        for table in (EQ_ADC, EQ_DAC):
            lo = table[0][0]
            hi = table[-1][0] + 4
            if lo <= reg < hi and len(data) >= 4:
                parts = []
                i = 0
                while i + 4 <= len(data):
                    base = reg + i
                    group = [(b, n) for b, n in table if b == base]
                    if not group:
                        return None
                    value = ((data[i] & 0x3F) << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | data[i + 3]
                    parts.append('%s=0x%08X' % (group[0][1], value))
                    i += 4
                if i == len(data):
                    return 'EQ ' + ', '.join(parts)
        return None

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        if reg is None and not self.is_read:
            return
        name = _name(reg) if reg is not None else '?'
        if self.is_read:
            if not self.databuf:
                return
            if reg in (0xFD, 0xFE) and len(self.databuf) == 1:
                expect = 0x83 if reg == 0xFD else 0x11
                ok = 'ES8311' if self.databuf[0] == expect else 'expected 0x%02X' % expect
                self._put(ANN_READ, ['%s 0x%02X (%s)' % (name, self.databuf[0], ok), 'ID 0x%02X' % self.databuf[0]])
            elif len(self.databuf) == 1:
                text = _decode(reg, self.databuf[0])
                self._put(ANN_READ, ['Read ' + (text or '%s: 0x%02X' % (name, self.databuf[0])),
                                     'R %s 0x%02X' % (name, self.databuf[0])])
            else:
                eq = self._eq_text(reg, self.databuf)
                self._put(ANN_READ, ['Read ' + (eq or '%s: %d bytes' % (name, len(self.databuf))),
                                     'R %s %dB' % (name, len(self.databuf))])
            self._mirror()
            return
        if not self.databuf:
            self._put(ANN_WRITE, ['Pointer → %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg])
            self._mirror()
            return
        eq = self._eq_text(reg, self.databuf) if len(self.databuf) > 1 else None
        if eq:
            self._put(ANN_WRITE, ['Write ' + eq, 'W EQ %dB' % len(self.databuf)])
        else:
            texts = []
            for i, v in enumerate(self.databuf):
                r = reg + i
                texts.append(_decode(r, v) or '%s 0x%02X' % (_name(r), v))
            self._put(ANN_WRITE, ['Write ' + '; '.join(texts),
                                  'W %s 0x%02X' % (name, self.databuf[0]) + ('..' if len(self.databuf) > 1 else '')])
            for i, v in enumerate(self.databuf):
                self._check(reg + i, v)
        self._mirror()

    def _check(self, reg, v):
        if reg == 0x01:
            self.last_clk1 = v
        elif reg == 0x00 and v & 0x80 and self.last_clk1 is not None and (self.last_clk1 & 0x3C) == 0:
            self._warn('RESET sets CSM_ON but CLK_MANAGER1 was written with all clocks off')
        elif reg == 0x03 and (v & 0x3F) < 15:
            self._warn('CLK_MANAGER3 ADC OSR code %d not allowed (0-14)' % (v & 0x3F))

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
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
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = pdata
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
