import sigrokdecode as srd

ADDRS = {0x34}

REGS = {
    0x00: 'STATUS1', 0x01: 'STATUS2',
    0x04: 'DATA_BUFFER0', 0x05: 'DATA_BUFFER1', 0x06: 'DATA_BUFFER2', 0x07: 'DATA_BUFFER3',
    0x10: 'COMMON_CONFIG', 0x12: 'BATFET_CTRL', 0x13: 'DIE_TEMP_CTRL', 0x14: 'MIN_VSYS',
    0x15: 'VINDPM', 0x16: 'IINLIM', 0x17: 'GAUGE_RESET', 0x18: 'MODULE_ENABLE',
    0x19: 'WATCHDOG_CTRL', 0x1A: 'LOWBAT_WARN', 0x1B: 'GPIO1_CTRL',
    0x20: 'PWRON_STATUS', 0x21: 'PWROFF_STATUS', 0x22: 'PWROFF_EN', 0x23: 'DCDC_PROT',
    0x24: 'VOFF', 0x25: 'PWROK_CTRL', 0x26: 'SLEEP_CTRL', 0x27: 'PWRKEY_LEVEL',
    0x28: 'FAST_PWRON0', 0x29: 'FAST_PWRON1', 0x2A: 'FAST_PWRON2', 0x2B: 'FAST_PWRON3',
    0x30: 'ADC_ENABLE',
    0x34: 'VBAT_H', 0x35: 'VBAT_L', 0x36: 'TS_H', 0x37: 'TS_L',
    0x38: 'VBUS_H', 0x39: 'VBUS_L', 0x3A: 'VSYS_H', 0x3B: 'VSYS_L', 0x3C: 'TDIE_H', 0x3D: 'TDIE_L',
    0x40: 'IRQ_EN0', 0x41: 'IRQ_EN1', 0x42: 'IRQ_EN2',
    0x48: 'IRQ_STATUS0', 0x49: 'IRQ_STATUS1', 0x4A: 'IRQ_STATUS2',
    0x50: 'TS_CTRL', 0x52: 'TS_HYS_L2H', 0x53: 'TS_HYS_H2L',
    0x54: 'VLTF_CHG', 0x55: 'VHTF_CHG', 0x56: 'VLTF_WORK', 0x57: 'VHTF_WORK',
    0x58: 'JEITA_EN', 0x59: 'JEITA_CV', 0x5A: 'JEITA_COOL', 0x5B: 'JEITA_WARM',
    0x5C: 'TS_CFG_DATA_H', 0x5D: 'TS_CFG_DATA_L',
    0x61: 'IPRECHG', 0x62: 'ICC', 0x63: 'ITERM', 0x64: 'CV', 0x65: 'THERMAL_REG',
    0x67: 'CHG_TIMER', 0x68: 'BAT_DETECT', 0x69: 'CHGLED_CTRL', 0x6A: 'BTN_BAT_VTERM',
    0x80: 'DCDC_EN', 0x81: 'DCDC_PWM', 0x82: 'DCDC1_V', 0x83: 'DCDC2_V', 0x84: 'DCDC3_V', 0x85: 'DCDC4_V',
    0x90: 'LDO_EN0', 0x91: 'LDO_EN1',
    0x92: 'ALDO1_V', 0x93: 'ALDO2_V', 0x94: 'ALDO3_V', 0x95: 'ALDO4_V',
    0x96: 'BLDO1_V', 0x97: 'BLDO2_V', 0x98: 'CPUSLDO_V', 0x99: 'DLDO1_V', 0x9A: 'DLDO2_V',
    0xA1: 'BAT_PARAM', 0xA2: 'GAUGE_CTRL', 0xA4: 'BAT_PERCENT',
}

# ADC pairs: high-byte register -> (name, LSB value, unit)
ADC = {0x34: ('Battery', 0.001, 'V'), 0x36: ('TS', 0.0005, 'V'), 0x38: ('VBUS', 0.001, 'V'),
       0x3A: ('VSYS', 0.001, 'V'), 0x3C: ('Die', None, 'C')}

