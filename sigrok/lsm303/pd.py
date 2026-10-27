"""LSM303 (LSM303DLHC e-compass) sigrok protocol decoder.

Sits on top of the sigrok `i2c` decoder. Two I2C slaves are decoded:

  * 0x19 accelerometer. Sub-address bit 7 is the auto-increment flag; it is
    stripped from the register number and shown as "[AI]" in the annotation.
  * 0x1E magnetometer. Auto-increment is native, no flag bit.

Named registers are annotated per byte. Bursts are additionally assembled
into physical values: accelerometer 0x28-0x2D as signed g at the last-seen
range and mode, magnetometer 0x03-0x08 as uT at the last-seen gain (X, Z, Y
on the wire), TEMP_OUT as relative degrees C. The timing markers
`odr_start` / `odr_ready` mirror the first_data_ready conformance check in
specs/accelerometer/lsm303.md.
"""

import sigrokdecode as srd

ADDR_ACC = 0x19
ADDR_MAG = 0x1E
ADDRS = {ADDR_ACC, ADDR_MAG}

ACC_REGS = {
    0x20: 'CTRL_REG1_A', 0x21: 'CTRL_REG2_A', 0x22: 'CTRL_REG3_A',
    0x23: 'CTRL_REG4_A', 0x24: 'CTRL_REG5_A', 0x25: 'CTRL_REG6_A',
    0x26: 'REFERENCE_A', 0x27: 'STATUS_REG_A',
    0x28: 'OUT_X_L_A', 0x29: 'OUT_X_H_A', 0x2A: 'OUT_Y_L_A',
    0x2B: 'OUT_Y_H_A', 0x2C: 'OUT_Z_L_A', 0x2D: 'OUT_Z_H_A',
    0x2E: 'FIFO_CTRL_REG_A', 0x2F: 'FIFO_SRC_REG_A',
    0x30: 'INT1_CFG_A', 0x31: 'INT1_SOURCE_A', 0x32: 'INT1_THS_A',
    0x33: 'INT1_DURATION_A', 0x34: 'INT2_CFG_A', 0x35: 'INT2_SOURCE_A',
    0x36: 'INT2_THS_A', 0x37: 'INT2_DURATION_A', 0x38: 'CLICK_CFG_A',
    0x39: 'CLICK_SRC_A', 0x3A: 'CLICK_THS_A', 0x3B: 'TIME_LIMIT_A',
    0x3C: 'TIME_LATENCY_A', 0x3D: 'TIME_WINDOW_A',
}

MAG_REGS = {
    0x00: 'CRA_REG_M', 0x01: 'CRB_REG_M', 0x02: 'MR_REG_M',
    0x03: 'OUT_X_H_M', 0x04: 'OUT_X_L_M', 0x05: 'OUT_Z_H_M',
    0x06: 'OUT_Z_L_M', 0x07: 'OUT_Y_H_M', 0x08: 'OUT_Y_L_M',
    0x09: 'SR_REG_M', 0x0A: 'IRA_REG_M', 0x0B: 'IRB_REG_M',
    0x0C: 'IRC_REG_M', 0x31: 'TEMP_OUT_H_M', 0x32: 'TEMP_OUT_L_M',
}

# Short names used in tier-2 / tier-1 annotation text.
ACC_SHORT = {
    0x20: 'CTRL1', 0x21: 'CTRL2', 0x22: 'CTRL3', 0x23: 'CTRL4',
    0x24: 'CTRL5', 0x25: 'CTRL6', 0x26: 'REF', 0x27: 'STAT',
    0x2E: 'FCTL', 0x2F: 'FSRC', 0x30: 'INT1C', 0x31: 'INT1S',
    0x34: 'INT2C', 0x35: 'INT2S', 0x38: 'CLKC', 0x39: 'CLKS',
}

MAG_SHORT = {
    0x00: 'CRA', 0x01: 'CRB', 0x02: 'MR', 0x09: 'SR',
    0x0A: 'IRA', 0x0B: 'IRB', 0x0C: 'IRC', 0x31: 'TMPH', 0x32: 'TMPL',
}

# Read-only registers: a write to them is a protocol violation.
ACC_RO = {0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2F, 0x31, 0x35, 0x39}
MAG_RO = {0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C,
          0x31, 0x32}

