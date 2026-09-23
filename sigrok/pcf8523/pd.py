import sigrokdecode as srd

ADDRS = {0x68}

REGISTERS = {
    0x00: 'CONTROL_1',       0x01: 'CONTROL_2',
    0x02: 'CONTROL_3',       0x03: 'SECONDS',
    0x04: 'MINUTES',         0x05: 'HOURS',
    0x06: 'DAYS',            0x07: 'WEEKDAYS',
    0x08: 'MONTHS',          0x09: 'YEARS',
    0x0A: 'MINUTE_ALARM',    0x0B: 'HOUR_ALARM',
    0x0C: 'DAY_ALARM',       0x0D: 'WEEKDAY_ALARM',
    0x0E: 'OFFSET',          0x0F: 'TMR_CLKOUT_CTRL',
    0x10: 'TMR_A_FREQ_CTRL', 0x11: 'TMR_A_REG',
    0x12: 'TMR_B_FREQ_CTRL', 0x13: 'TMR_B_REG',
}
STATUS_REGS = {0x00, 0x01, 0x02}
TIME_REGS = range(0x03, 0x0A)
LAST_REG = 0x13

WEEKDAY_NAMES = ('Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat')
PM_MODES = {
    0: 'standard switch-over, low detect on',
    1: 'direct switch-over, low detect on',
    2: 'switch-over off, low detect on',
    3: 'switch-over off, low detect on',
    4: 'standard switch-over, low detect off',
    5: 'direct switch-over, low detect off',
    6: 'NOT ALLOWED',
    7: 'switch-over off, low detect off',
}
COF_FREQS = ('32768Hz', '16384Hz', '8192Hz', '4096Hz', '1024Hz', '32Hz', '1Hz', 'off')
TAC_MODES = ('off', 'countdown', 'watchdog', 'off')
SOURCE_CLOCKS = {0: '4096Hz', 1: '64Hz', 2: '1Hz', 3: '1/60Hz'}
TBW_MS = ('46.875', '62.5', '78.125', '93.75', '125', '156.25', '187.5', '218.75')

ANN_DATA              = 0
ANN_STATUS            = 1
ANN_WARNING           = 2
# Named start/end pair for the time_access conformance check (see
# specs/rtc/pcf8523.md, "Timing Constraints" and "Sigrok Decoder", and
# specs/rtc/pcf8523_timing.conf).
ANN_TIME_ACCESS_START = 3
ANN_TIME_ACCESS_DONE  = 4


def _bcd(byte):
    return (byte >> 4) * 10 + (byte & 0x0F)


def _decode_control_1(byte):
    return ('CAP_SEL=%s STOP=%d SR=%d 12_24=%d SIE=%d AIE=%d CIE=%d'
            % ('12.5pF' if byte & 0x80 else '7pF', (byte >> 5) & 1, (byte >> 4) & 1,
               (byte >> 3) & 1, (byte >> 2) & 1, (byte >> 1) & 1, byte & 1))


def _decode_control_2(byte):
    return ('WTAF=%d CTAF=%d CTBF=%d SF=%d AF=%d WTAIE=%d CTAIE=%d CTBIE=%d'
            % ((byte >> 7) & 1, (byte >> 6) & 1, (byte >> 5) & 1, (byte >> 4) & 1,
               (byte >> 3) & 1, (byte >> 2) & 1, (byte >> 1) & 1, byte & 1))


def _decode_control_3(byte):
    return ('PM=%d%d%d (%s) BSF=%d BLF=%d BSIE=%d BLIE=%d'
            % ((byte >> 7) & 1, (byte >> 6) & 1, (byte >> 5) & 1, PM_MODES[(byte >> 5) & 7],
               (byte >> 3) & 1, (byte >> 2) & 1, (byte >> 1) & 1, byte & 1))


def _decode_status(reg, byte):
    return (_decode_control_1, _decode_control_2, _decode_control_3)[reg](byte)


def _decode_alarm(label, byte, mask, bcd=True):
    if byte & 0x80:
        return '%s=ignored (AEN=1)' % label
    value = _bcd(byte & mask) if bcd else byte & mask
    return '%s=%02d (AEN=0, enabled)' % (label, value)