IRQ_NAMES = (
    ('BAT_UNDER_TEMP_WORK', 'BAT_OVER_TEMP_WORK', 'BAT_UNDER_TEMP_CHG', 'BAT_OVER_TEMP_CHG',
     'GAUGE_NEW_SOC', 'GAUGE_WDT', 'SOC_WARN_L1', 'SOC_WARN_L2'),
    ('PWRON_POS_EDGE', 'PWRON_NEG_EDGE', 'PWRON_LONG', 'PWRON_SHORT',
     'BAT_REMOVE', 'BAT_INSERT', 'VBUS_REMOVE', 'VBUS_INSERT'),
    ('BAT_OVER_VOLTAGE', 'CHARGER_TIMER', 'DIE_OVER_TEMP_L1', 'CHARGE_START',
     'CHARGE_DONE', None, 'LDO_OVER_CURRENT', 'WATCHDOG_EXPIRE'),
)

CHARGE_STATES = {0: 'trickle', 1: 'pre-charge', 2: 'constant current', 3: 'constant voltage',
                 4: 'done', 5: 'not charging'}
DIRECTIONS = {0: 'standby', 1: 'charging', 2: 'discharging', 3: 'reserved'}
IINLIM = {0: '100 mA', 1: '500 mA', 2: '900 mA', 3: '1000 mA', 4: '1500 mA', 5: '2000 mA'}
CV = {1: '4.0 V', 2: '4.1 V', 3: '4.2 V', 4: '4.35 V', 5: '4.4 V'}
PWR_ON = {0: '128 ms', 1: '512 ms', 2: '1 s', 3: '2 s'}
PWR_OFF = {0: '4 s', 1: '6 s', 2: '8 s', 3: '10 s'}
PWR_IRQ = {0: '1 s', 1: '1.5 s', 2: '2 s', 3: '2.5 s'}

# Rail names for the enable registers (bit -> name)
DCDC_EN_BITS = ('DCDC1', 'DCDC2', 'DCDC3', 'DCDC4')
LDO_EN0_BITS = ('ALDO1', 'ALDO2', 'ALDO3', 'ALDO4', 'BLDO1', 'BLDO2', 'CPUSLDO', 'DLDO1')

ANN_REG_WRITE = 0
ANN_REG_READ = 1
ANN_PTR_WRITE = 2
ANN_WARNING = 3
ANN_STATUS = 4


def _flags(raw, names):
    return [n for i, n in enumerate(names) if n and raw & (1 << i)]


def _rail_voltage(reg, raw):
    code = raw & 0x7F
    if reg == 0x82:
        code &= 0x1F
        return ('DCDC1', 1.5 + 0.1 * code)
    if reg in (0x83, 0x84, 0x85):
        name = 'DCDC%d' % (reg - 0x80)
        if code <= 70:
            return (name, 0.5 + 0.01 * code)
        return (name, 1.22 + 0.02 * (code - 71))
    code &= 0x1F
    if reg in (0x92, 0x93, 0x94, 0x95):
        return ('ALDO%d' % (reg - 0x91), 0.5 + 0.1 * code)
    if reg in (0x96, 0x97):
        return ('BLDO%d' % (reg - 0x95), 0.5 + 0.1 * code)
    if reg == 0x98:
        return ('CPUSLDO', 0.5 + 0.05 * code)
    if reg == 0x99:
        return ('DLDO1', 0.5 + 0.1 * code)
    return ('DLDO2', 0.5 + 0.05 * code)


def _icc_ma(code):
    return code * 25 if code <= 8 else 200 + (code - 8) * 100