ODR_NAMES = {
    0: 'power-down', 1: '1 Hz', 2: '10 Hz', 3: '25 Hz', 4: '50 Hz',
    5: '100 Hz', 6: '200 Hz', 7: '400 Hz', 8: '1.620 kHz (LP only)',
    9: '1.344 kHz (normal) / 5.376 kHz (LP)',
}

HPM_NAMES = {0: 'normal (ref reset)', 1: 'reference filter',
             2: 'normal', 3: 'autoreset on INT'}

FM_NAMES = {0: 'bypass', 1: 'FIFO', 2: 'stream', 3: 'trigger'}

MAG_DO_NAMES = {
    0: '0.75 Hz', 1: '1.5 Hz', 2: '3 Hz', 3: '7.5 Hz', 4: '15 Hz',
    5: '30 Hz', 6: '75 Hz', 7: '220 Hz',
}

MAG_MD_NAMES = {0: 'continuous', 1: 'single', 2: 'sleep', 3: 'sleep'}

# GN -> (full scale uT, XY LSB/gauss, Z LSB/gauss). GN=0 is undefined.
MAG_GAIN = {
    1: (130, 1100, 980), 2: (190, 855, 760), 3: (250, 670, 600),
    4: (400, 450, 400), 5: (470, 400, 355), 6: (560, 330, 295),
    7: (810, 230, 205),
}

# Accelerometer sensitivity in mg/LSB, indexed [mode][FS]. Mode 0 = low
# power, 1 = normal, 2 = high resolution. Normal and low-power rows are
# derived from the HR row (x4, x16) per the spec; verify on hardware.
ACC_MG = {
    0: (16, 32, 64, 192),
    1: (4, 8, 16, 48),
    2: (1, 2, 4, 12),
}
ACC_SHIFT = {0: 8, 1: 6, 2: 4}
ACC_MODE_NAMES = {0: 'LP 8-bit', 1: 'normal 10-bit', 2: 'HR 12-bit'}

ANN_WRITE = 0
ANN_READ = 1
ANN_WARNING = 2
ANN_STATUS = 3
ANN_ODR_START = 4
ANN_ODR_READY = 5


def _s16(lo, hi):
    """Signed 16-bit from little-endian bytes (lo, hi)."""
    v = (lo | (hi << 8)) & 0xFFFF
    return v - 0x10000 if v & 0x8000 else v


def _s16_be(hi, lo):
    """Signed 16-bit from big-endian bytes (hi, lo)."""
    v = ((hi << 8) | lo) & 0xFFFF
    return v - 0x10000 if v & 0x8000 else v


def _flags(raw, names):
    """Comma-separated names of the set bits; `names` maps bit mask -> name."""
    set_names = [n for mask, n in names if raw & mask]
    return ', '.join(set_names) if set_names else 'none'


ACC_CTRL3_BITS = [(0x80, 'I1_CLICK'), (0x40, 'I1_AOI1'), (0x20, 'I1_AOI2'),
                  (0x10, 'I1_DRDY1'), (0x08, 'I1_DRDY2'), (0x04, 'I1_WTM'),
                  (0x02, 'I1_OVERRUN')]
ACC_CTRL6_BITS = [(0x80, 'I2_CLICK'), (0x40, 'I2_INT1'), (0x20, 'I2_INT2'),
                  (0x10, 'BOOT_I1'), (0x08, 'P2_ACT')]
ACC_CTRL5_BITS = [(0x80, 'BOOT'), (0x40, 'FIFO_EN'), (0x08, 'LIR_INT1'),
                  (0x04, 'D4D_INT1'), (0x02, 'LIR_INT2'), (0x01, 'D4D_INT2')]
ACC_STATUS_BITS = [(0x80, 'ZYXOR'), (0x40, 'ZOR'), (0x20, 'YOR'),
                   (0x10, 'XOR'), (0x08, 'ZYXDA'), (0x04, 'ZDA'),
                   (0x02, 'YDA'), (0x01, 'XDA')]
ACC_INT_SRC_BITS = [(0x40, 'IA'), (0x20, 'ZH'), (0x10, 'ZL'), (0x08, 'YH'),
                    (0x04, 'YL'), (0x02, 'XH'), (0x01, 'XL')]
ACC_INT_CFG_BITS = [(0x20, 'ZHIE'), (0x10, 'ZLIE'), (0x08, 'YHIE'),
                    (0x04, 'YLIE'), (0x02, 'XHIE'), (0x01, 'XLIE')]
ACC_CLICK_CFG_BITS = [(0x20, 'ZD'), (0x10, 'ZS'), (0x08, 'YD'),
                      (0x04, 'YS'), (0x02, 'XD'), (0x01, 'XS')]
