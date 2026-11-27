import sigrokdecode as srd

ADDRS = {0x38}

COMMANDS = {
    0x00: 'Get Product ID', 0x01: 'Get Tracking Number', 0x10: 'Get Operation Mode',
    0x11: 'Set Operation Mode', 0x12: 'Set Humidity', 0x18: 'Get Measurement Results',
    0x20: 'Config GPIO', 0x21: 'Get GPIO', 0x22: 'Set GPIO', 0x23: 'Clear GPIO',
    0x31: 'Read Debug Data', 0x40: 'Read Flash Shadow', 0x41: 'Write Flash Shadow',
    0x42: 'Write Flash', 0x8F: 'Reset',
}

MODES = {0x00: 'SUSPEND', 0x01: 'IAQ 2nd Gen', 0x02: 'ULP', 0x03: 'SUSPEND', 0x04: 'do not use',
         0x05: 'PBAQ', 0x80: 'sensor cleaning'}

ANN_REQUEST  = 0
ANN_RESPONSE = 1
ANN_STATUS   = 2
ANN_WARNING  = 3


def _hex(data):
    return ' '.join('%02X' % b for b in data)


def _checksum_ok(data):
    return (sum(data) & 0xFF) == 0xFF


def _cmd_name(code):
    return COMMANDS.get(code, 'command 0x%02X' % code)


def _mode_name(m):
    return MODES.get(m, '0x%02X (SUSPEND)' % m)


def _status_text(s):
    if s == 0:
        return 'OK'
    parts = []
    if s & 0x80:
        parts.append('invalid command / read without request')
    if s & 0x40:
        parts.append('request checksum wrong')
    if s & 0x20:
        parts.append('gas sensor NACK')
    if s & 0x10:
        parts.append('not complete')
    code = s & 0x0F
    if code:
        parts.append('mode error 0x%X%s' % (code, ' (sensor damaged)' if code == 0x0A else ''))
    return ', '.join(parts)


def _decode_request(cmd, p):
    if cmd == 0x11 and p:
        return 'mode %s' % _mode_name(p[0])
    if cmd == 0x12 and p:
        return 'humidity %.1f %%RH (code %d)' % (p[0] * 100.0 / 255, p[0])
    if cmd == 0x20 and p:
        return 'outputs: GPIO1 %s, GPIO2 %s' % ('out' if p[0] & 1 else 'in', 'out' if p[0] & 2 else 'in')
    if cmd in (0x22, 0x23) and p:
        return 'mask 0x%02X' % p[0]
    if cmd == 0x40 and p:
        return 'index %d' % p[0]
    if cmd == 0x41 and len(p) >= 2:
        return 'index %d = 0x%02X' % (p[0], p[1])
    return _hex(p) if p else 'no parameters'


def _decode_results(mode, d):
    if not d:
        return 'no data'
    if mode == 0x05 and len(d) >= 5:
        return 'sample %d, TVOC %.3f mg/m3, EtOH %.3f ppm' % (
            d[0], ((d[1] << 8) | d[2]) / 1000.0, ((d[3] << 8) | d[4]) / 1000.0)
    if len(d) >= 9:
        return 'sample %d, IAQ %.1f, TVOC %.2f mg/m3, EtOH %.2f ppm, eCO2 %d ppm, rel. IAQ %d' % (
            d[0], d[1] / 10.0, ((d[2] << 8) | d[3]) / 100.0, ((d[4] << 8) | d[5]) / 100.0,
            (d[6] << 8) | d[7], d[8] * 10)
    return 'sample %d, %s' % (d[0], _hex(d[1:]))


def _decode_response(cmd, mode, d):
    if cmd == 0x00 and len(d) >= 5:
        return 'product ID 0x%02X%02X, firmware %d.%d.%d' % (d[0], d[1], d[2], d[3], d[4])
    if cmd == 0x01:
        return 'tracking number %s' % _hex(d)
    if cmd == 0x10 and d:
        return 'mode %s' % _mode_name(d[0])
    if cmd == 0x18:
        return _decode_results(mode, d)
    if cmd == 0x21 and d:
        return 'GPIO1 %d, GPIO2 %d, GPIO3 %d, GPIO4 %d' % (d[0] & 1, (d[0] >> 1) & 1, (d[0] >> 2) & 1, (d[0] >> 3) & 1)
    return _hex(d) if d else 'no parameters'


class Decoder(srd.Decoder):
    api_version = 3
    id = 'rrh46410'
    name = 'RRH46410'
    longname = 'Renesas RRH46410 indoor air quality sensor'
    desc = 'Decode RRH46410 I2C requests, responses, status bytes and measurement results.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['rrh46410']
    tags = ['IC', 'Sensor']

    annotations = (
        ('request',  'Request'),
        ('response', 'Response'),
        ('status',   'Status byte'),
        ('warning',  'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REQUEST, ANN_RESPONSE)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.is_read = False
        self.databuf = []
        self.ss_block = None
        self.pending = None   # command awaiting its response
        self.mode = 0x00      # operation mode last seen

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _put(self, cls, texts, py=None):
        self.put(self.ss_block, self.es, self.out_ann, [cls, texts])
        if py is not None:
            self.put(self.ss_block, self.es, self.out_python, py)

    def _warn(self, msg, tag):
        self._put(ANN_WARNING, [msg, tag])

    def _finish_write(self):
        f = self.databuf
        if not f:
            return
        cmd, params = f[0], f[1:-1]
        name = _cmd_name(cmd)
        ok = len(f) >= 2 and _checksum_ok(f)
        text = '%s: %s' % (name, _decode_request(cmd, params))
        if not ok:
            text += ' (checksum error)'
            self._warn('Request checksum error (%s)' % name, 'REQ CS')
        self._put(ANN_REQUEST, [text, name, 'C%02X' % cmd], ('REQUEST', (cmd, bytes(params))))
        if cmd == 0x11 and params:
            self.mode = params[0]
        if cmd == 0x8F:
            self._put(ANN_STATUS, ['Reset: no response, up to 500 ms until ready', 'Reset', 'RST'], ('RESET', None))
            self.mode = 0x00
            self.pending = None
            return
        self.pending = cmd

    def _finish_read(self):
        buf = self.databuf
        if not buf:
            return
        if self.pending is None:
            self._warn('Read without a preceding request', 'NO REQ')
        st = buf[0]
        cmd = self.pending if self.pending is not None else -1
        params = buf[1:-1]
        ok = len(buf) >= 2 and _checksum_ok(buf)
        stext = _status_text(st)
        self._put(ANN_STATUS, ['Status 0x%02X: %s' % (st, stext), 'Status 0x%02X' % st, 'S%02X' % st])
        if st:
            self._warn('Status 0x%02X: %s' % (st, stext), 'STATUS')
        if cmd == 0x10 and params and st == 0:
            self.mode = params[0]
        name = _cmd_name(cmd) if cmd >= 0 else 'Unsolicited'
        text = '%s response: %s' % (name, _decode_response(cmd, self.mode, params) if st == 0 else _hex(params))
        if not ok:
            text += ' (checksum error)'
            self._warn('Response checksum error (%s)' % name, 'RESP CS')
        self._put(ANN_RESPONSE, [text, '%s resp' % name, 'R%02X' % (cmd & 0xFF)],
                  ('RESPONSE', (cmd, st, bytes(params))))
        self.pending = None

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
        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if self.state != 'GET_ADDR' or pdata not in ADDRS:
                self.state = 'IDLE'
                return
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
