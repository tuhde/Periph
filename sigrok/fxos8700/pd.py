import sigrokdecode as srd

# SA1 / SA0 select 0x1C-0x1F.
ADDRS = {0x1C, 0x1D, 0x1E, 0x1F}
WHO_AM_I_VALUE = 0xC7

# --- Register map (0x00-0x78) -------------------------------------------------
REGS = {
    0x00: 'STATUS', 0x01: 'OUT_X_MSB', 0x02: 'OUT_X_LSB', 0x03: 'OUT_Y_MSB',
    0x04: 'OUT_Y_LSB', 0x05: 'OUT_Z_MSB', 0x06: 'OUT_Z_LSB', 0x09: 'F_SETUP',
    0x0A: 'TRIG_CFG', 0x0B: 'SYSMOD', 0x0C: 'INT_SOURCE', 0x0D: 'WHO_AM_I',
    0x0E: 'XYZ_DATA_CFG', 0x0F: 'HP_FILTER_CUTOFF', 0x10: 'PL_STATUS',
    0x11: 'PL_CFG', 0x12: 'PL_COUNT', 0x13: 'PL_BF_ZCOMP', 0x14: 'PL_THS_REG',
    0x15: 'A_FFMT_CFG', 0x16: 'A_FFMT_SRC', 0x17: 'A_FFMT_THS',
    0x18: 'A_FFMT_COUNT', 0x1D: 'TRANSIENT_CFG', 0x1E: 'TRANSIENT_SRC',
    0x1F: 'TRANSIENT_THS', 0x20: 'TRANSIENT_COUNT', 0x21: 'PULSE_CFG',
    0x22: 'PULSE_SRC', 0x23: 'PULSE_THSX', 0x24: 'PULSE_THSY',
    0x25: 'PULSE_THSZ', 0x26: 'PULSE_TMLT', 0x27: 'PULSE_LTCY',
    0x28: 'PULSE_WIND', 0x29: 'ASLP_COUNT', 0x2A: 'CTRL_REG1',
    0x2B: 'CTRL_REG2', 0x2C: 'CTRL_REG3', 0x2D: 'CTRL_REG4',
    0x2E: 'CTRL_REG5', 0x2F: 'OFF_X', 0x30: 'OFF_Y', 0x31: 'OFF_Z',
    0x32: 'M_DR_STATUS', 0x33: 'M_OUT_X_MSB', 0x34: 'M_OUT_X_LSB',
    0x35: 'M_OUT_Y_MSB', 0x36: 'M_OUT_Y_LSB', 0x37: 'M_OUT_Z_MSB',
    0x38: 'M_OUT_Z_LSB', 0x39: 'CMP_X_MSB', 0x3A: 'CMP_X_LSB',
    0x3B: 'CMP_Y_MSB', 0x3C: 'CMP_Y_LSB', 0x3D: 'CMP_Z_MSB', 0x3E: 'CMP_Z_LSB',
    0x3F: 'M_OFF_X_MSB', 0x40: 'M_OFF_X_LSB', 0x41: 'M_OFF_Y_MSB',
    0x42: 'M_OFF_Y_LSB', 0x43: 'M_OFF_Z_MSB', 0x44: 'M_OFF_Z_LSB',
    0x45: 'MAX_X_MSB', 0x46: 'MAX_X_LSB', 0x47: 'MAX_Y_MSB', 0x48: 'MAX_Y_LSB',
    0x49: 'MAX_Z_MSB', 0x4A: 'MAX_Z_LSB', 0x4B: 'MIN_X_MSB', 0x4C: 'MIN_X_LSB',
    0x4D: 'MIN_Y_MSB', 0x4E: 'MIN_Y_LSB', 0x4F: 'MIN_Z_MSB', 0x50: 'MIN_Z_LSB',
    0x51: 'TEMP', 0x52: 'M_THS_CFG', 0x53: 'M_THS_SRC', 0x54: 'M_THS_X_MSB',
    0x55: 'M_THS_X_LSB', 0x56: 'M_THS_Y_MSB', 0x57: 'M_THS_Y_LSB',
    0x58: 'M_THS_Z_MSB', 0x59: 'M_THS_Z_LSB', 0x5A: 'M_THS_COUNT',
    0x5B: 'M_CTRL_REG1', 0x5C: 'M_CTRL_REG2', 0x5D: 'M_CTRL_REG3',
    0x5E: 'M_INT_SRC', 0x5F: 'A_VECM_CFG', 0x60: 'A_VECM_THS_MSB',
    0x61: 'A_VECM_THS_LSB', 0x62: 'A_VECM_CNT', 0x63: 'A_VECM_INITX_MSB',
    0x64: 'A_VECM_INITX_LSB', 0x65: 'A_VECM_INITY_MSB',
    0x66: 'A_VECM_INITY_LSB', 0x67: 'A_VECM_INITZ_MSB',
    0x68: 'A_VECM_INITZ_LSB', 0x69: 'M_VECM_CFG', 0x6A: 'M_VECM_THS_MSB',
    0x6B: 'M_VECM_THS_LSB', 0x6C: 'M_VECM_CNT', 0x6D: 'M_VECM_INITX_MSB',
    0x6E: 'M_VECM_INITX_LSB', 0x6F: 'M_VECM_INITY_MSB',
    0x70: 'M_VECM_INITY_LSB', 0x71: 'M_VECM_INITZ_MSB',
    0x72: 'M_VECM_INITZ_LSB', 0x73: 'A_FFMT_THS_X_MSB',
    0x74: 'A_FFMT_THS_X_LSB', 0x75: 'A_FFMT_THS_Y_MSB',
    0x76: 'A_FFMT_THS_Y_LSB', 0x77: 'A_FFMT_THS_Z_MSB',
    0x78: 'A_FFMT_THS_Z_LSB',
}

DR = {0: '800 Hz', 1: '400 Hz', 2: '200 Hz', 3: '100 Hz', 4: '50 Hz',
      5: '12.5 Hz', 6: '6.25 Hz', 7: '1.5625 Hz'}
ASLP_RATE = {0: '50 Hz', 1: '12.5 Hz', 2: '6.25 Hz', 3: '1.56 Hz'}
FS = {0: '±2 g', 1: '±4 g', 2: '±8 g', 3: 'reserved'}
COUNTS_PER_G = {0: 4096.0, 1: 2048.0, 2: 1024.0, 3: 4096.0}
MODS = {0: 'normal', 1: 'low-noise low-power', 2: 'high resolution', 3: 'low power'}
F_MODE = {0: 'disabled', 1: 'circular', 2: 'fill', 3: 'trigger'}
HMS = {0: 'accel only', 1: 'mag only', 2: 'reserved', 3: 'hybrid'}
SYSMOD = {0: 'STANDBY', 1: 'WAKE', 2: 'SLEEP', 3: 'reserved'}
LAPO = {0: 'portrait up', 1: 'portrait down', 2: 'landscape right', 3: 'landscape left'}
MAG_UT_PER_LSB = 0.1

ANN_REG_WRITE = 0
ANN_REG_READ = 1
ANN_PTR_WRITE = 2
ANN_WARNING = 3
# Conformance annotation pair (see specs/accelerometer/fxos8700.md, Sigrok
# Decoder + Timing Constraints, and specs/accelerometer/fxos8700_timing.conf).
ANN_ACTIVE_START = 4
ANN_ACTIVE_READY = 5


def _flags(raw, names):
    """Names of the set bits; ``names`` maps bit position -> name."""
    return [n for bit, n in sorted(names.items(), reverse=True) if raw & (1 << bit)]


def _fmt(name, raw, parts):
    parts = [p for p in parts if p]
    return '%s 0x%02X%s' % (name, raw, (', ' + ', '.join(parts)) if parts else '')


def _bracket(names):
    return ('[' + ' '.join(names) + ']') if names else ''


DATA_STATUS = {7: 'ZYXOW', 6: 'ZOW', 5: 'YOW', 4: 'XOW', 3: 'ZYXDR', 2: 'ZDR', 1: 'YDR', 0: 'XDR'}
INT_BITS = {7: 'ASLP', 6: 'FIFO', 5: 'TRANS', 4: 'LNDPRT', 3: 'PULSE', 2: 'FFMT', 1: 'A_VECM', 0: 'DRDY'}


def _decode_status(raw, fifo_on):
    if fifo_on:
        return _fmt('F_STATUS', raw, [('F_OVF' if raw & 0x80 else ''),
                                      ('F_WMRK_FLAG' if raw & 0x40 else ''),
                                      'F_CNT=%d' % (raw & 0x3F)])
    return _fmt('STATUS', raw, [_bracket(_flags(raw, DATA_STATUS))])


def _decode_reg(reg, raw, state):
    """Return the decoded text for one register value."""
    if reg == 0x00:
        return _decode_status(raw, state.get('f_mode', 0) != 0)
    if reg == 0x09:
        return _fmt('F_SETUP', raw, ['f_mode=%s' % F_MODE[(raw >> 6) & 3], 'f_wmrk=%d' % (raw & 0x3F)])
    if reg == 0x0A:
        return _fmt('TRIG_CFG', raw, [_bracket(_flags(raw, {5: 'TRANS', 4: 'LNDPRT', 3: 'PULSE', 2: 'FF_MT'}))])
    if reg == 0x0B:
        return _fmt('SYSMOD', raw, ['sysmod=%s' % SYSMOD[raw & 3], ('FGERR' if raw & 0x80 else ''),
                                    'fgt=%d' % ((raw >> 2) & 0x1F)])
    if reg == 0x0C:
        return _fmt('INT_SOURCE', raw, [_bracket(_flags(raw, INT_BITS))])
    if reg == 0x0D:
        return 'WHO_AM_I 0x%02X%s' % (raw, ' (FXOS8700CQ ✓)' if raw == WHO_AM_I_VALUE else
                                      ' (pre-production)' if raw == 0xC4 else ' (expected 0xC7!)')
    if reg == 0x0E:
        return _fmt('XYZ_DATA_CFG', raw, ['hpf_out=%d' % ((raw >> 4) & 1), 'fs=%s' % FS[raw & 3]])
    if reg == 0x0F:
        return _fmt('HP_FILTER_CUTOFF', raw, ['pulse_hpf_byp=%d' % ((raw >> 5) & 1),
                                              'pulse_lpf_en=%d' % ((raw >> 4) & 1), 'sel=%d' % (raw & 3)])
    if reg == 0x10:
        return _fmt('PL_STATUS', raw, [('NEWLP' if raw & 0x80 else ''), ('LO' if raw & 0x40 else ''),
                                       'lapo=%s' % LAPO[(raw >> 1) & 3], 'bafro=%s' % ('back' if raw & 1 else 'front')])
    if reg == 0x11:
        return _fmt('PL_CFG', raw, ['dbcntm=%d' % ((raw >> 7) & 1), 'pl_en=%d' % ((raw >> 6) & 1)])
    if reg == 0x13:
        return _fmt('PL_BF_ZCOMP', raw, ['bkfr=%d' % ((raw >> 6) & 3), 'zlock=%d' % (raw & 7)])
    if reg == 0x14:
        return _fmt('PL_THS_REG', raw, ['pl_ths=%d' % (raw >> 3), 'hys=%d' % (raw & 7)])
    if reg == 0x15:
        return _fmt('A_FFMT_CFG', raw, ['ELE' if raw & 0x80 else '', 'motion (OAE)' if raw & 0x40 else 'freefall',
                                        _bracket(_flags(raw, {5: 'ZEFE', 4: 'YEFE', 3: 'XEFE'}))])
    if reg in (0x16, 0x1E):
        names = {7: 'EA', 5: 'ZHE', 4: 'ZHP', 3: 'YHE', 2: 'YHP', 1: 'XHE', 0: 'XHP'}
        if reg == 0x1E:
            names = {6: 'EA', 5: 'ZTRANSE', 4: 'Z_POL', 3: 'YTRANSE', 2: 'Y_POL', 1: 'XTRANSE', 0: 'X_POL'}
        return _fmt(REGS[reg], raw, [_bracket(_flags(raw, names))])
    if reg in (0x17, 0x1F):
        return _fmt(REGS[reg], raw, ['dbcntm=%d' % ((raw >> 7) & 1), 'ths=%d (%.3f g)' % (raw & 0x7F, (raw & 0x7F) * 0.063)])
    if reg == 0x1D:
        return _fmt('TRANSIENT_CFG', raw, ['ELE' if raw & 0x10 else '', 'hpf_byp=%d' % (raw & 1),
                                           _bracket(_flags(raw, {3: 'ZTEFE', 2: 'YTEFE', 1: 'XTEFE'}))])
    if reg == 0x21:
        return _fmt('PULSE_CFG', raw, ['DPA' if raw & 0x80 else '', 'ELE' if raw & 0x40 else '',
                                       _bracket(_flags(raw, {5: 'ZDPEFE', 4: 'ZSPEFE', 3: 'YDPEFE', 2: 'YSPEFE',
                                                             1: 'XDPEFE', 0: 'XSPEFE'}))])
    if reg == 0x22:
        return _fmt('PULSE_SRC', raw, [_bracket(_flags(raw, {7: 'EA', 6: 'AxZ', 5: 'AxY', 4: 'AxX', 3: 'DPE',
                                                             2: 'PolZ', 1: 'PolY', 0: 'PolX'}))])
    if reg == 0x2A:
        return _fmt('CTRL_REG1', raw, ['aslp_rate=%s' % ASLP_RATE[raw >> 6], 'dr=%s' % DR[(raw >> 3) & 7],
                                       'lnoise=%d' % ((raw >> 2) & 1), 'f_read=%d' % ((raw >> 1) & 1),
                                       'ACTIVE' if raw & 1 else 'STANDBY'])
    if reg == 0x2B:
        return _fmt('CTRL_REG2', raw, ['ST' if raw & 0x80 else '', 'RST' if raw & 0x40 else '',
                                       'smods=%s' % MODS[(raw >> 3) & 3], 'SLPE' if raw & 4 else '',
                                       'mods=%s' % MODS[raw & 3]])
    if reg == 0x2C:
        return _fmt('CTRL_REG3', raw, [_bracket(_flags(raw, {7: 'FIFO_GATE', 6: 'WAKE_TRANS', 5: 'WAKE_LNDPRT',
                                                            4: 'WAKE_PULSE', 3: 'WAKE_FFMT', 2: 'WAKE_A_VECM'})),
                                       'ipol=%s' % ('active-high' if raw & 2 else 'active-low'),
                                       'pp_od=%s' % ('open-drain' if raw & 1 else 'push-pull')])
    if reg == 0x2D:
        return _fmt('CTRL_REG4 (int_en)', raw, [_bracket(_flags(raw, INT_BITS))])
    if reg == 0x2E:
        return _fmt('CTRL_REG5 (1=INT1)', raw, [_bracket(_flags(raw, INT_BITS))])
    if reg == 0x32:
        return _fmt('M_DR_STATUS', raw, [_bracket(_flags(raw, DATA_STATUS))])
    if reg == 0x5B:
        return _fmt('M_CTRL_REG1', raw, ['m_acal=%d' % ((raw >> 7) & 1), 'm_rst=%d' % ((raw >> 6) & 1),
                                         'm_ost=%d' % ((raw >> 5) & 1), 'm_os=%d' % ((raw >> 2) & 7),
                                         'm_hms=%s' % HMS[raw & 3]])
    if reg == 0x5C:
        return _fmt('M_CTRL_REG2', raw, [_bracket(_flags(raw, {5: 'hyb_autoinc_mode', 4: 'm_maxmin_dis',
                                                               3: 'm_maxmin_dis_ths', 2: 'm_maxmin_rst'})),
                                         'm_rst_cnt=%d' % (raw & 3)])
    if reg == 0x5D:
        return _fmt('M_CTRL_REG3', raw, ['m_raw=%d' % ((raw >> 7) & 1), 'm_aslp_os=%d' % ((raw >> 4) & 7),
                                         'm_ths_xyz_update=%d' % ((raw >> 3) & 1)])
    if reg == 0x5E:
        return _fmt('M_INT_SRC', raw, [_bracket(_flags(raw, {2: 'src_m_ths', 1: 'src_m_vecm', 0: 'src_m_drdy'}))])
    if reg == 0x51:
        v = raw if raw < 0x80 else raw - 0x100
        return 'TEMP 0x%02X (%+.2f °C relative)' % (raw, v * 0.96)
    return 'Reg[0x%02X] 0x%02X' % (reg, raw)