ACC_CLICK_SRC_BITS = [(0x40, 'IA'), (0x20, 'DCLICK'), (0x10, 'SCLICK'),
                      (0x08, 'Sign-neg'), (0x04, 'Z'), (0x02, 'Y'),
                      (0x01, 'X')]
ACC_FIFO_SRC_BITS = [(0x80, 'WTM'), (0x40, 'OVRN_FIFO'), (0x20, 'EMPTY')]


class Decoder(srd.Decoder):
    api_version = 3
    id = 'lsm303'
    name = 'LSM303'
    longname = 'LSM303DLHC e-compass (accelerometer + magnetometer)'
    desc = 'Decode LSM303DLHC I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['lsm303']
    tags = ['IC', 'Sensor', 'Accelerometer', 'Magnetometer']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('warning', 'Warning'),
        ('status', 'Status flag'),
        ('odr-start', 'Timing: accelerometer ODR started'),
        ('odr-ready', 'Timing: first accelerometer data ready'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_WRITE, ANN_READ)),
        ('status', 'Status', (ANN_STATUS,)),
        ('timing', 'Timing', (ANN_ODR_START, ANN_ODR_READY)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.out_ann = None
        self.out_python = None
        self._pending_ss = None
        self._reset_state()
        self.reset()

    def reset(self):
        self.txn = None
        self.state = 'IDLE'

    def _reset_state(self):
        # Last-seen accelerometer configuration (init defaults until written).
        self.acc_odr = 5
        self.acc_lpen = 0
        self.acc_hr = 1
        self.acc_fs = 0
        self.acc_cfg_seen = False
        self.odr_armed = False
        # Last-seen magnetometer configuration (init defaults until written).
        self.mag_gn = 1
        self.mag_seen = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    # -- annotation helpers -------------------------------------------------

    def _ann(self, ss, es, cls, texts):
        self.put(ss, es, self.out_ann, [cls, texts])

    def _py(self, ss, es, kind, value):
        self.put(ss, es, self.out_python, (kind, value))

    def _warn(self, ss, es, msg):
        self._ann(ss, es, ANN_WARNING, [msg, self._warn_tag(msg)])

    @staticmethod
    def _warn_tag(msg):
        m = msg.lower()
        if 'read-only' in m:
            return 'RO?'
        if 'auto-increment' in m:
            return 'AI?'
        if 'pointer' in m:
            return 'PTR?'
        if 'ident' in m or 'expected' in m:
            return 'ID?'
        return 'WARN'

    # -- register descriptions ----------------------------------------------

    def _acc_mode(self):
        if self.acc_lpen and self.acc_hr:
            return 2, 'invalid LPen+HR'
        if self.acc_lpen:
            return 0, ACC_MODE_NAMES[0]
        if self.acc_hr:
            return 2, ACC_MODE_NAMES[2]
        return 1, ACC_MODE_NAMES[1]

    def _describe_acc(self, reg, v):
        """Return (long, medium, short, class) and update the accel state."""
        name = ACC_REGS.get(reg, 'Reserved')
        short = ACC_SHORT.get(reg, '0x%02X' % reg)
        cls = ANN_READ
        if reg == 0x20:
            odr = (v >> 4) & 0x0F
            self.acc_odr = odr
            self.acc_lpen = (v >> 3) & 1
            axes = ''.join(c for c, b in (('X', 0x01), ('Y', 0x02), ('Z', 0x04))
                           if v & b) or 'none'
            text = 'ODR=%s, %s, axes %s' % (
                ODR_NAMES.get(odr, '?'),
                'LPen (low-power)' if self.acc_lpen else 'LPen=0',
                axes)
        elif reg == 0x21:
            text = 'HPM=%s, HPCF=%d, FDS=%d, HPCLICK=%d, HPIS2=%d, HPIS1=%d' % (
                HPM_NAMES[(v >> 6) & 3], (v >> 4) & 3, (v >> 3) & 1,
                (v >> 2) & 1, (v >> 1) & 1, v & 1)
        elif reg == 0x22:
            text = 'INT1 routing: %s' % _flags(v, ACC_CTRL3_BITS)
        elif reg == 0x23:
            self.acc_hr = (v >> 3) & 1
            self.acc_fs = (v >> 4) & 3
            self.acc_cfg_seen = True
            mode, mname = self._acc_mode()
            text = 'BDU=%d, BLE=%d, FS=+/-%dg, HR=%d (%s), SIM=%d' % (
                (v >> 7) & 1, (v >> 6) & 1, (2, 4, 8, 16)[self.acc_fs],
                self.acc_hr, mname, v & 1)
        elif reg == 0x24:
            text = 'Flags: %s' % _flags(v, ACC_CTRL5_BITS)
        elif reg == 0x25:
            text = 'INT2 routing: %s, H_LACTIVE=%d' % (
                _flags(v, ACC_CTRL6_BITS), (v >> 1) & 1)
        elif reg == 0x26:
            text = 'REFERENCE=%+d' % (v - 256 if v & 0x80 else v)
        elif reg == 0x27:
            cls = ANN_STATUS
            text = 'Status: %s' % _flags(v, ACC_STATUS_BITS)
        elif 0x28 <= reg <= 0x2D:
            text = 'Output byte, raw 0x%02X' % v
        elif reg == 0x2E:
            text = 'FM=%s, TR=INT%d, FTH=%d' % (
                FM_NAMES[(v >> 6) & 3], 2 if v & 0x20 else 1, v & 0x1F)
        elif reg == 0x2F:
            cls = ANN_STATUS
            text = 'FIFO: %s, FSS=%d' % (
                _flags(v, ACC_FIFO_SRC_BITS), v & 0x1F)
        elif reg in (0x30, 0x34):
            text = 'AOI=%d, 6D=%d, enables: %s' % (
                (v >> 7) & 1, (v >> 6) & 1, _flags(v, ACC_INT_CFG_BITS))
        elif reg in (0x31, 0x35):
            cls = ANN_STATUS
            text = 'Source: %s' % _flags(v, ACC_INT_SRC_BITS)
        elif reg in (0x32, 0x36):
            text = 'Threshold %d (x full-scale/128)' % (v & 0x7F)
        elif reg in (0x33, 0x37):
            text = 'Duration %d (1/ODR)' % (v & 0x7F)
        elif reg == 0x38:
            text = 'Click enables: %s' % _flags(v, ACC_CLICK_CFG_BITS)
        elif reg == 0x39:
            cls = ANN_STATUS
            text = 'Click source: %s' % _flags(v, ACC_CLICK_SRC_BITS)
        elif reg == 0x3A:
            text = 'Click threshold %d' % (v & 0x7F)
        elif reg == 0x3B:
            text = 'Click time limit %d (1/ODR)' % (v & 0x7F)
        elif reg == 0x3C:
            text = 'Click latency %d (1/ODR)' % v
        elif reg == 0x3D:
            text = 'Click window %d (1/ODR)' % v
        else:
            text = 'Reserved register'
        return ('%s 0x%02X: %s' % (name, v, text),
                '%s 0x%02X' % (short, v), '0x%02X' % v, cls)

    def _describe_mag(self, reg, v):
        name = MAG_REGS.get(reg, 'Reserved')
        short = MAG_SHORT.get(reg, '0x%02X' % reg)
        cls = ANN_READ
        if reg == 0x00:
            text = 'TEMP_EN=%d, DO=%s' % (
                (v >> 7) & 1, MAG_DO_NAMES[(v >> 2) & 7])
        elif reg == 0x01:
            self.mag_gn = (v >> 5) & 7
            self.mag_seen = True
            if self.mag_gn == 0:
                text = 'GN=000 (undefined)'
            else:
                fs = MAG_GAIN[self.mag_gn][0]
                text = 'GN=%d, full scale +/-%d uT' % (self.mag_gn, fs)
        elif reg == 0x02:
            text = 'MD=%s' % MAG_MD_NAMES[v & 3]
        elif 0x03 <= reg <= 0x08:
            text = 'Output byte, raw 0x%02X' % v
        elif reg == 0x09:
            cls = ANN_STATUS
            text = 'SR: LOCK=%d, DRDY=%d' % ((v >> 1) & 1, v & 1)
        elif reg == 0x0A:
            text = 'Identification A: %s' % (
                'ok' if v == 0x48 else 'expected 0x48')
        elif reg == 0x0B:
            text = 'Identification B: %s' % (
                'ok' if v == 0x34 else 'expected 0x34')
        elif reg == 0x0C:
            text = 'Identification C: %s' % (
                'ok' if v == 0x33 else 'expected 0x33')
        elif reg == 0x31:
            text = 'Temperature high byte, raw 0x%02X' % v
        elif reg == 0x32:
            text = 'Temperature low byte, raw 0x%02X' % v
        else:
            text = 'Reserved register'
        return ('%s 0x%02X: %s' % (name, v, text),
                '%s 0x%02X' % (short, v), '0x%02X' % v, cls)

    # -- bursts -------------------------------------------------------------

    def _acc_burst_text(self, data):
        mode, mname = self._acc_mode()
        mg_lsb = ACC_MG[mode][self.acc_fs]
        shift = ACC_SHIFT[mode]
        axes = []
        for idx, ax in enumerate(('X', 'Y', 'Z')):
            raw = _s16(data[2 * idx], data[2 * idx + 1]) >> shift
            g = raw * mg_lsb / 1000.0
            axes.append('%s=%+.3f g' % (ax, g))
        note = '' if self.acc_cfg_seen else ' (default config assumed)'
        return ('Accel burst: %s at +/-%dg, %s%s' % (
            ', '.join(axes), (2, 4, 8, 16)[self.acc_fs], mname, note),
            'Accel burst', 'ACC')

    def _mag_burst_text(self, data):
        gn = self.mag_gn if self.mag_gn in MAG_GAIN else 1
        _, gxy, gz = MAG_GAIN[gn]
        # Wire order is X, Z, Y, each high byte first.
        x = _s16_be(data[0], data[1]) * 100.0 / gxy
        z = _s16_be(data[2], data[3]) * 100.0 / gz
        y = _s16_be(data[4], data[5]) * 100.0 / gxy
        note = '' if self.mag_seen else ' (default gain assumed)'
        return ('Mag burst: X=%+.1f uT, Y=%+.1f uT, Z=%+.1f uT%s' % (
            x, y, z, note), 'Mag burst', 'MAG')

    def _temp_text(self, hi, lo):
        raw = _s16_be(hi, lo) >> 4
        return ('Temperature: %+.3f C relative (8 LSB/C, no absolute ref)'
                % (raw / 8.0), 'Temp %+.2f C' % (raw / 8.0), 'TEMP')

    # -- transaction processing ---------------------------------------------

    def _process(self, txn):
        """Annotate one completed transaction (pointer-only write, or
        pointer + data write, or read from a pointer)."""
        addr = txn['addr']
        is_write = txn['write']
        ptr = txn['ptr']
        items = txn['bytes']  # (ss, es, value) of data bytes only
        ss_all, es_all = txn['ss'], txn['es']
        regnames = ACC_REGS if addr == ADDR_ACC else MAG_REGS
        ro = ACC_RO if addr == ADDR_ACC else MAG_RO

        if ptr is None:
            if items:
                self._warn(items[0][0], items[-1][1],
                           'Data without register pointer (read or write)')
            return

        ai = False
        base = ptr
        if addr == ADDR_ACC:
            ai = bool(ptr & 0x80)
            base = ptr & 0x7F

        if not items:
            if is_write:
                ai_s = ' [AI]' if ai else ''
                self._ann(ss_all, es_all, ANN_WRITE, [
                    'Pointer -> %s (0x%02X)%s'
                    % (regnames.get(base, 'Reserved'), base, ai_s),
                    'PTR %s' % regnames.get(base, 'Reserved'),
                    'PTR 0x%02X' % base])
                self._py(ss_all, es_all, 'REG_WRITE', (addr, base, ()))
            return

        if addr == ADDR_ACC and len(items) > 1 and not ai:
            self._warn(items[0][0], items[-1][1],
                       'Multi-byte accel access without auto-increment '
                       '(bit 7): all bytes map to one register')

        values = [v for _, _, v in items]
        for i, (ss, es, v) in enumerate(items):
            if addr == ADDR_ACC:
                # Auto-increment walks the map; otherwise every byte
                # lands on the pointed-to register.
                reg = base + i if (ai or len(items) == 1) else base
                if reg > 0x3D:
                    reg = 0xFF
            else:
                reg = base + i

            if is_write and reg in ro:
                self._warn(ss, es, 'Write to read-only register 0x%02X (%s)'
                           % (reg, regnames.get(reg, 'Reserved')))

            if addr == ADDR_ACC:
                long_t, med_t, short_t, cls = self._describe_acc(reg, v)
            else:
                long_t, med_t, short_t, cls = self._describe_mag(reg, v)
            if is_write:
                cls = ANN_WRITE
            self._ann(ss, es, cls, [long_t, med_t, short_t])

            # Timing: accelerometer ODR start (write to CTRL_REG1_A, ODR != 0).
            if addr == ADDR_ACC and is_write and reg == 0x20:
                if (v & 0xF0) != 0:
                    self.odr_armed = True
                    self._ann(ss, es, ANN_ODR_START, [
                        'odr_start: CTRL_REG1_A write with ODR != 0000',
                        'odr_start', '->ODR'])
                    self._py(ss, es, 'ODR_START', v >> 4)
                else:
                    self.odr_armed = False

            # Timing: first STATUS_REG_A read with ZYXDA=1 after odr_start.
            if addr == ADDR_ACC and not is_write and reg == 0x27:
                if (v & 0x08) and self.odr_armed:
                    self.odr_armed = False
                    self._ann(ss, es, ANN_ODR_READY, [
                        'odr_ready: first STATUS_REG_A read with ZYXDA=1',
                        'odr_ready', 'RDY->'])
                    self._py(ss, es, 'ODR_READY', v)

        # Assembled burst annotations (reads only).
        if not is_write:
            if addr == ADDR_ACC and base == 0x28 and ai and len(values) >= 6:
                text, med, short = self._acc_burst_text(values[:6])
                self._ann(items[0][0], items[5][1], ANN_READ,
                          [text, med, short])
            elif addr == ADDR_MAG and base == 0x03 and len(values) >= 6:
                text, med, short = self._mag_burst_text(values[:6])
                self._ann(items[0][0], items[5][1], ANN_READ,
                          [text, med, short])
            elif addr == ADDR_MAG and base == 0x31 and len(values) >= 2:
                text, med, short = self._temp_text(values[0], values[1])
                self._ann(items[0][0], items[1][1], ANN_READ,
                          [text, med, short])

        # One transaction-level OUTPUT_PYTHON record.
        self._py(ss_all, es_all, 'REG_WRITE' if is_write else 'REG_READ',
                 (addr, base, tuple(values)))

    # -- protocol state machine ---------------------------------------------

    def _new_txn(self, addr, write, ss):
        return {'addr': addr, 'write': write, 'ptr': None, 'ss': ss,
                'es': ss, 'bytes': [], 'ptr_span': None}

    def _flush(self):
        txn = self.txn
        self.txn = None
        if txn is None:
            return
        if txn['write'] and txn['ptr'] is None:
            return
        self._process(txn)

    def decode(self, ss, es, data):
        ptype, pdata = data
        if self.out_ann is None:
            self.out_ann = self.register(srd.OUTPUT_ANN)
            self.out_python = self.register(srd.OUTPUT_PYTHON)

        if ptype == 'START':
            self._flush()
            self.state = 'GET_ADDR'
            self._pending_ss = ss

        elif ptype == 'START REPEAT':
            txn = self.txn
            if txn is not None and txn['write'] and txn['ptr'] is not None \
                    and not txn['bytes']:
                # Pointer-only write followed by repeated start: a read
                # transaction in progress. Keep txn until ADDRESS READ.
                txn['es'] = es
                self.state = 'GET_ADDR_REP'
            else:
                self._flush()
                self.state = 'GET_ADDR'
                self._pending_ss = ss

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            is_read = ptype == 'ADDRESS READ'
            if pdata not in ADDRS:
                self.state = 'IDLE'
                self.txn = None
                return
            if self.state == 'GET_ADDR_REP' and self.txn is not None:
                # Read after a pointer-only write: the pointer carries over.
                if is_read and self.txn['addr'] == pdata:
                    self.txn['write'] = False
                    self.txn['bytes'] = []
                    self.state = 'GET_DATA_READ'
                    return
            self._flush()
            start = self._pending_ss if self._pending_ss is not None else ss
            self.txn = self._new_txn(pdata, not is_read, start)
            self.state = 'GET_DATA_READ' if is_read else 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            txn = self.txn
            if txn is None:
                return
            txn['es'] = es
            if txn['ptr'] is None:
                txn['ptr'] = pdata
                txn['ptr_span'] = (ss, es)
                self.state = 'GET_DATA_WRITE'
            else:
                txn['bytes'].append((ss, es, pdata))

        elif ptype == 'DATA READ':
            txn = self.txn
            if txn is None or self.state != 'GET_DATA_READ':
                return
            txn['es'] = es
            txn['bytes'].append((ss, es, pdata))

        elif ptype == 'STOP':
            self._flush()
            self.state = 'IDLE'
            self._pending_ss = None