def _decode_value(reg, byte):
    """Register-specific decode text for the data row (0x03-0x13)."""
    if reg == 0x03:
        return 'Seconds=%02d OS=%d' % (_bcd(byte & 0x7F), (byte >> 7) & 1)
    if reg == 0x04:
        return 'Minutes=%02d' % _bcd(byte & 0x7F)
    if reg == 0x05:
        return 'Hours=%02d (24h)' % _bcd(byte & 0x3F)
    if reg == 0x06:
        return 'Day=%02d' % _bcd(byte & 0x3F)
    if reg == 0x07:
        return 'Weekday=%d (%s)' % (byte & 0x07, WEEKDAY_NAMES[byte & 0x07] if (byte & 0x07) < 7 else '?')
    if reg == 0x08:
        return 'Month=%02d' % _bcd(byte & 0x1F)
    if reg == 0x09:
        return 'Year=20%02d' % _bcd(byte)
    if reg == 0x0A:
        return _decode_alarm('Minute', byte, 0x7F)
    if reg == 0x0B:
        return _decode_alarm('Hour', byte, 0x3F)
    if reg == 0x0C:
        return _decode_alarm('Day', byte, 0x3F)
    if reg == 0x0D:
        return _decode_alarm('Weekday', byte, 0x07, bcd=False)
    if reg == 0x0E:
        value = byte & 0x7F
        if value & 0x40:
            value -= 128
        mode = 'every minute' if byte & 0x80 else 'every 2 h'
        ppm = value * (4.069 if byte & 0x80 else 4.34)
        return 'Offset=%d LSB (%.2f ppm, %s)' % (value, ppm, mode)
    if reg == 0x0F:
        return ('TAM=%s TBM=%s COF=%s TAC=%s TBC=%d'
                % ('pulsed' if byte & 0x80 else 'level', 'pulsed' if byte & 0x40 else 'level',
                   COF_FREQS[(byte >> 3) & 7], TAC_MODES[(byte >> 1) & 3], byte & 1))
    if reg == 0x10:
        return 'TAQ=%s' % SOURCE_CLOCKS.get(byte & 7, '1/3600Hz')
    if reg == 0x12:
        return 'TBW=%s ms TBQ=%s' % (TBW_MS[(byte >> 4) & 7], SOURCE_CLOCKS.get(byte & 7, '1/3600Hz'))
    if reg in (0x11, 0x13):
        return 'T_%s=%d' % ('A' if reg == 0x11 else 'B', byte)
    return '0x%02X' % byte


