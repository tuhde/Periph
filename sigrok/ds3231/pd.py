import sigrokdecode as srd

ADDRS = {0x68}

REGISTERS = {
    0x00: 'SECONDS',          0x01: 'MINUTES',
    0x02: 'HOURS',            0x03: 'DAY',
    0x04: 'DATE',             0x05: 'MONTH_CENTURY',
    0x06: 'YEAR',             0x07: 'ALARM1_SECONDS',
    0x08: 'ALARM1_MINUTES',   0x09: 'ALARM1_HOURS',
    0x0A: 'ALARM1_DAY_DATE',  0x0B: 'ALARM2_MINUTES',
    0x0C: 'ALARM2_HOURS',     0x0D: 'ALARM2_DAY_DATE',
    0x0E: 'CONTROL',          0x0F: 'CONTROL_STATUS',
    0x10: 'AGING_OFFSET',     0x11: 'TEMP_MSB',
    0x12: 'TEMP_LSB',
}
READONLY = {0x11, 0x12}
LAST_REG = 0x12

ANN_DATA           = 0
ANN_STATUS         = 1
ANN_WARNING        = 2
# Named start/end pair for the temp_conversion_ready conformance check (see
# specs/rtc/ds3231.md, "Timing Constraints" and "Sigrok Decoder", and
# specs/rtc/ds3231_timing.conf). Additive: does not change the data/status/
# warning annotations PulseView's manual verification depends on.
ANN_TEMP_CONV_START = 3
ANN_TEMP_CONV_DONE  = 4


def _bcd(byte):
    return (byte >> 4) * 10 + (byte & 0x0F)


def _decode_hours(byte):
    """Returns a human string. The HOURS field (and the Alarm 1/2 hours
    fields, which share this encoding) is not plain 2-digit BCD: bit 6
    selects 12-/24-hour mode, and the tens-of-hours digit is carried by a
    single bit (bit 4) in 12-hour mode or by bits 5:4 together (20-23
    indicator + 10s bit) in 24-hour mode - see specs/rtc/ds3231.md's HOURS
    bit-field table."""
    if byte & 0x40:  # 12-hour mode
        tens = 1 if (byte & 0x10) else 0
        hour = tens * 10 + (byte & 0x0F)
        ampm = 'PM' if (byte & 0x20) else 'AM'
        return '%d %s (12h)' % (hour, ampm)
    tens = 2 if (byte & 0x20) else (1 if (byte & 0x10) else 0)
    hour = tens * 10 + (byte & 0x0F)
    return '%02d (24h)' % hour


def _decode_alarm_sec_min(byte, label):
    mask = (byte >> 7) & 1
    return '%s=%02d M%s=%d' % (label, _bcd(byte & 0x7F), label[0], mask)


def _decode_alarm_hours(byte):
    mask = (byte >> 7) & 1
    return 'Hours=%s M=%d' % (_decode_hours(byte & 0x7F), mask)


def _decode_alarm_day_date(byte):
    mask = (byte >> 7) & 1
    dydt = (byte >> 6) & 1
    kind = 'Day' if dydt else 'Date'
    return '%s=%02d M=%d DY/DT=%d' % (kind, _bcd(byte & 0x3F), mask, dydt)


def _decode_control(byte):
    rates = {0: '1Hz', 1: '1.024kHz', 2: '4.096kHz', 3: '8.192kHz'}
    return ('EOSC=%d BBSQW=%d CONV=%d RS=%s INTCN=%d A2IE=%d A1IE=%d'
            % ((byte >> 7) & 1, (byte >> 6) & 1, (byte >> 5) & 1,
               rates[(byte >> 3) & 0x3], (byte >> 2) & 1,
               (byte >> 1) & 1, byte & 1))


def _decode_status(byte):
    return ('OSF=%d EN32kHz=%d BSY=%d A2F=%d A1F=%d'
            % ((byte >> 7) & 1, (byte >> 3) & 1, (byte >> 2) & 1,
               (byte >> 1) & 1, byte & 1))


def _decode_aging(byte):
    return 'offset=%d' % (byte - 256 if byte & 0x80 else byte)


def _decode_temp_msb(byte):
    signed = byte - 256 if byte & 0x80 else byte
    return 'integer=%d C' % signed


def _decode_value(reg, byte):
    """Register-specific decode text for every register except CONTROL_STATUS
    (0x0F, routed to the dedicated status row by the caller)."""
    if reg == 0x00:
        return 'Seconds=%02d' % _bcd(byte & 0x7F)
    if reg == 0x01:
        return 'Minutes=%02d' % _bcd(byte & 0x7F)
    if reg == 0x02:
        return 'Hours=%s' % _decode_hours(byte)
    if reg == 0x03:
        return 'Day=%d' % (byte & 0x07)
    if reg == 0x04:
        return 'Date=%02d' % _bcd(byte & 0x3F)
    if reg == 0x05:
        return 'Month=%02d Century=%d' % (_bcd(byte & 0x1F), (byte >> 7) & 1)
    if reg == 0x06:
        return 'Year=%02d' % _bcd(byte)
    if reg == 0x07:
        return _decode_alarm_sec_min(byte, 'Seconds')
    if reg == 0x08:
        return _decode_alarm_sec_min(byte, 'Minutes')
    if reg in (0x09, 0x0C):
        return _decode_alarm_hours(byte)
    if reg in (0x0A, 0x0D):
        return _decode_alarm_day_date(byte)
    if reg == 0x0B:
        return _decode_alarm_sec_min(byte, 'Minutes')
    if reg == 0x0E:
        return _decode_control(byte)
    if reg == 0x10:
        return _decode_aging(byte)
    if reg == 0x11:
        return _decode_temp_msb(byte)
    if reg == 0x12:
        return 'fractional=%.2f C (bits 7:6)' % (((byte >> 6) & 0x3) * 0.25)
    return '0x%02X' % byte


