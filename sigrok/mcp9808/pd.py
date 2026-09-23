import sigrokdecode as srd

ADDRS = set(range(0x18, 0x20))

REG_CONFIG = 0x01
REG_TUPPER = 0x02
REG_TLOWER = 0x03
REG_TCRIT = 0x04
REG_TA = 0x05
REG_MFR_ID = 0x06
REG_DEVICE_ID = 0x07
REG_RESOLUTION = 0x08

REGISTERS = {
    REG_CONFIG: 'CONFIG',
    REG_TUPPER: 'TUPPER',
    REG_TLOWER: 'TLOWER',
    REG_TCRIT: 'TCRIT',
    REG_TA: 'TA',
    REG_MFR_ID: 'MANUFACTURER_ID',
    REG_DEVICE_ID: 'DEVICE_ID_REV',
    REG_RESOLUTION: 'RESOLUTION',
}
WRITABLE = (REG_CONFIG, REG_TUPPER, REG_TLOWER, REG_TCRIT, REG_RESOLUTION)

RESOLUTIONS = ('0.5', '0.25', '0.125', '0.0625')
CONV_MS = (30, 65, 130, 250)
HYSTERESES = ('0', '1.5', '3.0', '6.0')

CFG_SHDN = 0x0100
CFG_CRIT_LOCK = 0x0080
CFG_WIN_LOCK = 0x0040
CFG_INT_CLEAR = 0x0020
# Bits frozen by either lock: THYST, ALERT_SEL, ALERT_POL, ALERT_MOD.
CFG_FROZEN_BY_LOCK = 0x0600 | 0x0004 | 0x0002 | 0x0001

ANN_DATA = 0
ANN_STATUS = 1
ANN_WARNING = 2
# Named start/end pair for the register_access conformance check (see
# specs/temperature/mcp9808.md, "Timing Constraints" and "Sigrok Decoder",
# and specs/temperature/mcp9808_timing.conf).
ANN_ACCESS_START = 3
ANN_ACCESS_DONE = 4


def _temperature(raw):
    value = raw & 0x1FFF
    if value & 0x1000:
        value -= 0x2000
    return value / 16.0


def _limit(raw):
    value = (raw >> 2) & 0x3FF
    if raw & 0x1000:
        value -= 1024
    return value / 4.0


def _flags(raw):
    names = []
    if raw & 0x8000:
        names.append('TA>=TCRIT')
    if raw & 0x4000:
        names.append('TA>TUPPER')
    if raw & 0x2000:
        names.append('TA<TLOWER')
    return names


