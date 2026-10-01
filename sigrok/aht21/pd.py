import sigrokdecode as srd

ADDRS = {0x38}

CMDS = {
    0xAC: 'Trigger Measurement',
    0xBA: 'Soft Reset',
    0x1B: 'Cal Init 1',
    0x1C: 'Cal Init 2',
    0x1E: 'Cal Init 3',
}

ANN_CMD_WRITE   = 0
ANN_STATUS_READ = 1
ANN_DATA_READ   = 2
ANN_WARNING     = 3
ANN_STATUS      = 4
ANN_TRIGGER_START = 5
ANN_TRIGGER_DONE  = 6
ANN_POWERON_READY = 7


def _decode_status(byte):
    busy = 'BUSY' if (byte & 0x80) else 'IDLE'
    cal  = 'CAL' if (byte & 0x08) else 'UNCAL'
    return 'Status 0x%02X (%s, %s)' % (byte, busy, cal)


def _decode_measurement(data):
    if len(data) < 6:
        return None
    status = data[0]
    raw_rh = (data[1] << 12) | (data[2] << 4) | (data[3] >> 4)
    raw_t  = ((data[3] & 0x0F) << 16) | (data[4] << 8) | data[5]
    rh_pct = (raw_rh / 1048576.0) * 100.0
    temp_c = (raw_t  / 1048576.0) * 200.0 - 50.0
    parts = [
        'Status=0x%02X' % status,
        'RH=%.2f %%RH' % rh_pct,
        'T=%.2f °C' % temp_c,
    ]
    if len(data) == 7:
        parts.append('CRC=0x%02X' % data[6])
    return ', '.join(parts)


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'chip id' in m or 'device id' in m:
        return 'ID?'
    if 'address' in m:
        return 'ADDR?'
    if any(k in m for k in ('length', 'byte', 'short', 'missing', 'expected', 'no data', 'empty')):
        return 'LEN?'
    if any(k in m for k in ('unknown', 'unexpected', 'invalid', 'undefined', 'reserved', 'out of range')):
        return 'BAD?'
    return 'WARN'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); if a
    call site supplied fewer, derive the missing tier from the existing text
    (leading name token, <= 8 chars when it becomes the shortest tier) so a
    narrow PulseView zoom still has something to show."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings

    def lead(text):
        text = text.strip()
        n = 0
        while n < len(text) and (text[n].isalnum() or text[n] in '_\u2192'):
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
    id = 'aht21'
    name = 'AHT21'
    longname = 'AHT21 temperature and humidity sensor'
    desc = 'Decode AHT21 I2C temperature/humidity sensor transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['aht21']
    tags = ['IC', 'Sensor']

    annotations = (
        ('cmd-write',   'Command write'),
        ('status-read', 'Status read'),
        ('data-read',   'Measurement data read'),
        ('warning',     'Warning'),
        ('state',       'Busy / calibration state'),
        ('trigger-start', 'Measurement trigger written'),
        ('trigger-done',  'Measurement finished (status IDLE)'),
        ('poweron-ready', 'First transaction after power-on'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CMD_WRITE, ANN_STATUS_READ, ANN_DATA_READ)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_TRIGGER_START, ANN_TRIGGER_DONE, ANN_POWERON_READY)),
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
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.databuf  = []
        self.ss_block = None
        self.seen_first = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _emit_state(self, status):
        busy = bool(status & 0x80)
        cal = bool(status & 0x08)
        text = '%s, %s' % ('BUSY' if busy else 'IDLE', 'CAL' if cal else 'UNCAL')
        self.put(self.ss_block, self.es, self.out_ann,
                 [ANN_STATUS, [text, 'BUSY' if busy else 'IDLE', 'B' if busy else 'I']])
        self.put(self.ss_block, self.es, self.out_python, ('STATUS', (status, busy, cal)))

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ'):
            return
        if not self.seen_first:
            self.seen_first = True
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_POWERON_READY, ['poweron_ready: first transaction after power-on',
                                          'poweron_ready', '\u26a1']])

        if self.is_read:
            if len(self.databuf) == 1:
                status = self.databuf[0]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_STATUS_READ,
                          [_decode_status(status),
                           'Status 0x%02X' % status,
                           'S %02X' % status]])
                self._emit_state(status)
                if not status & 0x80:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_TRIGGER_DONE, ['measurement_trigger_done: status read reports IDLE',
                                                 'measurement_trigger_done', 'DONE']])
            elif len(self.databuf) >= 6:
                desc = _decode_measurement(self.databuf)
                if desc:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_DATA_READ,
                              ['Measurement: %s' % desc,
                               'M %s' % desc.split(', ', 1)[1],
                               'M %dB' % len(self.databuf)]])
                    self._emit_state(self.databuf[0])
                    d = self.databuf
                    raw_rh = (d[1] << 12) | (d[2] << 4) | (d[3] >> 4)
                    raw_t = ((d[3] & 0x0F) << 16) | (d[4] << 8) | d[5]
                    self.put(self.ss_block, self.es, self.out_python,
                             ('MEASUREMENT', (raw_rh / 1048576.0 * 100.0, raw_t / 1048576.0 * 200.0 - 50.0)))
                else:
                    self._warn(self.ss_block, self.es,
                               'Unexpected read length %d' % len(self.databuf))
            elif self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d' % len(self.databuf))
        else:
            if self.databuf:
                cmd = self.databuf[0]
                name = CMDS.get(cmd, 'Unknown 0x%02X' % cmd)
                params = ' '.join('0x%02X' % b for b in self.databuf[1:])
                desc = '%s' % name
                if params:
                    desc += ' [%s]' % params
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_CMD_WRITE,
                          ['Write %s' % desc,
                           'W %s' % name,
                           'W %02X' % cmd]])
                self.put(self.ss_block, self.es, self.out_python,
                         ('CMD', (cmd, tuple(self.databuf[1:]))))
                if cmd == 0xAC:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_TRIGGER_START, ['measurement_trigger_start: Trigger Measurement written',
                                                  'measurement_trigger_start', 'TRIG']])
            else:
                self._warn(self.ss_block, self.es, 'Empty write')

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf  = []
            self.is_read  = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = addr
            self.is_read = (ptype == 'ADDRESS READ')
            self.databuf = []
            self.state   = 'GET_DATA_READ' if self.is_read else 'GET_DATA_WRITE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []
