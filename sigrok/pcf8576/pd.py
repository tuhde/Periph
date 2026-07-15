import sigrokdecode as srd

ADDRS = {0x38, 0x39}

CMD_MODE_SET       = 0x40
CMD_LOAD_PTR       = 0x00
CMD_DEVICE_SELECT  = 0x60
CMD_BANK_SELECT    = 0x78
CMD_BLINK_SELECT   = 0x70

MODE_NAMES = {0: '1:4', 1: 'static', 2: '1:2', 3: '1:3'}
BIAS_NAMES = {0: '1/3 bias', 1: '1/2 bias'}
BLINK_FREQ = {0: 'off', 1: '~2 Hz', 2: '~1 Hz', 3: '~0.5 Hz'}

ANN_CMD = 0
ANN_DATA = 1
ANN_WARNING = 2


class Decoder(srd.Decoder):
    api_version = 3
    id = 'pcf8576'
    name = 'PCF8576'
    longname = 'PCF8576 40x4 universal LCD segment driver'
    desc = 'Decode PCF8576 I2C command-stream transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['pcf8576']
    tags = ['IC', 'Display', 'LCD']

    annotations = (
        ('cmd',     'Command'),
        ('data',    'Display data'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CMD, ANN_DATA)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.cmds     = []
        self.databuf  = []
        self.ss_block = None
        self.ss       = None
        self.es       = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _decode_mode_set(self, raw):
        e = (raw >> 3) & 1
        b = (raw >> 2) & 1
        m = raw & 3
        e_s = 'on' if e else 'off'
        return 'mode-set 0x%02X: E=%s bias=%s mode=%s' % (
            raw, e_s, BIAS_NAMES[b], MODE_NAMES[m])

    def _decode_load_ptr(self, raw):
        return 'load-data-pointer 0x%02X: addr=%d' % (raw, raw & 0x3F)

    def _decode_device_select(self, raw):
        return 'device-select 0x%02X: subaddress=%d' % (raw, raw & 0x07)

    def _decode_bank_select(self, raw):
        i = (raw >> 1) & 1
        o = raw & 1
        return 'bank-select 0x%02X: input=%d output=%d' % (raw, i, o)

    def _decode_blink_select(self, raw):
        ab = (raw >> 2) & 1
        bf = raw & 3
        ab_s = ' (alt bank)' if ab else ''
        return 'blink-select 0x%02X: freq=%s%s' % (raw, BLINK_FREQ[bf], ab_s)

    def _decode_command(self, raw):
        if raw & 0xF0 == CMD_MODE_SET:
            return self._decode_mode_set(raw)
        if raw & 0xC0 == CMD_LOAD_PTR:
            return self._decode_load_ptr(raw)
        if raw & 0xF8 == CMD_DEVICE_SELECT:
            return self._decode_device_select(raw)
        if raw & 0xFC == CMD_BANK_SELECT:
            return self._decode_bank_select(raw)
        if raw & 0xFC == CMD_BLINK_SELECT:
            return self._decode_blink_select(raw)
        return 'unknown command 0x%02X' % raw

    def _finish_transaction(self):
        if self.state not in ('GET_CMD', 'GET_DATA'):
            return
        ss, es = self.ss_block, self.es
        if self.cmds:
            labels = [self._decode_command(c) for c in self.cmds]
            label = 'Cmd: ' + ' → '.join(labels)
            short = 'C' if len(self.cmds) == 1 else 'C(%d)' % len(self.cmds)
            self.put(ss, es, self.out_ann, [ANN_CMD, [label, short]])
        if self.databuf:
            if len(self.databuf) == 1:
                dshort = '0x%02X' % self.databuf[0]
            else:
                dshort = 'data[%d]' % len(self.databuf)
            dlong = 'Display data: ' + ' '.join('0x%02X' % b for b in self.databuf)
            self.put(ss, es, self.out_ann, [ANN_DATA, [dlong, dshort]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.cmds    = []
            self.databuf = []
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self._warn(ss, es, 'Unexpected address 0x%02X' % addr)
                self.state = 'IDLE'
                return
            self.addr = addr
            if ptype == 'ADDRESS READ':
                self._warn(ss, es, 'PCF8576 is write-only; saw ADDRESS READ')
                self.state = 'IDLE'
            else:
                self.state = 'GET_CMD'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_CMD':
                if byte & 0x80:
                    self.cmds.append(byte & 0x7F)
                else:
                    self.cmds.append(byte & 0x7F)
                    self.state = 'GET_DATA'
            elif self.state == 'GET_DATA':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            self._warn(ss, es, 'PCF8576 is write-only; saw DATA READ')

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.cmds    = []
            self.databuf = []