class Decoder(srd.Decoder):
    api_version = 3
    id = 'ds3231'
    name = 'DS3231'
    longname = 'DS3231 extremely accurate I2C RTC/TCXO/crystal'
    desc = 'Decode DS3231 I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ds3231']
    tags = ['IC', 'Clock/timing']

    annotations = (
        ('data',              'Register data'),
        ('status',            'Control/status register'),
        ('warning',           'Warning'),
        ('temp-conv-start',   'Temperature conversion start'),
        ('temp-conv-done',    'Temperature conversion done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_TEMP_CONV_START, ANN_TEMP_CONV_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.mode = 'IDLE'   # IDLE | WRITE_PENDING_REG | WRITE_STREAM | READ_STREAM | IGNORE
        self.ptr = None       # current register pointer for the active stream
        self.saved_ptr = None  # register pointer remembered across STOP/START (datasheet:
                                # a read with no preceding pointer write resumes from here)
        self._pending_temp_msb = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, 'WARN']])

    @staticmethod
    def _next_ptr(reg):
        return 0x00 if reg >= LAST_REG else reg + 1

    def _emit_write(self, ss, es, reg, byte):
        if reg not in REGISTERS:
            self._warn(ss, es, 'Write to undefined register 0x%02X' % reg)
            return
        if reg in READONLY:
            self._warn(ss, es, 'Write to read-only register %s (0x%02X)' % (REGISTERS[reg], reg))
            return
        name = REGISTERS[reg]
        if reg == 0x0F:
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['W %s [0x%02X] %s' % (name, reg, _decode_status(byte)),
                                    'W status 0x%02X' % byte]])
            return
        text = _decode_value(reg, byte)
        self.put(ss, es, self.out_ann,
                 [ANN_DATA, ['W %s [0x%02X] %s' % (name, reg, text), 'W 0x%02X' % reg]])
        if reg == 0x0E and (byte & 0x20):
            self.put(ss, es, self.out_ann,
                     [ANN_TEMP_CONV_START,
                      ['temp_conversion_ready_start: CONTROL CONV bit (0x0E bit 5) written 1, '
                       'conversion begins',
                       'temp_conversion_ready_start',
                       '→CONV']])

    def _emit_read(self, ss, es, reg, byte):
        if reg not in REGISTERS:
            self._warn(ss, es, 'Read of undefined register 0x%02X' % reg)
            return
        name = REGISTERS[reg]
        if reg == 0x0F:
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['R %s [0x%02X] %s' % (name, reg, _decode_status(byte)),
                                    'R status 0x%02X' % byte]])
            if not (byte & 0x04):  # BSY clear
                self.put(ss, es, self.out_ann,
                         [ANN_TEMP_CONV_DONE,
                          ['temp_conversion_ready_done: CONTROL_STATUS BSY bit (0x0F bit 2) '
                           'read 0, conversion complete',
                           'temp_conversion_ready_done',
                           'BSY✓']])
            return

        text = _decode_value(reg, byte)
        self.put(ss, es, self.out_ann,
                 [ANN_DATA, ['R %s [0x%02X] %s' % (name, reg, text), 'R 0x%02X' % reg]])

        if reg == 0x11:
            self._pending_temp_msb = byte - 256 if byte & 0x80 else byte
        elif reg == 0x12 and self._pending_temp_msb is not None:
            temp_c = self._pending_temp_msb + ((byte >> 6) & 0x3) * 0.25
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['Temperature=%.2f C' % temp_c, 'T=%.2f C' % temp_c]])
            self._pending_temp_msb = None
        else:
            self._pending_temp_msb = None

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.mode = 'IDLE'

        elif ptype == 'START REPEAT':
            self.mode = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata in ADDRS:
                self.mode = 'WRITE_PENDING_REG'
            else:
                self.mode = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata in ADDRS:
                self.ptr = self.saved_ptr if self.saved_ptr is not None else 0x00
                self.mode = 'READ_STREAM'
            else:
                self.mode = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.mode == 'WRITE_PENDING_REG':
                self.ptr = pdata
                self.saved_ptr = self.ptr
                self.mode = 'WRITE_STREAM'
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Pointer → %s (0x%02X)'
                                     % (REGISTERS.get(pdata, '0x%02X' % pdata), pdata),
                                     'PTR 0x%02X' % pdata]])
            elif self.mode == 'WRITE_STREAM':
                self._emit_write(ss, es, self.ptr, pdata)
                self.saved_ptr = self.ptr
                self.ptr = self._next_ptr(self.ptr)

        elif ptype == 'DATA READ':
            if self.mode == 'READ_STREAM':
                self._emit_read(ss, es, self.ptr, pdata)
                self.saved_ptr = self.ptr
                self.ptr = self._next_ptr(self.ptr)

        elif ptype == 'STOP':
            self.mode = 'IDLE'
            self._pending_temp_msb = None
