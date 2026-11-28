import sigrokdecode as srd

ADDRS = {0x69}

COMMANDS = {
    0x00: 'READ', 0x40: 'DATA', 0x50: 'SLEEP', 0x51: 'CLEAN', 0x52: 'RESET', 0x53: 'MAVE',
    0x5A: 'TINTC_H', 0x5B: 'TINTC_L', 0x5C: 'TCLEAN', 0x63: 'SPEEDFAN', 0x71: 'RMOX',
    0x72: 'UID', 0x73: 'ARGVER', 0x74: 'CSTATUS', 0x75: 'FWVER',
}

ANN_COMMAND = 0
ANN_DATA    = 1
ANN_STATUS  = 2
ANN_WARNING = 3

T_RD = 1.0   # s, minimum master access interval


def _hex(data):
    return ' '.join('%02X' % b for b in data)


def _name(cmd):
    return COMMANDS.get(cmd, 'command 0x%02X' % cmd)


def crc8(data):
    crc = 0xFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x31) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def decode_block(b):
    """Decode the 37-byte block into a dict (no CRC check)."""
    def w(i):
        return (b[i] << 8) | b[i + 1]
    t = w(24)
    if t & 0x8000:
        t -= 0x10000
    status = b[0] | (b[1] << 8)
    return {
        'status': status,
        'high_concentration': bool(status & 0x01), 'dust_accumulation': bool(status & 0x02),
        'fan_speed': bool(status & 0x04), 'fan': bool(status & 0x08),
        'nc_0_3': w(2) / 10.0, 'nc_0_5': w(4) / 10.0, 'nc_1': w(6) / 10.0, 'nc_2_5': w(8) / 10.0,
        'nc_4': w(10) / 10.0,
        'pm1': w(12) / 10.0, 'pm2_5': w(14) / 10.0, 'pm10': w(16) / 10.0,
        'pm1_smoke': w(18) / 10.0, 'pm2_5_smoke': w(20) / 10.0, 'pm10_smoke': w(22) / 10.0,
        'temperature_c': t / 100.0, 'humidity_rh': w(26) / 100.0, 'tvoc': w(28) * 10,
        'eco2_ppm': w(30), 'iaq': w(32) / 100.0,
    }


def _status_text(m):
    flags = [n for n, k in (('dust concentration too high', 'high_concentration'),
                            ('dust accumulated', 'dust_accumulation'),
                            ('fan speed out of range', 'fan_speed'),
                            ('fan malfunction', 'fan')) if m[k]]
    return ', '.join(flags) if flags else 'OK'


class Decoder(srd.Decoder):
    api_version = 3
    id = 'rrh62000'
    name = 'RRH62000'
    longname = 'Renesas RRH62000 air-quality module'
    desc = 'Decode RRH62000 I2C commands, the measurement block, settings and identification.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['rrh62000']
    tags = ['IC', 'Sensor']

    annotations = (
        ('command', 'Command'),
        ('data',    'Data'),
        ('status',  'Status'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_COMMAND, ANN_DATA)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.samplerate = None
        self.state = 'IDLE'
        self.is_read = False
        self.databuf = []
        self.ss_block = None
        self.pointer = None       # command byte last written
        self.last_access = None   # sample number of the previous access to the module
        self.tintc_h = None
        self.repeat = False

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _put(self, cls, texts, py=None):
        self.put(self.ss_block, self.es, self.out_ann, [cls, texts])
        if py is not None:
            self.put(self.ss_block, self.es, self.out_python, py)

    def _warn(self, msg, tag):
        self._put(ANN_WARNING, [msg, tag])

    def _check_interval(self):
        if self.samplerate and self.last_access is not None:
            dt = (self.ss_block - self.last_access) / float(self.samplerate)
            if dt < T_RD:
                self._warn('Access %.3f s after the previous one (tRD > 1 s)' % dt, 'tRD')
        self.last_access = self.ss_block

    def _range(self, ok, what):
        if not ok:
            self._warn('%s out of range' % what, 'RANGE')

    def _finish_write(self):
        f = self.databuf
        if not f:
            return
        cmd = f[0]
        self.pointer = cmd
        name = _name(cmd)
        if len(f) == 1:
            self._put(ANN_COMMAND, ['Pointer %s (0x%02X)' % (name, cmd), name, 'P%02X' % cmd], ('WRITE', (cmd, None)))
            return
        v = f[1]
        text = '%s = 0x%02X' % (name, v)
        if cmd == 0x50:
            text = 'SLEEP: %s' % {0x00: 'sleep', 0x80: 'wake up'}.get(v, 'unknown 0x%02X' % v)
            self._range(v in (0x00, 0x80), 'SLEEP value')
        elif cmd == 0x51:
            text = 'CLEAN: start fan cleaning' if v == 0x01 else 'CLEAN = 0x%02X' % v
            self._warn('CLEAN write: fan cleaning started', 'CLEAN')
        elif cmd == 0x52:
            text = 'RESET: module reset' if v == 0x81 else 'RESET = 0x%02X' % v
            self._warn('RESET write: module restarts (1 s until I2C, 3.3 s until valid data)', 'RESET')
        elif cmd == 0x53:
            text = 'MAVE: %d moving averages' % v
            self._range(1 <= v <= 60, 'Moving average (1-60)')
        elif cmd == 0x5A:
            self.tintc_h = v
            text = 'TINTC_H = 0x%02X' % v
        elif cmd == 0x5B:
            if self.tintc_h is not None:
                units = (self.tintc_h << 8) | v
                text = 'TINTC_L = 0x%02X: cleaning interval %d s' % (v, units * 30)
                self._range(units <= 60480, 'Cleaning interval (0-60480 x 30 s)')
            else:
                text = 'TINTC_L = 0x%02X' % v
        elif cmd == 0x5C:
            text = 'TCLEAN: cleaning time %d s' % v
            self._range(v <= 60, 'Cleaning time (0-60 s)')
        elif cmd == 0x63:
            text = 'SPEEDFAN: fan %d %%' % v
            self._range(60 <= v <= 100, 'Fan speed (60-100 %)')
        if len(f) > 2:
            text += ' (+%d extra bytes)' % (len(f) - 2)
        self._put(ANN_COMMAND, [text, '%s=%02X' % (name, v), 'W%02X' % cmd], ('WRITE', (cmd, v)))

    def _finish_read(self):
        d = self.databuf
        if not d:
            return
        cmd = self.pointer if self.pointer is not None else 0x00
        name = _name(cmd)
        py = ('READ', (cmd, bytes(d)))
        if cmd == 0x00 and len(d) == 37:
            if all(b == 0xFF for b in d[:36]):
                self._warn('All-0xFF block: read before the start-up time (3.3 s)', 'START-UP')
                self._put(ANN_DATA, ['READ: invalid block (start-up)', 'READ invalid', 'R!'], py)
                return
            crc_ok = crc8(d[:36]) == d[36]
            m = decode_block(d)
            text = ('READ: PM1 %.1f PM2.5 %.1f PM10 %.1f ug/m3 (KCl), smoke %.1f/%.1f/%.1f ug/m3, '
                    'NC %.1f/%.1f/%.1f/%.1f/%.1f cm-3, %.2f C, %.2f %%RH, TVOC %d ug/m3, eCO2 %d ppm, IAQ %.2f, CRC %s' % (
                        m['pm1'], m['pm2_5'], m['pm10'], m['pm1_smoke'], m['pm2_5_smoke'], m['pm10_smoke'],
                        m['nc_0_3'], m['nc_0_5'], m['nc_1'], m['nc_2_5'], m['nc_4'], m['temperature_c'],
                        m['humidity_rh'], m['tvoc'], m['eco2_ppm'], m['iaq'], 'OK' if crc_ok else 'ERROR'))
            short = 'PM2.5 %.1f IAQ %.2f' % (m['pm2_5'], m['iaq'])
            self._put(ANN_DATA, [text, short, 'READ'], py)
            self._put(ANN_STATUS, ['Status 0x%04X: %s' % (m['status'], _status_text(m)),
                                   'Status 0x%04X' % m['status'], 'S'])
            if m['status'] & 0x0F:
                self._warn('Status: %s' % _status_text(m), 'STATUS')
            if not crc_ok:
                self._warn('CRC error: 0x%02X, expected 0x%02X' % (d[36], crc8(d[:36])), 'CRC')
            else:
                m['crc_ok'] = True
                self.put(self.ss_block, self.es, self.out_python, ('MEASUREMENT', m))
            return
        if cmd == 0x40:
            text = 'DATA: %s' % ('new data' if d[0] == 0x01 else 'no new data')
        elif cmd == 0x50:
            text = 'SLEEP = 0x%02X' % d[0]
        elif cmd == 0x53:
            text = 'MAVE: %d moving averages' % d[0]
        elif cmd == 0x5A and len(d) >= 2:
            text = 'TINTC: cleaning interval %d s' % (((d[0] << 8) | d[1]) * 30)
        elif cmd == 0x5C:
            text = 'TCLEAN: cleaning time %d s' % d[0]
        elif cmd == 0x63:
            text = 'SPEEDFAN: fan %d %%' % d[0]
        elif cmd == 0x71 and len(d) >= 4:
            text = 'RMOX: %d ohm' % ((d[0] << 24) | (d[1] << 16) | (d[2] << 8) | d[3])
        elif cmd == 0x72:
            text = 'UID: %s' % _hex(d)
        elif cmd == 0x73 and len(d) >= 3:
            text = 'ARGVER: %d.%d.%d' % (d[0], d[1], d[2])
        elif cmd == 0x74:
            text = 'CSTATUS: cleaning %s' % ('completed' if d[0] == 0x01 else 'not completed')
        elif cmd == 0x75 and len(d) >= 2:
            text = 'FWVER: %d.%d' % (d[0], d[1])
        else:
            text = '%s: %s' % (name, _hex(d))
        self._put(ANN_DATA, [text, '%s %s' % (name, _hex(d[:4])), 'R%02X' % cmd], py)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ') or self.ss_block is None:
            return
        if self.is_read:
            self._finish_read()
        else:
            self._finish_write()

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf = []
            self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'
            self.repeat = ptype == 'START REPEAT'
        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if self.state != 'GET_ADDR' or pdata not in ADDRS:
                self.state = 'IDLE'
                return
            if not self.repeat:
                self._check_interval()
            self.is_read = ptype == 'ADDRESS READ'
            self.state = 'GET_DATA_READ' if self.is_read else 'GET_DATA_WRITE'
        elif ptype == 'DATA WRITE' and self.state == 'GET_DATA_WRITE':
            self.databuf.append(pdata)
        elif ptype == 'DATA READ' and self.state == 'GET_DATA_READ':
            self.databuf.append(pdata)
        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
