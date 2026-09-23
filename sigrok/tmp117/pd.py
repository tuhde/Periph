import sigrokdecode as srd

ADDRS = set(range(0x48, 0x4C))

REG_TEMP_RESULT = 0x00
REG_CONFIG = 0x01
REG_THIGH = 0x02
REG_TLOW = 0x03
REG_EEPROM_UL = 0x04
REG_EEPROM1 = 0x05
REG_EEPROM2 = 0x06
REG_TEMP_OFFSET = 0x07
REG_EEPROM3 = 0x08
REG_DEVICE_ID = 0x0F

REGISTERS = {
    REG_TEMP_RESULT: 'TEMP_RESULT',
    REG_CONFIG: 'CONFIGURATION',
    REG_THIGH: 'THIGH_LIMIT',
    REG_TLOW: 'TLOW_LIMIT',
    REG_EEPROM_UL: 'EEPROM_UL',
    REG_EEPROM1: 'EEPROM1',
    REG_EEPROM2: 'EEPROM2',
    REG_TEMP_OFFSET: 'TEMP_OFFSET',
    REG_EEPROM3: 'EEPROM3',
    REG_DEVICE_ID: 'DEVICE_ID',
}
READ_ONLY = (REG_TEMP_RESULT, REG_DEVICE_ID)
TEMPERATURE_REGS = (REG_TEMP_RESULT, REG_THIGH, REG_TLOW, REG_TEMP_OFFSET)
SCRATCH_REGS = (REG_EEPROM1, REG_EEPROM2, REG_EEPROM3)
FACTORY_REGS = (REG_EEPROM1, REG_EEPROM3)
# Registers whose writes also program the EEPROM while EUN=1.
EEPROM_BACKED = (REG_CONFIG, REG_THIGH, REG_TLOW, REG_TEMP_OFFSET, REG_EEPROM1, REG_EEPROM2, REG_EEPROM3)

MODES = ('continuous', 'shutdown', 'continuous', 'one-shot')
CYCLES = ('15.5 ms', '125 ms', '250 ms', '500 ms', '1 s', '4 s', '8 s', '16 s')
AVERAGINGS = ('none', '8', '32', '64')

EUN = 0x8000
UL_EEPROM_BUSY = 0x4000
CFG_EEPROM_BUSY = 0x1000

ANN_DATA = 0
ANN_STATUS = 1
ANN_WARNING = 2
# Named start/end pair for the eeprom_write_ready conformance check (see
# specs/temperature/tmp117.md, "Timing Constraints" and "Sigrok Decoder",
# and specs/temperature/tmp117_timing.conf).
ANN_EEPROM_START = 3
ANN_EEPROM_DONE = 4


def _temperature(raw):
    value = raw - 0x10000 if raw & 0x8000 else raw
    return value * 0.0078125


def _config(raw):
    return ('HIGH_Alert=%d LOW_Alert=%d Data_Ready=%d EEPROM_Busy=%d MOD=%s CONV=%s AVG=%s '
            'T/nA=%s POL=%s DR/Alert=%s Soft_Reset=%d'
            % ((raw >> 15) & 1, (raw >> 14) & 1, (raw >> 13) & 1, (raw >> 12) & 1,
               MODES[(raw >> 10) & 3], CYCLES[(raw >> 7) & 7], AVERAGINGS[(raw >> 5) & 3],
               'therm' if raw & 0x0010 else 'alert',
               'active-high' if raw & 0x0008 else 'active-low',
               'data-ready' if raw & 0x0004 else 'alert',
               (raw >> 1) & 1))


