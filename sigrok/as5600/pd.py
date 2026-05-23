import sigrokdecode as srd

ADDRS = {0x36}

REGS = {
    0x00: 'ZMCO',
    0x01: 'ZPOS_H',
    0x02: 'ZPOS_L',
    0x03: 'MPOS_H',
    0x04: 'MPOS_L',
    0x05: 'MANG_H',
    0x06: 'MANG_L',
    0x07: 'CONF_H',
    0x08: 'CONF_L',
    0x0B: 'STATUS',
    0x0C: 'RAW_ANGLE_H',
    0x0D: 'RAW_ANGLE_L',
    0x0E: 'ANGLE_H',
    0x0F: 'ANGLE_L',
    0x1A: 'AGC',
    0x1B: 'MAGNITUDE_H',
    0x1C: 'MAGNITUDE_L',
    0xFF: 'BURN',
}

PM = {0: 'NOM', 1: 'LPM1', 2: 'LPM2', 3: 'LPM3'}
HYST = {0: 'off', 1: '1 LSB', 2: '2 LSBs', 3: '3 LSBs'}
OUTS = {0: 'analog 0–VDD', 1: 'analog 10–90%', 2: 'PWM'}
PWMF = {0: '115 Hz', 1: '230 Hz', 2: '460 Hz', 3: '920 Hz'}
SF = {0: '16× (2.2 ms)', 1: '8× (1.1 ms)', 2: '4× (0.55 ms)', 3: '2× (0.286 ms)'}
FTH = {
    0: 'slow only', 1: '6/1 LSB', 2: '7/1 LSB', 3: '9/1 LSB',
    4: '18/2 LSB', 5: '21/2 LSB', 6: '24/2 LSB', 7: '10/4 LSB',
}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3


def _decode_status(raw):
    md = 'MD=1 (Magnet OK)' if (raw & 0x08) else 'MD=0 (Not Detected)'
    ml = 'ML=1 (Too Weak)' if (raw & 0x10) else 'ML=0'
    mh = 'MH=1 (Too Strong)' if (raw & 0x20) else 'MH=0'
    return 'STATUS 0x%02X: %s, %s, %s' % (raw, md, ml, mh)


def _decode_angle_12(raw):
    deg = raw * 360.0 / 4096.0
    return '0x%03X = %d counts = %.2f°' % (raw, raw, deg)


def _decode_zmco(raw):
    count = raw & 0x03
    return 'ZMCO: %d burns performed (%d remaining)' % (count, 3 - count)


def _decode_12bit_pair(hi, lo, name):
    val = ((hi & 0x0F) << 8) | lo
    deg = val * 360.0 / 4096.0
    return '%s: 0x%03X = %d counts = %.2f°' % (name, val, val, deg)


def _decode_conf(conf_h, conf_l):
    wd = (conf_h >> 5) & 1
    fth = (conf_h >> 2) & 7
    sf = conf_h & 3
    pwmf = (conf_l >> 6) & 3
    outs = (conf_l >> 4) & 3
    hyst = (conf_l >> 2) & 3
    pm = conf_l & 3
    parts = [
        'CONF 0x%02X%02X' % (conf_h, conf_l),
        'PM=%s' % PM.get(pm, str(pm)),
        'HYST=%s' % HYST.get(hyst, str(hyst)),
        'OUTS=%s' % OUTS.get(outs, str(outs)),
        'PWMF=%s' % PWMF.get(pwmf, str(pwmf)),
        'SF=%s' % SF.get(sf, str(sf)),
        'FTH=%s' % FTH.get(fth, str(fth)),
        'WD=%s' % ('on' if wd else 'off'),
    ]
    return ', '.join(parts)


def _decode_agc(raw):
    return 'AGC: %d (mid-range ≈ optimal airgap)' % raw


def _decode_magnitude(raw):
    return 'MAGNITUDE: 0x%03X = %d' % (raw, raw)


def _decode_burn(raw):
    if raw == 0x80:
        return 'Burn_Angle (burn ZPOS+MPOS to OTP)'
    elif raw == 0x40:
        return 'Burn_Setting (burn MANG+CONF to OTP)'
    elif raw in (0x01, 0x11, 0x10):
        return 'OTP reload sequence (0x%02X)' % raw
    return 'BURN: 0x%02X (unknown command)' % raw