def _int16_be(buf, offset):
    """Sign-extend a big-endian 16-bit pair."""
    v = ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF)
    return v if v < 0x8000 else v - 0x10000


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'who_am_i' in m or 'identity' in m or 'device id' in m:
        return 'ID?'
    if 'address' in m:
        return 'ADDR?'
    return 'WARN'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); if a
    call site supplied fewer, derive the missing tier from the existing text."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings

    def lead(text):
        text = text.strip()
        n = 0
        while n < len(text) and (text[n].isalnum() or text[n] in '_→'):
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
    id = 'fxos8700'
    name = 'FXOS8700'
    longname = 'NXP FXOS8700CQ 6-axis e-compass (accelerometer + magnetometer)'
    desc = 'Decode FXOS8700CQ I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['fxos8700']
    tags = ['IC', 'Sensor', 'Accelerometer', 'Magnetometer']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('ptr-write', 'Register pointer write'),
        ('warning', 'Warning'),
        ('active-start', 'active_start: CTRL_REG1 write setting active = 1'),
        ('active-ready', 'active_ready: first STATUS read with zyxdr = 1'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE)),
        ('timing', 'Timing', (ANN_ACTIVE_START, ANN_ACTIVE_READY)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
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
                self._mirror_python(ss, es)
        super().put(ss, es, out, data)

    def _mirror_python(self, ss, es):
        """OUTPUT_PYTHON mirror of each transaction-level data annotation:
        ('REG_READ' | 'REG_WRITE', (register, bytes)), or
        ('I2C_READ' | 'I2C_WRITE', (address, bytes)) for chips without a register pointer."""
        out_py = self.__dict__.get('out_python')
        if out_py is None or self.__dict__.get('_py_span') == (ss, es):
            return
        self._py_span = (ss, es)
        try:
            buf = bytes(b & 0xFF for b in getattr(self, 'databuf', None) or ())
        except TypeError:
            return
        rw = 'READ' if getattr(self, 'is_read', False) else 'WRITE'
        reg = getattr(self, 'reg_ptr', None)
        if reg is None:
            reg = getattr(self, 'reg_byte', None)
        if reg is not None:
            super().put(ss, es, out_py, ('REG_' + rw, (reg, buf)))
        else:
            super().put(ss, es, out_py, ('I2C_' + rw, (getattr(self, 'addr', None), buf)))

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.databuf = []
        self.ss_block = None
        # Last-seen configuration, used to convert the output registers.
        self.cfg = {'fs': 0, 'f_read': 0, 'f_mode': 0}
        # Set when CTRL_REG1 was written with active = 1, so the first STATUS
        # read with zyxdr = 1 can be annotated as active_ready.
        self.active_seen = False
        self._active_ready_emitted = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _reg_name(self, reg):
        return REGS.get(reg, 'Reg[0x%02X]' % reg)

    def _accel_values(self, buf, offset):
        """Convert 14-bit left-justified (or 8-bit) accelerometer bytes to g."""
        cpg = COUNTS_PER_G[self.cfg['fs']]
        if self.cfg['f_read']:
            raw = [(buf[offset + i] if buf[offset + i] < 0x80 else buf[offset + i] - 0x100) for i in range(3)]
            return raw, [v / (cpg / 64.0) for v in raw]
        raw = [_int16_be(buf, offset + 2 * i) >> 2 for i in range(3)]
        return raw, [v / cpg for v in raw]

    def _annotate_accel(self, buf, offset, label):
        raw, g = self._accel_values(buf, offset)
        text = ('Read %s: x=%+.4f g, y=%+.4f g, z=%+.4f g (raw %d/%d/%d, FS=%s%s)'
                % (label, g[0], g[1], g[2], raw[0], raw[1], raw[2], FS[self.cfg['fs']],
                   ', 8-bit' if self.cfg['f_read'] else ''))
        return [text, 'R ACC %+.3f %+.3f %+.3f g' % tuple(g), 'ACC']

    def _annotate_mag(self, buf, offset, label):
        raw = [_int16_be(buf, offset + 2 * i) for i in range(3)]
        ut = [v * MAG_UT_PER_LSB for v in raw]
        text = ('Read %s: x=%+.1f uT, y=%+.1f uT, z=%+.1f uT (raw %d/%d/%d)'
                % (label, ut[0], ut[1], ut[2], raw[0], raw[1], raw[2]))
        return [text, 'R MAG %+.1f %+.1f %+.1f uT' % tuple(ut), 'MAG']

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        name = self._reg_name(reg) if reg is not None else '?'
        if self.is_read:
            if not self.databuf or reg is None:
                return
            n = len(self.databuf)
            if reg == 0x01 and n == 12:
                a = self._annotate_accel(self.databuf, 0, 'OUT_X_MSB..OUT_Z_LSB')
                m = self._annotate_mag(self.databuf, 6, 'M_OUT_X_MSB..M_OUT_Z_LSB')
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, [a[0] + ' | ' + m[0].replace('Read ', '', 1),
                                         a[1] + ' ' + m[1], 'HYB']])
                return
            if reg == 0x01 and n >= 6:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, self._annotate_accel(self.databuf, 0, 'OUT_X_MSB..OUT_Z_LSB')])
                return
            if reg == 0x01 and n == 3 and self.cfg['f_read']:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, self._annotate_accel(self.databuf, 0, 'OUT_X/Y/Z_MSB')])
                return
            if reg == 0x33 and n >= 6:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, self._annotate_mag(self.databuf, 0, 'M_OUT_X_MSB..M_OUT_Z_LSB')])
                return
            raw = self.databuf[0]
            decoded = _decode_reg(reg, raw, self.cfg)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_REG_READ, ['Read %s: %s' % (name, decoded), 'R %s 0x%02X' % (name, raw)]])
            self._track_read(reg, raw, decoded)
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_PTR_WRITE, ['Pointer → %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg]])
                return
            raw = self.databuf[0]
            decoded = _decode_reg(reg, raw, self.cfg)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_REG_WRITE, ['Write %s: %s' % (name, decoded), 'W %s 0x%02X' % (name, raw)]])
            self._track_write(reg, raw)

    def _track_write(self, reg, raw):
        """Update the cached configuration and emit the timing marker."""
        if reg == 0x0E:
            self.cfg['fs'] = raw & 3
        elif reg == 0x09:
            self.cfg['f_mode'] = (raw >> 6) & 3
        elif reg == 0x2A:
            self.cfg['f_read'] = (raw >> 1) & 1
            if raw & 1:
                # active_start: CTRL_REG1 written with active = 1.
                self.active_seen = True
                self._active_ready_emitted = False
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_ACTIVE_START,
                          ['active_start: CTRL_REG1=0x%02X (dr=%s)' % (raw, DR[(raw >> 3) & 7]),
                           'active_start', 'A▶']])
            else:
                self.active_seen = False

    def _track_read(self, reg, raw, decoded):
        """Emit the timing marker and check WHO_AM_I."""
        if reg == 0x00 and self.active_seen and not self._active_ready_emitted and not self.cfg['f_mode']:
            if raw & 0x08:
                # active_ready: first STATUS read with zyxdr = 1.
                self._active_ready_emitted = True
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_ACTIVE_READY,
                          ['active_ready: first STATUS read (0x%02X)' % raw, 'active_ready', 'A✓']])
        elif reg == 0x0D and raw not in (WHO_AM_I_VALUE, 0xC4):
            self._warn(self.ss_block, self.es,
                       'WHO_AM_I: expected 0x%02X, got 0x%02X' % (WHO_AM_I_VALUE, raw))

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass  # pointer already set; keep databuf
            else:
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
            self.addr = addr
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = byte
                self.databuf = []
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
