"""ADXL345 sigrok protocol decoder.

Sits on top of the sigrok `i2c` decoder and annotates bus transactions with
the ADXL345 register names and decoded field values (data format, BW_RATE,
power-control, threshold/time registers, INT_SOURCE, FIFO_CTL, etc.). Only
I²C transactions are decoded — ADXL345 SPI bursts use a command-byte
prefix that the upstream `i2c` decoder cannot see.
"""

import sigrokdecode as srd

ADDRS = {0x53, 0x1D}

REGS = {
    0x00: 'DEVID',
    0x1D: 'THRESH_TAP',
    0x1E: 'OFSX',
    0x1F: 'OFSY',
    0x20: 'OFSZ',
    0x21: 'DUR',
    0x22: 'LATENT',
    0x23: 'WINDOW',
    0x24: 'THRESH_ACT',
    0x25: 'THRESH_INACT',
    0x26: 'TIME_INACT',
    0x27: 'ACT_INACT_CTL',
    0x28: 'THRESH_FF',
    0x29: 'TIME_FF',
    0x2A: 'TAP_AXES',
    0x2B: 'ACT_TAP_STATUS',
    0x2C: 'BW_RATE',
    0x2D: 'POWER_CTL',
    0x2E: 'INT_ENABLE',
    0x2F: 'INT_MAP',
    0x30: 'INT_SOURCE',
    0x31: 'DATA_FORMAT',
    0x32: 'DATAX0',
    0x33: 'DATAX1',
    0x34: 'DATAY0',
    0x35: 'DATAY1',
    0x36: 'DATAZ0',
    0x37: 'DATAZ1',
    0x38: 'FIFO_CTL',
    0x39: 'FIFO_STATUS',
}

DATA_FORMAT_RANGE = {0: '±2g', 1: '±4g', 2: '±8g', 3: '±16g'}

BW_RATE_CODES = {
    0x0F: 3200.0, 0x0E: 1600.0, 0x0D: 800.0, 0x0C: 400.0,
    0x0B:  200.0, 0x0A:  100.0, 0x09:  50.0, 0x08:  25.0,
    0x07:   12.5, 0x06:    6.25,
}

POWER_CTL_WAKEUP = {0: '8Hz', 1: '8Hz', 2: '4Hz', 3: '2Hz', 4: '1Hz'}
WAKEUP_BITS = {0: '8Hz', 2: '4Hz', 4: '2Hz', 6: '1Hz'}

INT_NAMES = {
    0x80: 'DATA_READY',
    0x40: 'SINGLE_TAP',
    0x20: 'DOUBLE_TAP',
    0x10: 'ACTIVITY',
    0x08: 'INACTIVITY',
    0x04: 'FREE_FALL',
    0x02: 'WATERMARK',
    0x01: 'OVERRUN',
}

FIFO_MODE = {0x00: 'Bypass', 0x40: 'FIFO', 0x80: 'Stream', 0xC0: 'Trigger'}

ANN_WRITE   = 0
ANN_READ    = 1
ANN_WARNING = 2
ANN_RESET_START = 3
ANN_RESET_DONE  = 4
ANN_STARTUP_START = 5
ANN_STARTUP_DONE  = 6


def _signed16(lo, hi):
    """Decode little-endian signed 16-bit value from DATAX0..DATAZ1."""
    v = lo | (hi << 8)
    if v >= 0x8000:
        v -= 0x10000
    return v


def _decode_data_format(raw):
    parts = []
    parts.append('SELF_TEST' if raw & 0x80 else 'no_ST')
    parts.append('3-wire_SPI' if raw & 0x40 else '4-wire_SPI')
    parts.append('INT_invert' if raw & 0x20 else 'INT_normal')
    if raw & 0x10:
        parts.append('rsvd?')
    parts.append('FULL_RES' if raw & 0x08 else '10-bit')
    parts.append('left-just' if raw & 0x04 else 'right-just')
    rng = DATA_FORMAT_RANGE.get(raw & 0x03, '?')
    return 'DATA_FORMAT 0x%02X: %s, %s' % (raw, ', '.join(parts), rng)


def _decode_bw_rate(raw):
    rate = BW_RATE_CODES.get(raw & 0x0F)
    rate_s = ('%.2fHz' % rate) if rate else '?'
    lp = 'LOW_POWER' if raw & 0x10 else 'normal'
    return 'BW_RATE 0x%02X: %s, %s' % (raw, rate_s, lp)


def _decode_power_ctl(raw):
    sleep = 'SLEEP' if raw & 0x04 else 'awake'
    measure = 'MEASURE' if raw & 0x08 else 'standby'
    link = 'Link' if raw & 0x40 else 'no_link'
    auto = 'AutoSleep' if raw & 0x20 else 'no_auto'
    wakeup = WAKEUP_BITS.get(raw & 0x06, '8Hz')
    return 'POWER_CTL 0x%02X: %s, %s, %s, %s, wakeup=%s' % (
        raw, sleep, measure, link, auto, wakeup)


def _decode_int_enable(raw):
    names = [n for bit, n in INT_NAMES.items() if raw & bit]
    return 'INT_ENABLE 0x%02X: %s' % (raw, ', '.join(names) if names else 'none')


def _decode_int_map(raw):
    names = ['%s→INT2' % n for bit, n in INT_NAMES.items() if raw & bit]
    return 'INT_MAP 0x%02X: %s' % (raw, ', '.join(names) if names else 'all →INT1')