class Decoder(srd.Decoder):
    api_version = 3
    id = 'as5600'
    name = 'AS5600'
    longname = 'AS5600 12-bit rotary position sensor'
    desc = 'Decode AS5600 I2C contactless potentiometer register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['as5600']
    tags = ['IC', 'Position']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('ptr-write', 'Register pointer write'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.ss       = None
        self.es       = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg = self.reg_ptr
        if reg is None:
            if self.databuf:
                self._warn(self.ss_block, self.es,
                           'Read %d byte(s) with no register pointer set' % len(self.databuf))
            return
        name = REGS.get(reg, 'Reg[0x%02X]' % reg)

        if self.is_read:
            if reg in (0x0E, 0x0C, 0x1B) and len(self.databuf) == 2:
                # ANGLE, RAW_ANGLE, MAGNITUDE: 12-bit burst reads
                val = ((self.databuf[0] & 0x0F) << 8) | self.databuf[1]
                if reg == 0x0E:
                    text = 'ANGLE: %s' % _decode_angle_12(val)
                elif reg == 0x0C:
                    text = 'RAW_ANGLE: %s' % _decode_angle_12(val)
                else:
                    text = _decode_magnitude(val)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, [text, 'R %s' % name]])
            elif reg == 0x0B and len(self.databuf) == 1:
                # STATUS: single byte read
                raw = self.databuf[0]
                text = _decode_status(raw)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, [text, 'R STATUS 0x%02X' % raw]])
            elif reg == 0x1A and len(self.databuf) == 1:
                # AGC: single byte read
                raw = self.databuf[0]
                text = _decode_agc(raw)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, [text, 'R AGC %d' % raw]])
            elif reg == 0x00 and len(self.databuf) == 1:
                # ZMCO: single byte read
                raw = self.databuf[0]
                text = _decode_zmco(raw)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, [text, 'R ZMCO %d' % (raw & 0x03)]])
            elif reg in (0x01, 0x03, 0x05) and len(self.databuf) == 2:
                # ZPOS, MPOS, MANG: 12-bit pair reads
                hi = self.databuf[0]
                lo = self.databuf[1]
                label = {0x01: 'ZPOS', 0x03: 'MPOS', 0x05: 'MANG'}[reg]
                text = _decode_12bit_pair(hi, lo, label)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, [text, 'R %s' % label]])
            elif reg in (0x07, 0x08) and len(self.databuf) == 1:
                # CONF_H or CONF_L single read (partial)
                raw = self.databuf[0]
                label = 'CONF_H' if reg == 0x07 else 'CONF_L'
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ, ['%s: 0x%02X' % (label, raw), 'R %s' % label]])
            elif self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d for %s' % (len(self.databuf), name))
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_PTR_WRITE,
                          ['Pointer → %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg]])
            elif reg == 0xFF and len(self.databuf) == 1:
                # BURN write
                raw = self.databuf[0]
                text = _decode_burn(raw)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE, [text, 'W BURN 0x%02X' % raw]])
            elif reg in (0x01, 0x03, 0x05) and len(self.databuf) == 2:
                # ZPOS, MPOS, MANG: 12-bit pair writes
                hi = self.databuf[0]
                lo = self.databuf[1]
                label = {0x01: 'ZPOS', 0x03: 'MPOS', 0x05: 'MANG'}[reg]
                text = _decode_12bit_pair(hi, lo, label)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE, [text, 'W %s' % label]])
            elif reg in (0x07, 0x08) and len(self.databuf) == 1:
                raw = self.databuf[0]
                label = 'CONF_H' if reg == 0x07 else 'CONF_L'
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE, ['%s: 0x%02X' % (label, raw), 'W %s' % label]])
            elif reg == 0x07 and len(self.databuf) == 2:
                # CONF burst write (CONF_H + CONF_L together)
                conf_h = self.databuf[0]
                conf_l = self.databuf[1]
                text = _decode_conf(conf_h, conf_l)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE, [text, 'W CONF 0x%02X%02X' % (conf_h, conf_l)]])
            else:
                self._warn(self.ss_block, self.es,
                           'Unexpected write length %d for %s' % (len(self.databuf), name))

    def decode(self, ss, es, data):
        self.ss, self.es = ss, es
        ptype, pdata = data

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_DATA_WRITE' and not self.databuf:
                pass  # pointer-only write followed by repeated start; keep reg_ptr
            else:
                self._finish_transaction()
                self.databuf  = []
                self.is_read  = False
            self.ss_block = self.ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = addr
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state   = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = byte
                self.databuf = []
                self.state   = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []
