import sigrokdecode as srd

ADDRS = {0x5A, 0x5B}

REGS = {
    0x00: 'status', 0x01: 'meas_mode', 0x02: 'alg_result_data', 0x03: 'raw_data', 0x05: 'env_data',
    0x06: 'ntc', 0x10: 'thresholds', 0x11: 'baseline', 0x20: 'hw_id', 0x21: 'hw_version',
    0x23: 'fw_boot_version', 0x24: 'fw_app_version', 0xE0: 'error_id', 0xF1: 'app_erase',
    0xF2: 'app_data', 0xF3: 'app_verify', 0xF4: 'app_start', 0xFF: 'sw_reset',
}
# Register sizes in bytes (a read must not exceed them; ALG_RESULT_DATA may be read as 2..8)
SIZES = {0x00: 1, 0x01: 1, 0x02: 8, 0x03: 2, 0x05: 4, 0x06: 4, 0x10: 5, 0x11: 2, 0x20: 1, 0x21: 1,
         0x23: 2, 0x24: 2, 0xE0: 1, 0xF1: 4, 0xF2: 9, 0xF3: 0, 0xF4: 0, 0xFF: 4}
READ_ONLY = {0x00, 0x02, 0x03, 0x06, 0x20, 0x21, 0x23, 0x24, 0xE0}
WRITE_ONLY = {0x05, 0x10, 0xF1, 0xF2, 0xF3, 0xF4, 0xFF}
RESET_KEY = [0x11, 0xE5, 0x72, 0x8A]
ERASE_KEY = [0xE7, 0xA7, 0xE6, 0x09]
MODES = {0: 'idle', 1: 'mode 1 (1 s)', 2: 'mode 2 (10 s)', 3: 'mode 3 (60 s)', 4: 'mode 4 (250 ms, raw only)'}
ERRORS = ((0, 'WRITE_REG_INVALID'), (1, 'READ_REG_INVALID'), (2, 'MEASMODE_INVALID'),
          (3, 'MAX_RESISTANCE'), (4, 'HEATER_FAULT'), (5, 'HEATER_SUPPLY'))

ANN_REG_WRITE  = 0
ANN_REG_READ   = 1
ANN_RESULT     = 2
ANN_COMMAND    = 3
ANN_PTR_WRITE  = 4
ANN_STATUS     = 5
ANN_WARNING    = 6


def _u16(b, i):
    return (b[i] << 8) | b[i + 1]


def _decode_status(v):
    flags = [n for bit, n in ((7, 'FW_MODE app'), (6, 'APP_ERASE_DONE'), (4, 'APP_VALID'),
                              (3, 'DATA_READY'), (0, 'ERROR')) if (v >> bit) & 1]
    if not v & 0x80:
        flags.insert(0, 'boot mode')
    return 'STATUS 0x%02X: %s' % (v, ', '.join(flags) if flags else 'none')


def _decode_meas_mode(v):
    mode = (v >> 4) & 7
    parts = [MODES.get(mode, 'invalid drive mode %d' % mode)]
    if v & 0x08:
        parts.append('INT_DATARDY')
    if v & 0x04:
        parts.append('INT_THRESH')
    return 'MEAS_MODE 0x%02X: %s' % (v, ', '.join(parts))


def _decode_error_id(v):
    names = [n for bit, n in ERRORS if (v >> bit) & 1]
    return 'ERROR_ID 0x%02X: %s' % (v, ', '.join(names) if names else 'none')


def _decode_raw(b):
    raw = _u16(b, 0)
    return 'RAW_DATA: %d uA, %.3f V' % (raw >> 10, (raw & 0x3FF) * 1.65 / 1023)


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'hw_id' in m:
        return 'ID?'
    if 'read-only' in m or 'write-only' in m:
        return 'RW?'
    if 'longer' in m:
        return 'LEN?'
    if 'boot mode' in m:
        return 'BOOT?'
    if 'key' in m:
        return 'KEY?'
    if 'error' in m:
        return 'ERR'
    return 'WARN'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); if a call site supplied
    fewer, derive the missing tier from the existing text so a narrow PulseView zoom still has
    something to show."""
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
    id = 'ccs811'
    name = 'CCS811'
    longname = 'ScioSense CCS811 indoor air quality gas sensor'
    desc = 'Decode CCS811 I2C register accesses: eCO2/TVOC results, raw data, environment, NTC, thresholds, baseline, versions and boot commands.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ccs811']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write',  'Register write'),
        ('reg-read',   'Register read'),
        ('result',     'Measurement result'),
        ('command',    'Boot / reset command'),
        ('ptr-write',  'Register pointer write'),
        ('status',     'Status flags'),
        ('warning',    'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_RESULT, ANN_COMMAND, ANN_PTR_WRITE)),
        ('status',   'Status',   (ANN_STATUS,)),
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
        ('REG_READ' | 'REG_WRITE', (register, bytes))."""
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
        if reg is not None:
            super().put(ss, es, out_py, ('REG_' + rw, (reg, buf)))

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
        self.fw_mode  = None      # True (application) / False (boot) from the last STATUS seen

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _reg_name(self, reg):
        return REGS.get(reg, 'reg[0x%02X]' % reg)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return
        reg = self.reg_ptr
        if reg is None:
            return
        if self.is_read:
            self._finish_read(reg)
        else:
            self._finish_write(reg)

    def _status_seen(self, ss, es, v):
        self.fw_mode = bool(v & 0x80)
        self.put(ss, es, self.out_ann, [ANN_STATUS, [_decode_status(v), 'STATUS 0x%02X' % v]])
        if v & 0x01:
            self._warn(ss, es, 'STATUS.ERROR set (read ERROR_ID)')

    def _finish_read(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        if not buf:
            return
        size = SIZES.get(reg)
        if size is not None and len(buf) > size:
            self._warn(ss, es, 'Read of %d bytes is longer than %s (%d bytes)' % (len(buf), self._reg_name(reg), size))
        if reg in WRITE_ONLY:
            self._warn(ss, es, 'Read of write-only register %s' % self._reg_name(reg))
        if reg == 0x02:
            parts = []
            if len(buf) >= 2:
                parts.append('eCO2 %d ppm' % _u16(buf, 0))
            if len(buf) >= 4:
                parts.append('TVOC %d ppb' % _u16(buf, 2))
            short = parts[0] if parts else 'RESULT'
            if len(buf) >= 6:
                parts.append(_decode_error_id(buf[5]))
            if len(buf) >= 8:
                parts.append(_decode_raw(buf[6:8]))
            self.put(ss, es, self.out_ann, [ANN_RESULT, ['Result: ' + ', '.join(parts), short, 'RES']])
            if len(buf) >= 5:
                self._status_seen(ss, es, buf[4])
            if len(buf) >= 6 and buf[5]:
                self._warn(ss, es, 'Sensor error: ' + _decode_error_id(buf[5]))
            return
        if reg == 0x03 and len(buf) >= 2:
            self.put(ss, es, self.out_ann, [ANN_RESULT, [_decode_raw(buf), 'RAW', 'RAW']])
            return
        if reg == 0x06 and len(buf) >= 4:
            v_ref, v_ntc = _u16(buf, 0), _u16(buf, 2)
            ohm = ' (R_NTC = %.4f x R_REF)' % (v_ntc / v_ref) if v_ref else ''
            self.put(ss, es, self.out_ann, [ANN_RESULT, ['NTC: V_REF %d mV, V_NTC %d mV%s' % (v_ref, v_ntc, ohm), 'NTC', 'NTC']])
            return
        if reg == 0x11 and len(buf) >= 2:
            self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read BASELINE 0x%04X' % _u16(buf, 0), 'BASELINE', 'BL']])
            return
        if reg in (0x23, 0x24) and len(buf) >= 2:
            kind = 'boot' if reg == 0x23 else 'application'
            self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read %s firmware version %d.%d.%d' % (kind, buf[0] >> 4, buf[0] & 0x0F, buf[1]),
                                                           'FW %d.%d.%d' % (buf[0] >> 4, buf[0] & 0x0F, buf[1]), 'FW']])
            return
        v = buf[0]
        if reg == 0x00:
            self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read ' + _decode_status(v), 'R STATUS']])
            self._status_seen(ss, es, v)
            return
        if reg == 0x01:
            text = _decode_meas_mode(v)
        elif reg == 0xE0:
            text = _decode_error_id(v)
            if v:
                self._warn(ss, es, 'Sensor error: ' + text)
        elif reg == 0x20:
            text = 'HW_ID 0x%02X (%s)' % (v, 'CCS81x' if v == 0x81 else 'unexpected')
            if v != 0x81:
                self._warn(ss, es, 'Unexpected HW_ID: 0x%02X' % v)
        elif reg == 0x21:
            text = 'HW_VERSION 0x%02X' % v
        else:
            text = '%s 0x%02X' % (self._reg_name(reg), v)
        self.put(ss, es, self.out_ann, [ANN_REG_READ, ['Read ' + text, 'R 0x%02X' % reg]])

    def _finish_write(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        if reg == 0xF4 and not buf:
            self.put(ss, es, self.out_ann, [ANN_COMMAND, ['APP_START: boot -> application mode', 'APP_START', 'START']])
            self.fw_mode = True
            return
        if reg == 0xF3 and not buf:
            self.put(ss, es, self.out_ann, [ANN_COMMAND, ['APP_VERIFY', 'VERIFY', 'VFY']])
            return
        if not buf:
            self.put(ss, es, self.out_ann,
                     [ANN_PTR_WRITE, ['Pointer -> %s (0x%02X)' % (self._reg_name(reg), reg), 'PTR 0x%02X' % reg]])
            return
        if reg in READ_ONLY:
            self._warn(ss, es, 'Write to read-only register %s' % self._reg_name(reg))
        if reg == 0xFF:
            ok = list(buf) == RESET_KEY
            self.put(ss, es, self.out_ann, [ANN_COMMAND, ['SW_RESET%s' % ('' if ok else ' (wrong key)'), 'SW_RESET', 'RST']])
            if ok:
                self.fw_mode = False
            else:
                self._warn(ss, es, 'SW_RESET with a wrong key: ' + ' '.join('%02X' % b for b in buf))
            return
        if reg == 0xF1:
            ok = list(buf) == ERASE_KEY
            self.put(ss, es, self.out_ann, [ANN_COMMAND, ['APP_ERASE%s' % ('' if ok else ' (wrong key)'), 'APP_ERASE', 'ERASE']])
            if not ok:
                self._warn(ss, es, 'APP_ERASE with a wrong key')
            return
        if reg == 0xF2:
            self.put(ss, es, self.out_ann, [ANN_COMMAND, ['APP_DATA (%d bytes)' % len(buf), 'APP_DATA', 'DATA']])
            return
        if reg == 0x01:
            text = _decode_meas_mode(buf[0])
            if self.fw_mode is False:
                self._warn(ss, es, 'MEAS_MODE written in boot mode (APP_START first)')
            if ((buf[0] >> 4) & 7) > 4:
                self._warn(ss, es, 'Invalid drive mode in MEAS_MODE')
        elif reg == 0x05 and len(buf) >= 4:
            text = 'ENV_DATA: %.2f %%RH, %.2f C' % (_u16(buf, 0) / 512.0, _u16(buf, 2) / 512.0 - 25)
        elif reg == 0x10 and len(buf) >= 5:
            text = 'THRESHOLDS: low/medium %d ppm, medium/high %d ppm, hysteresis %d ppm' % (_u16(buf, 0), _u16(buf, 2), buf[4])
        elif reg == 0x11 and len(buf) >= 2:
            text = 'BASELINE 0x%04X' % _u16(buf, 0)
        else:
            text = '%s %s' % (self._reg_name(reg), ' '.join('%02X' % b for b in buf))
        size = SIZES.get(reg)
        if size is not None and len(buf) > size:
            self._warn(ss, es, 'Write of %d bytes is longer than %s (%d bytes)' % (len(buf), self._reg_name(reg), size))
        self.put(ss, es, self.out_ann, [ANN_REG_WRITE, ['Write ' + text, 'W 0x%02X' % reg]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_DATA_WRITE' and not self.databuf:
                pass   # pointer write followed by a repeated-start read: keep reg_ptr
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