def _config(raw):
    return ('THYST=%s°C SHDN=%d CRIT_LOCK=%d WIN_LOCK=%d INT_CLEAR=%d ALERT_STAT=%d '
            'ALERT_CNT=%d ALERT_SEL=%s ALERT_POL=%s ALERT_MOD=%s'
            % (HYSTERESES[(raw >> 9) & 3], (raw >> 8) & 1, (raw >> 7) & 1, (raw >> 6) & 1,
               (raw >> 5) & 1, (raw >> 4) & 1, (raw >> 3) & 1,
               'TCRIT only' if raw & 0x0004 else 'all',
               'active-high' if raw & 0x0002 else 'active-low',
               'interrupt' if raw & 0x0001 else 'comparator'))


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mcp9808'
    name = 'MCP9808'
    longname = 'MCP9808 ±0.5°C digital temperature sensor'
    desc = 'Decode MCP9808 I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mcp9808']
    tags = ['IC', 'Sensor']

    annotations = (
        ('data',                  'Register value'),
        ('status',                'CONFIG / boundary flags'),
        ('warning',               'Warning'),
        ('register-access-start', 'Register access start'),
        ('register-access-done',  'Register access done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_ACCESS_START, ANN_ACCESS_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'         # IDLE | GET_PTR | WRITE | READ | IGNORE
        self.ptr = None             # Register Pointer, kept across transactions
        self.txn_ss = None
        self.addr_es = None
        self.ours = False
        self.buf = []               # (ss, es, byte) of the current data phase
        self.direction = None
        self.config = None          # last CONFIG value seen on the bus
        self.crit_locked = False    # sticky until the decoder is reset
        self.win_locked = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg, tag):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, tag]])

    def _note_config(self, value):
        self.config = value
        if value & CFG_CRIT_LOCK:
            self.crit_locked = True
        if value & CFG_WIN_LOCK:
            self.win_locked = True

    def _check_locked_write(self, ss, es, reg, value):
        if reg == REG_TCRIT and self.crit_locked:
            self._warn(ss, es, 'Write to TCRIT while CRIT_LOCK is set - ignored by the chip', 'LOCKED')
        elif reg in (REG_TUPPER, REG_TLOWER) and self.win_locked:
            self._warn(ss, es, 'Write to %s while WIN_LOCK is set - ignored by the chip' % REGISTERS[reg],
                       'LOCKED')
        elif reg == REG_CONFIG and (self.crit_locked or self.win_locked):
            old = self.config if self.config is not None else value
            if (value ^ old) & CFG_FROZEN_BY_LOCK:
                self._warn(ss, es, 'CONFIG write changes THYST/ALERT_SEL/ALERT_POL/ALERT_MOD while locked',
                           'LOCKED')
            if value & CFG_SHDN and not old & CFG_SHDN:
                self._warn(ss, es, 'CONFIG write sets SHDN while locked - ignored by the chip', 'LOCKED')
            if (self.crit_locked and not value & CFG_CRIT_LOCK) or (self.win_locked and not value & CFG_WIN_LOCK):
                self._warn(ss, es, 'CONFIG write clears a lock bit - locks only clear on power-on reset',
                           'LOCKED')

    def _emit(self, ss, es, rw, reg, value):
        name = REGISTERS[reg]
        if reg == REG_CONFIG:
            if rw == 'W':
                self._check_locked_write(ss, es, reg, value)
            text = _config(value)
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['%s CONFIG [0x%04X] %s' % (rw, value, text),
                                   '%s CONFIG 0x%04X' % (rw, value),
                                   '%s CFG' % rw]])
            self.put(ss, es, self.out_python, ('status', (rw, reg, value)))
            self._note_config(value & ~CFG_INT_CLEAR)
            return
        if reg == REG_TA:
            t = _temperature(value)
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['R TA [0x%04X] %.4f °C' % (value, t), 'TA %.2f °C' % t, '%.1f°' % t]])
            self.put(ss, es, self.out_python, ('data', (rw, reg, t)))
            flags = _flags(value)
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['TA boundary flags: %s' % (', '.join(flags) or 'inside window'),
                                   'Flags %s' % (','.join(flags) or 'none'),
                                   'F%d' % ((value >> 13) & 7)]])
            self.put(ss, es, self.out_python, ('status', (rw, reg, (value >> 13) & 7)))
            return
        if reg in (REG_TUPPER, REG_TLOWER, REG_TCRIT):
            if rw == 'W':
                self._check_locked_write(ss, es, reg, value)
            c = _limit(value)
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['%s %s [0x%04X] %.2f °C' % (rw, name, value, c),
                                 '%s %s %.2f °C' % (rw, name, c),
                                 '%s%s' % (rw, name[1:3])]])
            self.put(ss, es, self.out_python, ('data', (rw, reg, c)))
            return
        if reg == REG_RESOLUTION:
            code = value & 3
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['%s RESOLUTION [0x%02X] %s °C (t_CONV %d ms)'
                                 % (rw, value, RESOLUTIONS[code], CONV_MS[code]),
                                 '%s RES %s °C' % (rw, RESOLUTIONS[code]),
                                 '%sRES' % rw]])
            self.put(ss, es, self.out_python, ('data', (rw, reg, float(RESOLUTIONS[code]))))
            return
        if reg == REG_MFR_ID:
            ok = 'Microchip' if value == 0x0054 else 'unexpected, expected 0x0054'
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['R MANUFACTURER_ID [0x%04X] %s' % (value, ok), 'MFR 0x%04X' % value, 'MFR']])
        else:
            ok = 'MCP9808' if value >> 8 == 0x04 else 'unexpected, expected 0x04'
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['R DEVICE_ID_REV [0x%04X] device 0x%02X (%s) rev 0x%02X'
                                 % (value, value >> 8, ok, value & 0xFF),
                                 'DEV 0x%02X rev 0x%02X' % (value >> 8, value & 0xFF), 'DEV']])
        self.put(ss, es, self.out_python, ('data', (rw, reg, value)))

    def _flush(self):
        """Annotate the data phase collected since the pointer / read address."""
        buf, self.buf = self.buf, []
        if not buf or self.ptr is None:
            return
        rw = 'W' if self.direction == 'W' else 'R'
        ss, es = buf[0][0], buf[-1][1]
        reg = self.ptr
        if reg not in REGISTERS:
            if rw == 'W':
                self._warn(ss, es, 'Write to %s pointer 0x%02X' % ('reserved' if reg == 0 else 'undefined', reg),
                           'PTR?')
            else:
                self._warn(ss, es, 'Read of %s pointer 0x%02X' % ('reserved' if reg == 0 else 'undefined', reg),
                           'PTR?')
            return
        if rw == 'W' and reg not in WRITABLE:
            self._warn(ss, es, 'Write to read-only %s' % REGISTERS[reg], 'RO')
            return
        width = 1 if reg == REG_RESOLUTION else 2
        if len(buf) != width:
            self._warn(ss, es, '%s of %d byte(s) to %s, expected %d'
                       % ('Write' if rw == 'W' else 'Read', len(buf), REGISTERS[reg], width), 'LEN?')
            if len(buf) < width:
                return
        if width == 2:
            value = (buf[0][2] << 8) | buf[1][2]
            es = buf[1][1]
        else:
            value = buf[0][2]
            es = buf[0][1]
        self._emit(ss, es, rw, reg, value)

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.txn_ss = ss
            self.ours = False
            self.buf = []

        elif ptype == 'START REPEAT':
            # Pointer write, repeated START, read: one register access.
            self._flush()
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata in ADDRS:
                self.state = 'GET_PTR'
                self.direction = 'W'
                if not self.ours:
                    self.ours = True
                    self.addr_es = es
            else:
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata in ADDRS:
                self.state = 'READ'
                self.direction = 'R'
                if not self.ours:
                    self.ours = True
                    self.addr_es = es
            else:
                self.state = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_PTR':
                self.ptr = pdata
                self.state = 'WRITE'
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Pointer → %s (0x%02X)' % (REGISTERS.get(pdata, 'undefined'), pdata),
                                     'PTR 0x%02X' % pdata,
                                     'P%02X' % pdata]])
            elif self.state == 'WRITE':
                self.buf.append((ss, es, pdata))

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.buf.append((ss, es, pdata))

        elif ptype == 'STOP':
            self._flush()
            if self.ours and self.txn_ss is not None:
                # register_access: START of a transaction addressed to the
                # MCP9808 through its STOP.
                self.put(self.txn_ss, self.addr_es, self.out_ann,
                         [ANN_ACCESS_START,
                          ['register_access_start: START of an MCP9808 register access',
                           'register_access_start',
                           '→RA']])
                self.put(ss, es, self.out_ann,
                         [ANN_ACCESS_DONE,
                          ['register_access_done: STOP ended the MCP9808 register access',
                           'register_access_done',
                           'RA✓']])
            self.state = 'IDLE'
            self.ours = False
            self.txn_ss = None