def _decode_int_source(raw):
    names = [n for bit, n in INT_NAMES.items() if raw & bit]
    return 'INT_SOURCE 0x%02X: %s' % (raw, ', '.join(names) if names else 'none')


def _decode_fifo_ctl(raw):
    mode = FIFO_MODE.get(raw & 0xC0, '?')
    samples = raw & 0x1F
    return 'FIFO_CTL 0x%02X: mode=%s, samples=%d' % (raw, mode, samples)


def _decode_fifo_status(raw):
    count = raw & 0x3F
    trig = 'TRIG' if raw & 0x80 else ''
    return 'FIFO_STATUS 0x%02X: entries=%d %s' % (raw, count, trig)


def _decode_axis(lo, hi):
    raw = _signed16(lo, hi)
    mg = raw * 3.9
    return 'raw=%+d (%+.1f mg)' % (raw, mg)


def _decode_sensor_burst(buf):
    if len(buf) < 6:
        return 'Short burst %dB' % len(buf)
    ax = _decode_axis(buf[0], buf[1])
    ay = _decode_axis(buf[2], buf[3])
    az = _decode_axis(buf[4], buf[5])
    return 'Burst: X=%s, Y=%s, Z=%s' % (ax, ay, az)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'adxl345'
    name = 'ADXL345'
    longname = 'ADXL345 3-axis MEMS accelerometer (Analog Devices)'
    desc = 'Decode ADXL345 I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['adxl345']
    tags = ['IC', 'Sensor', 'Accelerometer']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('warning',   'Warning'),
        ('startup-start', 'Startup: POWER_CTL Measure set'),
        ('startup-done',  'Startup: first data register read'),
        ('reset-start',   'Reset recovery: SOFT_RESET written'),
        ('reset-done',    'Reset recovery: post-reset write observed'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
        ('conformance', 'Conformance', (ANN_STARTUP_START, ANN_STARTUP_DONE,
                                         ANN_RESET_START, ANN_RESET_DONE)),
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

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg  = self.reg_ptr
        name = REGS.get(reg, 'Reg[0x%02X]' % reg) if reg is not None else '?'

        if self.is_read:
            if reg == 0x00 and len(self.databuf) == 1:
                val = self.databuf[0]
                ok = ' (ADXL345 \u2713)' if val == 0xE5 else ' (expected 0xE5!)'
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['DEVID 0x%02X%s' % (val, ok),
                           'ID 0x%02X' % val]])
            elif reg == 0x32 and len(self.databuf) == 6:
                desc = _decode_sensor_burst(self.databuf)
                # startup-done: first data-register read after POWER_CTL Measure
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_STARTUP_DONE, ['startup_done', 'SD']])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'Burst 6B']])
            elif reg == 0x31 and len(self.databuf) == 1:
                desc = _decode_data_format(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'DF 0x%02X' % self.databuf[0]]])
            elif reg == 0x2C and len(self.databuf) == 1:
                desc = _decode_bw_rate(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'BW 0x%02X' % self.databuf[0]]])
            elif reg == 0x2D and len(self.databuf) == 1:
                desc = _decode_power_ctl(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'PWR 0x%02X' % self.databuf[0]]])
            elif reg == 0x2E and len(self.databuf) == 1:
                desc = _decode_int_enable(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'IE 0x%02X' % self.databuf[0]]])
            elif reg == 0x2F and len(self.databuf) == 1:
                desc = _decode_int_map(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'IM 0x%02X' % self.databuf[0]]])
            elif reg == 0x30 and len(self.databuf) == 1:
                desc = _decode_int_source(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'IS 0x%02X' % self.databuf[0]]])
            elif reg == 0x38 and len(self.databuf) == 1:
                desc = _decode_fifo_ctl(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'FCTL 0x%02X' % self.databuf[0]]])
            elif reg == 0x39 and len(self.databuf) == 1:
                desc = _decode_fifo_status(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'FST 0x%02X' % self.databuf[0]]])
            elif len(self.databuf) == 1:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['Read %s: 0x%02X' % (name, self.databuf[0]),
                           'R %s 0x%02X' % (name, self.databuf[0])]])
            elif self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['Read %s: %d bytes' % (name, len(self.databuf)),
                           'R %s %dB' % (name, len(self.databuf))]])
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          ['Pointer \u2192 %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg]])
            elif len(self.databuf) == 1:
                val = self.databuf[0]
                if reg == 0x2D:
                    desc = _decode_power_ctl(val)
                    if val & 0x08:
                        self.put(self.ss_block, self.es, self.out_ann,
                                 [ANN_STARTUP_START, ['startup_start', 'SS']])
                elif reg == 0x31:
                    desc = _decode_data_format(val)
                elif reg == 0x2C:
                    desc = _decode_bw_rate(val)
                elif reg == 0x2E:
                    desc = _decode_int_enable(val)
                elif reg == 0x2F:
                    desc = _decode_int_map(val)
                elif reg == 0x38:
                    desc = _decode_fifo_ctl(val)
                else:
                    desc = 'Write %s: 0x%02X' % (name, val)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          [desc,
                           'W %s 0x%02X' % (name, val)]])
            elif self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected write length %d for %s' % (len(self.databuf), name))

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass
            else:
                self._finish_transaction()
                self.databuf  = []
                self.is_read  = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = pdata
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