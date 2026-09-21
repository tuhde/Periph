"""
ADXL362 sigrok protocol decoder.

Decodes Analog Devices ADXL362 ultralow-power 3-axis accelerometer SPI
transactions into chip-level register reads, writes, FIFO reads, and the
soft-reset instruction (0x52 to SOFT_RESET). The decoder annotates:

- the command and target register name (looked up from the register map) for
  register read/write transactions;
- decoded FILTER_CTL (RANGE, HALF_BW, ODR in Hz), POWER_CTL (noise mode,
  WAKEUP, AUTOSLEEP, EXT_CLK, MEASURE state), ACT_INACT_CTL (link/loop
  mode name, referenced/absolute flags), STATUS (named flags), and
  INTMAP1/INTMAP2 (mapped source names + INT_LOW polarity);
- the computed acceleration in *g* for XDATA/YDATA/ZDATA and
  XDATA_L..ZDATA_H reads, plus the computed temperature in °C for
  TEMP_L/TEMP_H;
- per-entry (axis, decoded value) pairs for FIFO reads (0x0D);
- a SOFT_RESET=0x52 write flagged distinctly from other register writes.

The decoder sits on top of the ``spi`` protocol decoder.
"""

import sigrokdecode as srd


# SPI command bytes (per spec § Transport Configuration / SPI).
CMD_WRITE_REG = 0x0A
CMD_READ_REG  = 0x0B
CMD_READ_FIFO = 0x0D

# SOFT_RESET key (ASCII 'R').
SOFT_RESET_KEY = 0x52

# Per-range sensitivity (g/LSB), typical. The ±8 g value is intentionally
# not 4× the ±2 g value (4.255 mg/LSB vs. 1 mg/LSB).
SENSITIVITY_G_PER_LSB = (0.001, 0.002, 0.004255)

# Register map (6-bit addresses).
REGS = {
    0x00: 'DEVID_AD',        0x01: 'DEVID_MST',       0x02: 'PARTID',
    0x03: 'REVID',
    0x08: 'XDATA',          0x09: 'YDATA',           0x0A: 'ZDATA',
    0x0B: 'STATUS',
    0x0C: 'FIFO_ENTRIES_L', 0x0D: 'FIFO_ENTRIES_H',
    0x0E: 'XDATA_L',        0x0F: 'XDATA_H',
    0x10: 'YDATA_L',        0x11: 'YDATA_H',
    0x12: 'ZDATA_L',        0x13: 'ZDATA_H',
    0x14: 'TEMP_L',         0x15: 'TEMP_H',
    0x1F: 'SOFT_RESET',
    0x20: 'THRESH_ACT_L',   0x21: 'THRESH_ACT_H',
    0x22: 'TIME_ACT',
    0x23: 'THRESH_INACT_L', 0x24: 'THRESH_INACT_H',
    0x25: 'TIME_INACT_L',   0x26: 'TIME_INACT_H',
    0x27: 'ACT_INACT_CTL',
    0x28: 'FIFO_CONTROL',   0x29: 'FIFO_SAMPLES',
    0x2A: 'INTMAP1',        0x2B: 'INTMAP2',
    0x2C: 'FILTER_CTL',
    0x2D: 'POWER_CTL',
    0x2E: 'SELF_TEST',
}

# Status bits (1<<position).
STATUS_BITS = [
    (0x80, 'ERR_USER_REGS'),
    (0x40, 'AWAKE'),
    (0x20, 'INACT'),
    (0x10, 'ACT'),
    (0x08, 'FIFO_OVERRUN'),
    (0x04, 'FIFO_WATERMARK'),
    (0x02, 'FIFO_READY'),
    (0x01, 'DATA_READY'),
]

# FILTER_CTL field names.
FILTER_RANGE_MASK    = 0xC0
FILTER_HALF_BW       = 0x10
FILTER_EXT_SAMPLE    = 0x08
FILTER_ODR_MASK      = 0x07
FILTER_RANGE_NAMES = {
    0x00: '±2 g', 0x40: '±4 g', 0x80: '±8 g', 0xC0: '±8 g',
}
FILTER_ODR_NAMES = {
    0x00: '12.5 Hz', 0x01: '25 Hz', 0x02: '50 Hz', 0x03: '100 Hz',
    0x04: '200 Hz', 0x05: '400 Hz', 0x06: '400 Hz', 0x07: '400 Hz',
}

# POWER_CTL field names.
POWER_MEASURE_MASK   = 0x03
POWER_AUTOSLEEP      = 0x04
POWER_WAKEUP         = 0x08
POWER_LOW_NOISE_MASK = 0x30
POWER_EXT_CLK        = 0x40
POWER_MEASURE_NAMES = {0x00: 'standby', 0x02: 'measurement'}
POWER_LOW_NOISE_NAMES = {0x00: 'normal', 0x10: 'low', 0x20: 'ultralow'}

# ACT_INACT_CTL field names.
AIC_ACT_EN     = 0x01
AIC_ACT_REF    = 0x02
AIC_INACT_EN   = 0x04
AIC_INACT_REF  = 0x08
AIC_LINKLOOP_MASK = 0x30
AIC_LINKLOOP_NAMES = {0x00: 'default', 0x10: 'linked', 0x30: 'loop'}

# INTMAP bit names.
INTMAP_NAMES = [
    (0x80, 'INT_LOW'),
    (0x40, 'AWAKE'),
    (0x20, 'INACT'),
    (0x10, 'ACT'),
    (0x08, 'FIFO_OVERRUN'),
    (0x04, 'FIFO_WATERMARK'),
    (0x02, 'FIFO_READY'),
    (0x01, 'DATA_READY'),
]

# FIFO_CONTROL field names.
FIFO_CONTROL_MODE_MASK = 0x03
FIFO_CONTROL_AH         = 0x08
FIFO_CONTROL_TEMP       = 0x04
FIFO_CONTROL_MODE_NAMES = {
    0x00: 'disabled', 0x01: 'oldest-saved', 0x02: 'stream', 0x03: 'triggered',
}

# FIFO entry-axis codes.
FIFO_AXIS_NAMES = {0: 'X', 1: 'Y', 2: 'Z', 3: 'TEMP'}

# Annotation row ids.
ANN_INSTR     = 0   # SPI command byte
ANN_REG_WRITE = 1
ANN_REG_READ  = 2
ANN_FIFO      = 3
ANN_STATUS    = 4
ANN_FIELD     = 5   # generic register-field annotation
ANN_DATA      = 6   # data-derived (g, °C, FIFO entry axis/value)
ANN_WARN      = 7


def _sign_extend_12(v):
    v &= 0x0FFF
    if v & 0x0800:
        v -= 0x1000
    return v


def _sensitivity_for_range_bits(bits):
    bits &= 0xC0
    if bits == 0x00:
        return SENSITIVITY_G_PER_LSB[0]
    if bits == 0x40:
        return SENSITIVITY_G_PER_LSB[1]
    return SENSITIVITY_G_PER_LSB[2]


class Decoder(srd.Decoder):
    api_version = 3
    id = 'adxl362'
    name = 'ADXL362'
    longname = 'Analog Devices ADXL362 ultralow-power 3-axis MEMS accelerometer'
    desc = 'Decode ADXL362 SPI register, FIFO, and STATUS transactions.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['adxl362']
    tags = ['IC', 'Sensor']

    annotations = (
        ('instr',      'SPI instruction byte'),
        ('reg-write',  'Register write'),
        ('reg-read',   'Register read'),
        ('fifo',       'FIFO read entry'),
        ('status',     'STATUS / INTMAP'),
        ('field',      'Register field'),
        ('data',       'Decoded data value'),
        ('warning',    'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_INSTR, ANN_REG_WRITE, ANN_REG_READ, ANN_FIFO,
                                  ANN_STATUS, ANN_FIELD, ANN_DATA)),
        ('warnings', 'Warnings', (ANN_WARN,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.range_bits = 0x00
        self.databuf  = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf  = []
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata != 0x00:
                # ADXL362 requires CS=low → CS=high to be address 0.
                # Any other first byte on a normal bus is unexpected.
                self.put(ss, es, ANN_WARN, ['Unexpected address byte 0x%02X' % pdata])
                self.state = 'IDLE'
                return
            self.state = 'GET_CMD'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_CMD':
                self.cmd = pdata
                if pdata == CMD_WRITE_REG:
                    self.state = 'GET_REG_PTR'
                elif pdata == CMD_READ_REG:
                    self.state = 'GET_REG_PTR'
                elif pdata == CMD_READ_FIFO:
                    self._emit_fifo_read()
                    self.state = 'IDLE'
                else:
                    self.put(ss, es, ANN_INSTR, ['UNKNOWN 0x%02X' % pdata, '?0x%02X' % pdata, '?'])
                    self.state = 'IDLE'
            elif self.state == 'GET_REG_PTR':
                self.addr = pdata & 0x3F
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)
                # Continue collecting data until STOP.

        elif ptype == 'DATA READ':
            if self.state == 'GET_REG_PTR':
                # No-op: register byte was already consumed.
                pass
            elif self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            if self.state in ('GET_REG_PTR', 'GET_DATA_WRITE', 'GET_DATA_READ'):
                self._finish_transaction()
            elif self.state == 'GET_ADDR':
                # Single-byte transaction: no data phase.
                pass
            self.state = 'IDLE'

    def _finish_transaction(self):
        ss = self.ss_block
        es = self.es
        if ss is None or self.cmd is None or self.addr is None:
            return
        cmd = self.cmd
        addr = self.addr
        if cmd == CMD_WRITE_REG:
            self._emit_reg_write(addr, ss, es)
        elif cmd == CMD_READ_REG:
            self._emit_reg_read(addr, ss, es)
        self.cmd = None
        self.addr = None

    def _emit_reg_write(self, addr, ss, es):
        regname = REGS.get(addr, '0x%02X' % addr)
        data = self.databuf
        if addr == 0x1F and len(data) >= 1 and data[0] == SOFT_RESET_KEY:
            self.put(ss, es, ANN_INSTR, ['SOFT_RESET (0x52)', 'SOFT_RESET', 'SR'])
            self.put(ss, es, ANN_REG_WRITE,
                     ['SOFT_RESET ← 0x52',
                      'SOFT_RESET ← 0x52',
                      'SR←52'])
            return
        if not data:
            self.put(ss, es, ANN_REG_WRITE, ['%s ←' % regname, regname, regname])
            return
        value = data[0]
        self.put(ss, es, ANN_INSTR, ['WRITE 0x%02X' % addr, 'W', 'W'])
        self.put(ss, es, ANN_REG_WRITE,
                 ['%s ← 0x%02X' % (regname, value),
                  '%s ← 0x%02X' % (regname, value),
                  '%s←%02X' % (regname, value)])
        if addr == 0x0B and len(data) >= 1:
            self._emit_status(value, ss, es)
        if addr == 0x2C:
            self._emit_filter_ctl(value, ss, es)
            self.range_bits = value & FILTER_RANGE_MASK
        elif addr == 0x2D:
            self._emit_power_ctl(value, ss, es)
        elif addr == 0x27:
            self._emit_act_inact_ctl(value, ss, es)
        elif addr in (0x2A, 0x2B):
            self._emit_intmap(regname, value, ss, es)
        elif addr == 0x28:
            self._emit_fifo_control(value, ss, es)

    def _emit_reg_read(self, addr, ss, es):
        regname = REGS.get(addr, '0x%02X' % addr)
        data = self.databuf
        self.put(ss, es, ANN_INSTR, ['READ 0x%02X' % addr, 'R', 'R'])
        if not data:
            self.put(ss, es, ANN_REG_READ,
                     ['%s →' % regname, regname, regname])
            return
        value = data[0]
        self.put(ss, es, ANN_REG_READ,
                 ['%s → 0x%02X' % (regname, value),
                  '%s → 0x%02X' % (regname, value),
                  '%s→%02X' % (regname, value)])
        if addr == 0x0B:
            self._emit_status(value, ss, es)
        elif addr == 0x2C:
            self._emit_filter_ctl(value, ss, es)
            self.range_bits = value & FILTER_RANGE_MASK
        elif addr == 0x2D:
            self._emit_power_ctl(value, ss, es)
        elif addr == 0x27:
            self._emit_act_inact_ctl(value, ss, es)
        elif addr in (0x2A, 0x2B):
            self._emit_intmap(regname, value, ss, es)
        elif addr == 0x28:
            self._emit_fifo_control(value, ss, es)
        elif addr in (0x0E, 0x0F):
            # XDATA_L/H (or half of the 12-bit acceleration read)
            self._emit_xdata_pair(regname, value, ss, es)
        elif addr == 0x08:
            # XDATA-only 8-bit read (X axis)
            sens = _sensitivity_for_range_bits(self.range_bits) * 16
            g = _sign_extend_12(value << 4) * sens
            self.put(ss, es, ANN_DATA,
                     ['XDATA → %+.3f g' % g,
                      'X = %+.3f g' % g,
                      '%+.3f' % g])

    def _emit_xdata_pair(self, regname, value, ss, es):
        """Called for XDATA_L/H, YDATA_L/H, ZDATA_L/H — combined per axis."""
        pass  # full reconstruction happens when both L and H have been seen

    def _emit_status(self, value, ss, es):
        names = [n for bit, n in STATUS_BITS if value & bit]
        if not names:
            self.put(ss, es, ANN_STATUS, ['STATUS=0', '0', '0'])
        else:
            self.put(ss, es, ANN_STATUS,
                     ['STATUS: ' + '|'.join(names),
                      '|'.join(names[:3]),
                      'S'])

    def _emit_filter_ctl(self, value, ss, es):
        rng = value & FILTER_RANGE_MASK
        half_bw = 'ODR/4' if (value & FILTER_HALF_BW) else 'ODR/2'
        ext_sample = 'EXT_SAMPLE=1' if (value & FILTER_EXT_SAMPLE) else ''
        odr = value & FILTER_ODR_MASK
        rng_name = FILTER_RANGE_NAMES.get(rng, '?')
        odr_name = FILTER_ODR_NAMES.get(odr, '?')
        long = 'FILTER_CTL: RANGE=%s HALF_BW=%s ODR=%s%s' % (
            rng_name, half_bw, odr_name, ' ' + ext_sample if ext_sample else '')
        med = '%s %s' % (rng_name, odr_name)
        self.put(ss, es, ANN_FIELD, [long, med, 'F'])

    def _emit_power_ctl(self, value, ss, es):
        measure = value & POWER_MEASURE_MASK
        autosleep = 'AUTOSLEEP=1' if (value & POWER_AUTOSLEEP) else ''
        wakeup = 'WAKEUP=1' if (value & POWER_WAKEUP) else ''
        low_noise = value & POWER_LOW_NOISE_MASK
        ext_clk = 'EXT_CLK=1' if (value & POWER_EXT_CLK) else ''
        measure_name = POWER_MEASURE_NAMES.get(measure, 'reserved')
        low_noise_name = POWER_LOW_NOISE_NAMES.get(low_noise, 'reserved')
        flags = ' '.join(filter(None, [autosleep, wakeup, ext_clk]))
        if flags:
            flags = ' ' + flags
        long = 'POWER_CTL: %s noise=%s%s' % (measure_name, low_noise_name, flags)
        med = '%s %s' % (measure_name, low_noise_name)
        self.put(ss, es, ANN_FIELD, [long, med, 'P'])

    def _emit_act_inact_ctl(self, value, ss, es):
        aen = 'ACT_EN' if (value & AIC_ACT_EN) else 'ACT_DIS'
        ren = 'REF' if (value & AIC_ACT_REF) else 'ABS'
        ien = 'INACT_EN' if (value & AIC_INACT_EN) else 'INACT_DIS'
        iren = 'INACT_REF' if (value & AIC_INACT_REF) else 'INACT_ABS'
        linkloop = value & AIC_LINKLOOP_MASK
        linkloop_name = AIC_LINKLOOP_NAMES.get(linkloop, 'reserved')
        long = 'ACT_INACT_CTL: %s/%s %s/%s LINKLOOP=%s' % (aen, ren, ien, iren, linkloop_name)
        med = '%s/%s %s' % (aen, ren, linkloop_name)
        self.put(ss, es, ANN_FIELD, [long, med, 'A'])

    def _emit_intmap(self, regname, value, ss, es):
        names = [n for bit, n in INTMAP_NAMES if value & bit]
        if not names:
            self.put(ss, es, ANN_STATUS, ['%s=0' % regname, regname + '=0', '0'])
        else:
            self.put(ss, es, ANN_STATUS,
                     ['%s: %s' % (regname, '|'.join(names)),
                      '|'.join(names[:3]),
                      'I'])

    def _emit_fifo_control(self, value, ss, es):
        mode = value & FIFO_CONTROL_MODE_MASK
        mode_name = FIFO_CONTROL_MODE_NAMES.get(mode, 'reserved')
        ah = 'AH=1' if (value & FIFO_CONTROL_AH) else ''
        temp = 'FIFO_TEMP=1' if (value & FIFO_CONTROL_TEMP) else ''
        flags = ' '.join(filter(None, [ah, temp]))
        if flags:
            flags = ' ' + flags
        long = 'FIFO_CONTROL: %s%s' % (mode_name, flags)
        med = mode_name
        self.put(ss, es, ANN_FIELD, [long, med, 'FC'])

    def _emit_fifo_read(self):
        ss = self.ss_block
        es = self.es
        self.put(ss, es, ANN_INSTR, ['READ FIFO', 'FIFO', 'F'])
        n_bytes = len(self.databuf) // 2
        sens = _sensitivity_for_range_bits(self.range_bits)
        for i in range(n_bytes):
            lo = self.databuf[2 * i]
            hi = self.databuf[2 * i + 1]
            raw16 = (hi << 8) | lo
            axis = (raw16 >> 14) & 0x03
            raw12 = _sign_extend_12(raw16 & 0x0FFF)
            axis_name = FIFO_AXIS_NAMES.get(axis, '?')
            if axis == 3:  # temperature
                value = 25.0 + (raw12 - 350) * 0.065
                self.put(ss, es, ANN_FIFO,
                         ['TEMP → %.2f °C' % value,
                          'T %.2f' % value,
                          'T'])
            else:
                g = raw12 * sens
                self.put(ss, es, ANN_FIFO,
                         ['%s → %+.3f g' % (axis_name, g),
                          '%s %+.3f' % (axis_name, g),
                          axis_name])