class Decoder(srd.Decoder):
    api_version = 3
    id = 'pcf8523'
    name = 'PCF8523'
    longname = 'NXP PCF8523 low-power I2C real-time clock'
    desc = 'Decode PCF8523 I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pcf8523']
    tags = ['IC', 'Clock/timing']

    annotations = (
        ('data',              'Register data'),
        ('status',            'Control/status register'),
        ('warning',           'Warning'),
        ('time-access-start', 'Time/date access start'),
        ('time-access-done',  'Time/date access done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_TIME_ACCESS_START, ANN_TIME_ACCESS_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.mode = 'IDLE'      # IDLE | WRITE_PENDING_REG | WRITE_STREAM | READ_STREAM | IGNORE
        self.ptr = None          # current register pointer for the active stream
        self.saved_ptr = None    # pointer remembered across STOP/START (reads resume here)
        self.txn_ss = None       # sample of the current transaction's START
        self.first_access = True  # next data byte is the transaction's first register access
        self.time_access = False  # current transaction started a time_access span

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg, code):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, code]])

    @staticmethod
    def _next_ptr(reg):
        return 0x00 if reg >= LAST_REG else reg + 1

    def _mark_first_access(self, es, reg):
        if not self.first_access:
            return
        self.first_access = False
        if reg in TIME_REGS and self.txn_ss is not None:
            self.time_access = True
            self.put(self.txn_ss, es, self.out_ann,
                     [ANN_TIME_ACCESS_START,
                      ['time_access_start: transaction touching %s (0x%02X) began; '
                       'counters frozen until STOP' % (REGISTERS[reg], reg),
                       'time_access_start',
                       '→T']])

    def _emit(self, ss, es, reg, byte, rw):
        if reg not in REGISTERS:
            self._warn(ss, es, '%s of undefined register 0x%02X' % ('Write' if rw == 'W' else 'Read', reg),
                       'UNDEF')
            return
        name = REGISTERS[reg]
        if rw == 'W':
            if reg == 0x01 and byte & 0x80:
                self._warn(ss, es, 'Write of 1 to read-only WTAF (CONTROL_2 bit 7)', 'RO-WTAF')
            if reg == 0x02 and byte & 0x04:
                self._warn(ss, es, 'Write of 1 to read-only BLF (CONTROL_3 bit 2)', 'RO-BLF')
            if reg == 0x02 and ((byte >> 5) & 7) == 6:
                self._warn(ss, es, 'PM[2:0]=110 is not allowed (CONTROL_3)', 'PM110')
            if reg == 0x00 and byte == 0x58:
                self.put(ss, es, self.out_ann,
                         [ANN_STATUS, ['W CONTROL_1 [0x00] software reset (0x58)', 'W SR', 'SR']])
                self.put(ss, es, self.out_python, ('reset', byte))
                return
        if reg in STATUS_REGS:
            text = _decode_status(reg, byte)
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['%s %s [0x%02X] %s' % (rw, name, reg, text),
                                   '%s %s 0x%02X' % (rw, name, byte),
                                   '%s 0x%02X' % (rw, byte)]])
            self.put(ss, es, self.out_python, ('status', (rw, reg, byte)))
            return
        text = _decode_value(reg, byte)
        self.put(ss, es, self.out_ann,
                 [ANN_DATA, ['%s %s [0x%02X] %s' % (rw, name, reg, text),
                             '%s %s 0x%02X' % (rw, name, byte),
                             '%s 0x%02X' % (rw, reg)]])
        self.put(ss, es, self.out_python, ('data', (rw, reg, byte)))

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.mode = 'IDLE'
            self.txn_ss = ss
            self.first_access = True
            self.time_access = False

        elif ptype == 'START REPEAT':
            if self.mode not in ('IDLE', 'IGNORE'):
                self._warn(ss, es, 'Repeated START is not allowed on the PCF8523; '
                           'end each transaction with STOP', 'RSTART')
            self.mode = 'IDLE'
            self.txn_ss = ss
            self.first_access = True

        elif ptype == 'ADDRESS WRITE':
            self.mode = 'WRITE_PENDING_REG' if pdata in ADDRS else 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata in ADDRS:
                self.ptr = self.saved_ptr if self.saved_ptr is not None else 0x00
                self.mode = 'READ_STREAM'
            else:
                self.mode = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.mode == 'WRITE_PENDING_REG':
                self.ptr = pdata
                self.saved_ptr = pdata
                self.mode = 'WRITE_STREAM'
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Pointer → %s (0x%02X)' % (REGISTERS.get(pdata, 'undefined'), pdata),
                                     'PTR 0x%02X' % pdata,
                                     'P%02X' % pdata]])
                if pdata not in REGISTERS:
                    self._warn(ss, es, 'Pointer set to undefined register 0x%02X' % pdata, 'UNDEF')
            elif self.mode == 'WRITE_STREAM':
                self._mark_first_access(es, self.ptr)
                self._emit(ss, es, self.ptr, pdata, 'W')
                self.ptr = self._next_ptr(self.ptr)
                self.saved_ptr = self.ptr

        elif ptype == 'DATA READ':
            if self.mode == 'READ_STREAM':
                self._mark_first_access(es, self.ptr)
                self._emit(ss, es, self.ptr, pdata, 'R')
                self.ptr = self._next_ptr(self.ptr)
                self.saved_ptr = self.ptr

        elif ptype == 'STOP':
            if self.time_access:
                self.put(ss, es, self.out_ann,
                         [ANN_TIME_ACCESS_DONE,
                          ['time_access_done: STOP ended the time/date register transaction',
                           'time_access_done',
                           'T✓']])
            self.mode = 'IDLE'
            self.time_access = False
            self.first_access = True