class Decoder(srd.Decoder):
    api_version = 3
    id = 'tmp117'
    name = 'TMP117'
    longname = 'TMP117 ±0.1°C digital temperature sensor'
    desc = 'Decode TMP117 I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['tmp117']
    tags = ['IC', 'Sensor']

    annotations = (
        ('data',                      'Register value'),
        ('status',                    'CONFIGURATION / EEPROM_UL'),
        ('warning',                   'Warning'),
        ('eeprom-write-ready-start',  'EEPROM write ready start'),
        ('eeprom-write-ready-done',   'EEPROM write ready done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_EEPROM_START, ANN_EEPROM_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'         # IDLE | GET_PTR | WRITE | READ | IGNORE
        self.ptr = None             # Register Pointer, kept across transactions
        self.buf = []               # (ss, es, byte) of the current data phase
        self.direction = None
        self.unlocked = False       # EUN as last written on the bus
        self.eeprom_pending = False # an EEPROM write awaits EEPROM_Busy=0

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg, tag):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, tag]])

    def _eeprom_write(self, ss, es, reg):
        if not self.unlocked or reg not in EEPROM_BACKED:
            return
        if reg in FACTORY_REGS:
            self._warn(ss, es, 'Write to %s while EEPROM unlocked - destroys the factory unique ID'
                       % REGISTERS[reg], 'NIST!')
        self.put(ss, es, self.out_ann,
                 [ANN_EEPROM_START,
                  ['eeprom_write_ready_start: %s written while EUN=1' % REGISTERS[reg],
                   'eeprom_write_ready_start',
                   '→EE']])
        self.eeprom_pending = True

    def _eeprom_busy_read(self, ss, es, busy):
        if self.eeprom_pending and not busy:
            self.put(ss, es, self.out_ann,
                     [ANN_EEPROM_DONE,
                      ['eeprom_write_ready_done: EEPROM_Busy read back 0',
                       'eeprom_write_ready_done',
                       'EE✓']])
            self.eeprom_pending = False

    def _emit(self, ss, es, rw, reg, value):
        name = REGISTERS[reg]
        if rw == 'W':
            self._eeprom_write(ss, es, reg)
        if reg == REG_CONFIG:
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['%s CONFIGURATION [0x%04X] %s' % (rw, value, _config(value)),
                                   '%s CONFIG 0x%04X' % (rw, value),
                                   '%s CFG' % rw]])
            self.put(ss, es, self.out_python, ('status', (rw, reg, value)))
            if rw == 'R':
                self._eeprom_busy_read(ss, es, value & CFG_EEPROM_BUSY)
            return
        if reg == REG_EEPROM_UL:
            if rw == 'W':
                self.unlocked = bool(value & EUN)
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['%s EEPROM_UL [0x%04X] EUN=%d EEPROM_Busy=%d'
                                   % (rw, value, (value >> 15) & 1, (value >> 14) & 1),
                                   '%s EEPROM_UL 0x%04X' % (rw, value),
                                   '%s UL' % rw]])
            self.put(ss, es, self.out_python, ('status', (rw, reg, value)))
            if rw == 'R':
                self._eeprom_busy_read(ss, es, value & UL_EEPROM_BUSY)
            return
        if reg in TEMPERATURE_REGS:
            c = _temperature(value)
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['%s %s [0x%04X] %.4f °C' % (rw, name, value, c),
                                 '%s %s %.2f °C' % (rw, name, c),
                                 '%.1f°' % c]])
            self.put(ss, es, self.out_python, ('data', (rw, reg, c)))
            return
        if reg in SCRATCH_REGS:
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['%s %s [0x%04X]' % (rw, name, value), '%s %s' % (rw, name), '%sEE' % rw]])
        else:
            ok = 'TMP117' if value & 0x0FFF == 0x117 else 'unexpected, expected 0x117'
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['R DEVICE_ID [0x%04X] device 0x%03X (%s) rev %d'
                                 % (value, value & 0x0FFF, ok, value >> 12),
                                 'DID 0x%03X rev %d' % (value & 0x0FFF, value >> 12), 'DID']])
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
            self._warn(ss, es, '%s of undefined pointer 0x%02X' % ('Write' if rw == 'W' else 'Read', reg),
                       'PTR?')
            return
        if rw == 'W' and reg in READ_ONLY:
            self._warn(ss, es, 'Write to read-only %s' % REGISTERS[reg], 'RO')
            return
        if len(buf) != 2:
            self._warn(ss, es, '%s of %d byte(s) to %s, expected 2'
                       % ('Write' if rw == 'W' else 'Read', len(buf), REGISTERS[reg]), 'LEN?')
            if len(buf) < 2:
                return
        value = (buf[0][2] << 8) | buf[1][2]
        self._emit(ss, buf[1][1], rw, reg, value)

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.buf = []

        elif ptype == 'START REPEAT':
            # Pointer write, repeated START, read: one register access.
            self._flush()
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata in ADDRS:
                self.state = 'GET_PTR'
                self.direction = 'W'
            else:
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata in ADDRS:
                self.state = 'READ'
                self.direction = 'R'
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
            self.state = 'IDLE'