def _decode_byte(reg, raw):
    """Human-readable decode of one register byte."""
    if reg == 0x00:
        f = _flags(raw, ('CURRENT_LIMIT', 'THERMAL_REG', 'BAT_ACTIVE', 'BAT_PRESENT', 'BATFET_OPEN', 'VBUS_GOOD'))
        return 'VBUS %s, flags: %s' % ('good' if raw & 0x20 else 'absent', ', '.join(f) or 'none')
    if reg == 0x01:
        return 'battery %s, system %s, VINDPM %s, charge %s' % (
            DIRECTIONS.get((raw >> 5) & 3), 'on' if raw & 0x10 else 'off',
            'active' if raw & 0x08 else 'off', CHARGE_STATES.get(raw & 7, 'reserved'))
    if reg == 0x10:
        return 'off-discharge %s, PWROK-restart %s, 16 s off %s%s%s' % (
            'on' if raw & 0x20 else 'off', 'on' if raw & 0x08 else 'off', 'on' if raw & 0x04 else 'off',
            ', RESTART' if raw & 0x02 else '', ', SOFT_POWEROFF' if raw & 0x01 else '')
    if reg == 0x14:
        return 'MIN_VSYS %.1f V' % (3.2 + 0.1 * (raw & 7))
    if reg == 0x15:
        return 'VINDPM %.2f V' % (3.88 + 0.08 * (raw & 15))
    if reg == 0x16:
        return 'IINLIM %s' % IINLIM.get(raw & 7, 'reserved')
    if reg == 0x18:
        return 'gauge %s, backup charge %s, cell charge %s, watchdog %s' % (
            'on' if raw & 8 else 'off', 'on' if raw & 4 else 'off', 'on' if raw & 2 else 'off', 'on' if raw & 1 else 'off')
    if reg == 0x1A:
        return 'warning level 2 %d %%, level 1 %d %%' % (5 + ((raw >> 4) & 15), raw & 15)
    if reg == 0x27:
        return 'IRQLEVEL %s, OFFLEVEL %s, ONLEVEL %s' % (
            PWR_IRQ.get((raw >> 4) & 3), PWR_OFF.get((raw >> 2) & 3), PWR_ON.get(raw & 3))
    if reg == 0x26:
        f = []
        if raw & 0x10: f.append('wake on IRQ')
        if raw & 0x08: f.append('PWROK low on wake')
        if raw & 0x04: f.append('restore voltages')
        if raw & 0x02: f.append('WAKE')
        if raw & 0x01: f.append('SLEEP')
        return ', '.join(f) or 'none'
    if reg == 0x30:
        f = _flags(raw, ('VBAT', 'TS', 'VBUS', 'VSYS', 'TDIE', 'GPADC'))
        return 'ADC channels: ' + (', '.join(f) or 'none')
    if reg in (0x40, 0x41, 0x42, 0x48, 0x49, 0x4A):
        f = _flags(raw, IRQ_NAMES[reg & 3])
        return ('enabled: ' if reg < 0x48 else 'pending: ') + (', '.join(f) or 'none')
    if reg == 0x61:
        return 'IPRECHG %d mA' % ((raw & 15) * 25)
    if reg == 0x62:
        return 'ICC %d mA' % _icc_ma(raw & 31)
    if reg == 0x63:
        return 'ITERM %d mA, termination %s' % ((raw & 15) * 25, 'on' if raw & 0x10 else 'off')
    if reg == 0x64:
        return 'CV %s' % CV.get(raw & 7, 'reserved')
    if reg == 0x6A:
        return 'backup cell terminates at %.1f V' % (2.6 + 0.1 * (raw & 7))
    if reg == 0x80:
        f = [n for i, n in enumerate(DCDC_EN_BITS) if raw & (1 << i)]
        return 'enabled: %s%s%s' % (', '.join(f) or 'none', ', slow DVM ramp' if raw & 0x20 else '',
                                     ', force CCM' if raw & 0x40 else '')
    if reg == 0x90:
        return 'enabled: ' + (', '.join(_flags(raw, LDO_EN0_BITS)) or 'none')
    if reg == 0x91:
        return 'enabled: ' + ('DLDO2' if raw & 1 else 'none')
    if reg in (0x82, 0x83, 0x84, 0x85, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A):
        name, volts = _rail_voltage(reg, raw)
        return '%s %.2f V' % (name, volts)
    if reg == 0xA4:
        return 'battery %d %%' % (raw & 0x7F)
    return '0x%02X' % raw


def _warn_tag(msg):
    m = msg.lower()
    if 'order' in m or 'before' in m:
        return 'ORD?'
    if 'reserved' in m or 'debug' in m:
        return 'BAD?'
    if 'length' in m or 'byte' in m:
        return 'LEN?'
    return 'WARN'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short)."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings
    first = strings[0]
    words = first.split()
    for cand in (words[0][:8] if words else '', ' '.join(words[:2])[:12]):
        if len(strings) >= 3:
            break
        if cand and cand not in strings and len(cand) < len(first):
            i = 0
            while i < len(strings) and len(strings[i]) > len(cand):
                i += 1
            strings.insert(i, cand)
    while len(strings) < 3:
        strings.append(strings[-1][:8])
    return strings


class Decoder(srd.Decoder):
    api_version = 3
    id = 'axp2101'
    name = 'AXP2101'
    longname = 'AXP2101 single-cell Li-ion power-management IC'
    desc = 'Decode AXP2101 I2C power-management register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['axp2101']
    tags = ['IC', 'Power']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
        ('status',    'Status flags'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid != 'warnings' for c in classes}
            if data[0] in tiered:
                data = [data[0], _with_short(data[1])]
                self._mirror_python(ss, es)
        super().put(ss, es, out, data)

    def _mirror_python(self, ss, es):
        """OUTPUT_PYTHON mirror of each transaction-level data annotation:
        ('REG_READ' | 'REG_WRITE', (register, bytes))."""
        out_py = self.__dict__.get('out_python')
        if out_py is None or self.__dict__.get('_py_span') == (ss, es):
            return
        self._py_span = (ss, es)
        try:
            buf = bytes(b & 0xFF for b in (self.databuf or ()))
        except TypeError:
            return
        rw = 'READ' if self.is_read else 'WRITE'
        if self.reg_ptr is not None:
            super().put(ss, es, out_py, ('REG_' + rw, (self.reg_ptr, buf)))

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.databuf = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, msg):
        self.put(self.ss_block, self.es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _adc_text(self, hi_reg, hi, lo):
        name, lsb, unit = ADC[hi_reg]
        value = ((hi & 0x3F) << 8) | lo
        if lsb is None:
            return '%s temperature channel %.1f mV (code %d, %.1f C via vendor formula)' % (
                name, value * 0.1, value, 22 + (7274 - value) / 20.0)
        return '%s %.3f %s (code %d)' % (name, value * lsb, unit, value)

    def _emit_bytes(self, kind, ann):
        """Annotate the data bytes of a transaction register by register."""
        reg = self.reg_ptr
        buf = self.databuf
        verb = 'Read' if self.is_read else 'Write'
        short = 'R' if self.is_read else 'W'
        i = 0
        # A read of a low ADC byte first means the high byte was skipped.
        if self.is_read and reg in (0x35, 0x37, 0x39, 0x3B, 0x3D):
            self._warn('ADC low byte %s read before high byte 0x%02X' % (REGS[reg], reg - 1))
        while i < len(buf):
            r = reg + i
            name = REGS.get(r, 'Reg[0x%02X]' % r)
            if r in ADC and i + 1 < len(buf) and (reg + i + 1) == r + 1:
                text = self._adc_text(r, buf[i], buf[i + 1])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ann, ['%s %s/%s: %s' % (verb, name, REGS[r + 1], text),
                                '%s %s %s' % (short, name[:-2], text.split(' (')[0]), '%s %s' % (short, name[:-2])]])
                if not self.is_read and r in (0x34, 0x36) and buf[i] & 0xC0:
                    self._warn('Write to %s with reserved debug bits 7:6 set (0x%02X)' % (name, buf[i]))
                i += 2
                continue
            raw = buf[i]
            text = _decode_byte(r, raw)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ann, ['%s %s: 0x%02X (%s)' % (verb, name, raw, text),
                            '%s %s 0x%02X' % (short, name, raw), '%s %s' % (short, name)]])
            if r in (0x00, 0x01):
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_STATUS, ['%s: %s' % (name, text), text, name]])
            elif r in (0x48, 0x49, 0x4A) and raw:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_STATUS, ['IRQ pending: %s' % text.replace('pending: ', ''), 'IRQ', 'IRQ']])
            if not self.is_read and r in (0x34, 0x36) and raw & 0xC0:
                self._warn('Write to %s with reserved debug bits 7:6 set (0x%02X)' % (name, raw))
            i += 1

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        if reg is None:
            return
        name = REGS.get(reg, 'Reg[0x%02X]' % reg)
        if self.is_read:
            if self.databuf:
                self._emit_bytes('read', ANN_REG_READ)
        elif not self.databuf:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_PTR_WRITE, ['Pointer → %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg, 'PTR']])
        else:
            self._emit_bytes('write', ANN_REG_WRITE)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass  # pointer already set; keep it for the read that follows
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
