import sigrokdecode as srd

ADDRS = set(range(0x48, 0x50))   # A2/A1/A0 = 000..111

AIP_MODE = {
    0: '4 single-ended',
    1: '3 differential',
    2: 'mixed (2 SE + 1 diff)',
    3: '2 differential',
}

ANN_WRITE     = 0
ANN_READ      = 1
ANN_WARNING   = 2


def _adc_voltage(raw, vref=3.3, vagnd=0.0):
    return vagnd + raw * (vref - vagnd) / 256.0


def _signed(raw):
    return raw - 256 if raw >= 128 else raw


class Decoder(srd.Decoder):
    api_version = 3
    id = 'pcf8591'
    name = 'PCF8591'
    longname = 'PCF8591 8-bit I2C ADC + DAC'
    desc = 'Decode PCF8591 control byte / DAC write and ADC read transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pcf8591']
    tags = ['IC', 'ADC']

    annotations = (
        ('write',   'Write'),
        ('read',    'Read'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.control  = None
        self.databuf  = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _decode_control(self, b):
        aoe = (b >> 6) & 1
        aip = (b >> 4) & 0x03
        ai  = (b >> 2) & 1
        chn = b & 0x03
        parts = ['Control 0x%02X' % b]
        parts.append('AOE=%d' % aoe)
        parts.append('AIP=%d (%s)' % (aip, AIP_MODE.get(aip, '?')))
        parts.append('AI=%d' % ai)
        parts.append('CHN=%d' % chn)
        return ' | '.join(parts)

    def _finish_transaction(self):
        if self.state != 'GET_DATA':
            return

        if self.is_read:
            buf = self.databuf
            if not buf:
                self._warn(self.ss_block, self.es, 'Read with no data bytes')
                return
            if len(buf) < 1:
                self._warn(self.ss_block, self.es, 'Read response too short')
                return
            stale = buf[0]
            fresh = buf[1:]
            if self.control is not None:
                chn = self.control & 0x03
                ai  = (self.control >> 2) & 1
            else:
                chn = 0
                ai  = 0
            vals_str = ', '.join('0x%02X' % b for b in fresh)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_READ,
                      ['ADC: stale=0x%02X  fresh=[%s]  (CHN=%d, AI=%d)' %
                       (stale, vals_str, chn, ai),
                       'R stale=0x%02X fresh=[%s]' % (stale, vals_str)]])
        else:
            buf = self.databuf
            if not buf:
                self._warn(self.ss_block, self.es, 'Write with no data bytes')
                return
            self.control = buf[0]
            if len(buf) >= 2:
                dac_value = buf[1]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          ['%s | DAC=0x%02X (%.4f V at 3.3V)' %
                           (self._decode_control(buf[0]), dac_value, _adc_voltage(dac_value)),
                           'W 0x%02X 0x%02X' % (buf[0], dac_value)]])
            else:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          [self._decode_control(buf[0]),
                           'W 0x%02X' % buf[0]]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_DATA':
                pass
            else:
                self._finish_transaction()
                self.databuf  = []
                self.is_read  = False
                self.control  = None
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
            self.state   = 'GET_DATA'

        elif ptype in ('DATA READ', 'DATA WRITE') and self.state == 'GET_DATA':
            self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []
