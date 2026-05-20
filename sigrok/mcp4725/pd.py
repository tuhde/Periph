import sigrokdecode as srd

ADDRS    = {0x60, 0x61}
GC_ADDR  = 0x00   # General Call address

PD_MODE = {0: 'Normal', 1: '1kΩ→GND', 2: '100kΩ→GND', 3: '500kΩ→GND'}

ANN_WRITE   = 0
ANN_READ    = 1
ANN_GC      = 2
ANN_WARNING = 3


def _pd_str(pd):
    return PD_MODE.get(pd, '?')


def _dac_voltage(code, vdd=3.3):
    return code / 4095.0 * vdd


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mcp4725'
    name = 'MCP4725'
    longname = 'MCP4725 12-bit I2C DAC'
    desc = 'Decode MCP4725 I2C DAC write commands and read responses.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mcp4725']
    tags = ['IC', 'DAC']

    annotations = (
        ('write',   'Write command'),
        ('read',    'Read response'),
        ('gc',      'General Call'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ, ANN_GC)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_gc    = False
        self.is_read  = False
        self.databuf  = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _decode_write(self, ss, es):
        buf = self.databuf
        if not buf:
            self._warn(ss, es, 'Write with no data bytes')
            return

        b0 = buf[0]
        c2c1 = (b0 >> 6) & 3
        c0   = (b0 >> 5) & 1

        if c2c1 == 0:
            # Fast Write: 2 bytes — C2 C1=0, PD1 PD0 in bits 5:4, D11-D8 in bits 3:0
            if len(buf) < 2:
                self._warn(ss, es, 'Fast Write: missing second byte')
                return
            pd   = (b0 >> 4) & 3
            code = ((b0 & 0x0F) << 8) | buf[1]
            self.put(ss, es, self.out_ann,
                     [ANN_WRITE,
                      ['Fast Write: DAC=0x%03X (%.4f V at 3.3V), PD=%s' %
                       (code, _dac_voltage(code), _pd_str(pd)),
                       'FW 0x%03X' % code]])

        elif c2c1 == 1 and c0 == 0:
            # Write DAC Register: 3 bytes
            if len(buf) < 3:
                self._warn(ss, es, 'Write DAC Reg: missing bytes (got %d)' % len(buf))
                return
            pd   = (b0 >> 1) & 3
            code = (buf[1] << 4) | (buf[2] >> 4)
            self.put(ss, es, self.out_ann,
                     [ANN_WRITE,
                      ['Write DAC Reg: 0x%03X (%.4f V at 3.3V), PD=%s' %
                       (code, _dac_voltage(code), _pd_str(pd)),
                       'W DAC 0x%03X' % code]])

        elif c2c1 == 1 and c0 == 1:
            # Write DAC + EEPROM: 3 bytes
            if len(buf) < 3:
                self._warn(ss, es, 'Write DAC+EEPROM: missing bytes (got %d)' % len(buf))
                return
            pd   = (b0 >> 1) & 3
            code = (buf[1] << 4) | (buf[2] >> 4)
            self.put(ss, es, self.out_ann,
                     [ANN_WRITE,
                      ['Write DAC+EEPROM: 0x%03X (%.4f V at 3.3V), PD=%s' %
                       (code, _dac_voltage(code), _pd_str(pd)),
                       'W DAC+EE 0x%03X' % code]])
        else:
            self._warn(ss, es, 'Unknown command 0x%02X' % b0)

    def _decode_read(self, ss, es):
        buf = self.databuf
        if len(buf) < 5:
            self._warn(ss, es, 'Read response too short (%d bytes, expected 5)' % len(buf))
            return

        rdy    = (buf[0] >> 7) & 1
        por    = (buf[0] >> 6) & 1
        pd_dac = (buf[0] >> 1) & 3

        dac_code  = (buf[1] << 4) | (buf[2] >> 4)

        pd_ee     = (buf[3] >> 5) & 3
        eep_code  = ((buf[3] & 0x0F) << 8) | buf[4]

        status = []
        if not rdy: status.append('BUSY')
        if por:     status.append('POR')

        status_str = ' [%s]' % ','.join(status) if status else ''
        self.put(ss, es, self.out_ann,
                 [ANN_READ,
                  ['Read: DAC=0x%03X (%.4f V) PD=%s | EEPROM=0x%03X (%.4f V) PD=%s%s' %
                   (dac_code, _dac_voltage(dac_code), _pd_str(pd_dac),
                    eep_code, _dac_voltage(eep_code), _pd_str(pd_ee), status_str),
                   'R DAC=0x%03X EE=0x%03X' % (dac_code, eep_code)]])

    def _decode_gc(self, ss, es):
        buf = self.databuf
        if not buf:
            self._warn(ss, es, 'General Call with no command byte')
            return
        cmd = buf[0]
        if cmd == 0x06:
            self.put(ss, es, self.out_ann, [ANN_GC, ['General Call: Reset', 'GC Reset']])
        elif cmd == 0x09:
            self.put(ss, es, self.out_ann, [ANN_GC, ['General Call: Wake-Up', 'GC Wake']])
        else:
            self.put(ss, es, self.out_ann, [ANN_GC, ['General Call: 0x%02X' % cmd]])

    def decode(self):
        while True:
            ptype, pdata = self.wait()

            if ptype in ('START', 'START REPEAT'):
                self.databuf  = []
                self.is_read  = False
                self.is_gc    = False
                self.ss_block = self.ss
                self.state    = 'GET_ADDR'

            elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
                addr = pdata[0]
                if addr == GC_ADDR:
                    self.is_gc   = True
                    self.is_read = False
                    self.state   = 'GET_DATA'
                elif addr in ADDRS:
                    self.addr    = addr
                    self.is_read = (ptype == 'ADDRESS READ')
                    self.is_gc   = False
                    self.state   = 'GET_DATA'
                else:
                    self.state = 'IDLE'

            elif ptype in ('DATA READ', 'DATA WRITE') and self.state == 'GET_DATA':
                self.databuf.append(pdata[0])

            elif ptype == 'STOP':
                if self.state == 'GET_DATA':
                    if self.is_gc:
                        self._decode_gc(self.ss_block, self.es)
                    elif self.is_read:
                        self._decode_read(self.ss_block, self.es)
                    else:
                        self._decode_write(self.ss_block, self.es)
                self.state   = 'IDLE'
                self.databuf = []
