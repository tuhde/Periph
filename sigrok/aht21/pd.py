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
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CMD_WRITE, ANN_STATUS_READ, ANN_DATA_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.databuf  = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ'):
            return

        if self.is_read:
            if len(self.databuf) == 1:
                status = self.databuf[0]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_STATUS_READ,
                          [_decode_status(status),
                           'S 0x%02X' % status]])
            elif len(self.databuf) >= 6:
                desc = _decode_measurement(self.databuf)
                if desc:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_DATA_READ,
                              ['Measurement: %s' % desc,
                               'M %d bytes' % len(self.databuf)]])
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
                           'W %s' % name]])
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
