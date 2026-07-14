import sigrokdecode as srd

ADDRS = set(range(0x60, 0x68))   # factory default 0x60, address bits A2:A1:A0 in EEPROM

CHANNELS = ('A', 'B', 'C', 'D')

ANN_WRITE     = 0
ANN_READ      = 1
ANN_GC        = 2
ANN_WARNING   = 3

VREF_NAMES = {0: 'V_DD', 1: 'INT (2.048V)'}
PD_NAMES   = {0: 'Normal', 1: '1kΩ→GND', 2: '100kΩ→GND', 3: '500kΩ→GND'}


def _vref_pd_gain_byte(b):
    """Decode byte 2 layout: [V_REF PD1 PD0 Gx D11-D8]."""
    vref = (b >> 7) & 0x01
    pd   = (b >> 5) & 0x03
    gx   = (b >> 4) & 0x01
    code_hi = b & 0x0F
    return vref, pd, gx, code_hi


def _format_channel_state(prefix, code, vref, pd, gx):
    return '%s code=0x%03X Vref=%s PD=%s Gx=×%d' % (
        prefix, code, VREF_NAMES.get(vref, str(vref)),
        PD_NAMES.get(pd, str(pd)), 2 if gx else 1)


def _format_input_register(reg):
    """Format the 3-byte input register response.

    Byte 1: [RDY/BSY POR DAC1 DAC0 0 A2 A1 A0]
    Byte 2: [V_REF PD1 PD0 Gx D11-D8]
    Byte 3: [D7-D0]
    """
    if len(reg) < 3:
        return None
    rdy = (reg[0] >> 7) & 0x01
    por = (reg[0] >> 6) & 0x01
    ch_idx = (reg[0] >> 4) & 0x03
    vref, pd, gx, code_hi = _vref_pd_gain_byte(reg[1])
    code = (code_hi << 8) | reg[2]
    ch = CHANNELS[ch_idx] if ch_idx < 4 else '?'
    flags = []
    if not rdy:
        flags.append('EEPROM-BSY')
    if por:
        flags.append('POR')
    flag_str = ' [%s]' % ','.join(flags) if flags else ''
    return ch, code, vref, pd, gx, flag_str


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mcp4728'
    name = 'MCP4728'
    longname = 'Microchip MCP4728 quad 12-bit DAC'
    desc = 'Decode MCP4728 I2C DAC register transactions (no register pointer).'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mcp4728']
    tags = ['IC', 'DAC']

    annotations = (
        ('write',  'Write'),
        ('read',   'Read'),
        ('gc',     'General Call'),
        ('warning','Warning'),
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
        self.is_read  = False
        self.databuf  = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _emit(self, ann_idx, ss, es, texts):
        self.put(ss, es, self.out_ann, [ann_idx, texts])

    def _finish_transaction(self):
        if self.addr is None or not self.databuf:
            return
        ss, es = self.ss_block, self.es
        buf = self.databuf

        if self.addr == 0x00:
            if len(buf) >= 1:
                gc_cmd = buf[0]
                if gc_cmd == 0x06:
                    name = 'General Call Reset (0x06)'
                elif gc_cmd == 0x08:
                    name = 'General Call Software Update (0x08)'
                elif gc_cmd == 0x09:
                    name = 'General Call Wake-Up (0x09)'
                else:
                    name = 'General Call 0x%02X' % gc_cmd
                self._emit(ANN_GC, ss, es, [name, 'GC 0x%02X' % gc_cmd])
            return

        if self.is_read:
            self._finish_read(ss, es, buf)
        else:
            self._finish_write(ss, es, buf)

    def _finish_read(self, ss, es, buf):
        if len(buf) != 24:
            self._warn(ss, es,
                       'Unexpected read length %d (expected 24)' % len(buf))
            return
        # 4 channels × 3 bytes input register + 3 bytes EEPROM
        for i, ch in enumerate(CHANNELS):
            inp = buf[i * 3: i * 3 + 3]
            ee  = buf[12 + i * 3: 12 + i * 3 + 3]
            inp_dec = _format_input_register(inp)
            if inp_dec is None:
                continue
            ch_letter, code, vref, pd, gx, flags = inp_dec
            inp_str = _format_channel_state('ch%s DAC' % ch_letter, code, vref, pd, gx) + flags
            ee_dec = _format_input_register(ee)
            if ee_dec is None:
                continue
            _, ee_code, ee_vref, ee_pd, ee_gx, _ = ee_dec
            ee_str = _format_channel_state('ch%s EE' % ch_letter, ee_code, ee_vref, ee_pd, ee_gx)
            self._emit(ANN_READ, ss, es, [
                '%s | %s' % (inp_str, ee_str),
                'ch%s 0x%03X / 0x%03X' % (ch_letter, code, ee_code)
            ])

    def _finish_write(self, ss, es, buf):
        if not buf:
            return
        cmd = buf[0]
        upper = (cmd >> 5) & 0x07

        # 100 xxx = Write V_REF / Gain / Power-Down broadcast commands
        if (cmd & 0xE0) == 0x80:
            if upper == 0b100:
                if len(buf) != 1:
                    self._warn(ss, es,
                               'Write V_REF expects 1 byte, got %d' % len(buf))
                    return
                vrefs = [(buf[0] >> (3 - i)) & 0x01 for i in range(4)]
                s = 'Write V_REF: A=%s B=%s C=%s D=%s' % (
                    VREF_NAMES[vrefs[0]], VREF_NAMES[vrefs[1]],
                    VREF_NAMES[vrefs[2]], VREF_NAMES[vrefs[3]])
                self._emit(ANN_WRITE, ss, es, [s, 'VREF 0x%02X' % buf[0]])
                return
            if upper == 0b110:
                if len(buf) != 1:
                    self._warn(ss, es,
                               'Write Gain expects 1 byte, got %d' % len(buf))
                    return
                gains = [2 if ((buf[0] >> (3 - i)) & 0x01) else 1 for i in range(4)]
                s = 'Write Gain: A=×%d B=×%d C=×%d D=×%d' % tuple(gains)
                self._emit(ANN_WRITE, ss, es, [s, 'GAIN 0x%02X' % buf[0]])
                return
            if upper == 0b101:
                if len(buf) != 2:
                    self._warn(ss, es,
                               'Write Power-Down expects 2 bytes, got %d' % len(buf))
                    return
                pd1 = ((buf[0] >> 3) & 0x02) | ((buf[0] >> 3) & 0x01)
                pds = [
                    ((buf[0] >> 3) & 0x03),
                    ((buf[0] >> 1) & 0x03),
                    ((buf[1] >> 5) & 0x03),
                    ((buf[1] >> 3) & 0x03),
                ]
                s = 'Write Power-Down: A=%s B=%s C=%s D=%s' % tuple(PD_NAMES[p] for p in pds)
                self._emit(ANN_WRITE, ss, es, [s, 'PD 0x%02X 0x%02X' % (buf[0], buf[1])])
                return

        # 010 xxx = Multi / Single / Sequential Write
        if (cmd & 0xE0) == 0x40:
            w = (cmd >> 3) & 0x03
            dac = (cmd >> 1) & 0x03
            udac = cmd & 0x01
            ch_letter = CHANNELS[dac] if dac < 4 else '?'
            if w == 0b00:
                # Multi-Write: 1 + 2*1 = 3 bytes (single channel)
                if len(buf) != 3:
                    self._warn(ss, es,
                               'Multi-Write expects 3 bytes, got %d' % len(buf))
                    return
                vref, pd, gx, code_hi = _vref_pd_gain_byte(buf[1])
                code = (code_hi << 8) | buf[2]
                s = _format_channel_state(
                    'Multi-Write ch%s' % ch_letter, code, vref, pd, gx)
                s += ' UDAC=%d' % udac
                self._emit(ANN_WRITE, ss, es,
                           [s, 'MW %s 0x%03X' % (ch_letter, code)])
                return
            if w == 0b10:
                # Sequential Write: 1 + 2*N bytes; max 10 bytes total
                if len(buf) < 3 or len(buf) > 10 or (len(buf) - 1) % 2 != 0:
                    self._warn(ss, es,
                               'Sequential Write length %d invalid' % len(buf))
                    return
                n = (len(buf) - 1) // 2
                parts = []
                for i in range(n):
                    vref, pd, gx, code_hi = _vref_pd_gain_byte(buf[1 + i * 2])
                    code = (code_hi << 8) | buf[2 + i * 2]
                    ch_idx = (dac + i) % 4
                    parts.append(_format_channel_state(
                        CHANNELS[ch_idx], code, vref, pd, gx))
                s = 'Sequential Write %d→D: %s' % (ch_letter, ' | '.join(parts))
                s += ' UDAC=%d' % udac
                self._emit(ANN_WRITE, ss, es,
                           [s, 'SW %d 0x%02X' % (n, buf[0])])
                return
            if w == 0b11:
                # Single Write: 1 + 2 = 3 bytes (one channel + EEPROM)
                if len(buf) != 3:
                    self._warn(ss, es,
                               'Single Write expects 3 bytes, got %d' % len(buf))
                    return
                vref, pd, gx, code_hi = _vref_pd_gain_byte(buf[1])
                code = (code_hi << 8) | buf[2]
                s = _format_channel_state(
                    'Single-Write+EEPROM ch%s' % ch_letter, code, vref, pd, gx)
                s += ' UDAC=%d' % udac
                self._emit(ANN_WRITE, ss, es,
                           [s, 'SW+EE %s 0x%03X' % (ch_letter, code)])
                return

        # 00x xxx = Fast Write (8 data bytes, A→D)
        if (cmd & 0xC0) == 0x00 and len(buf) == 8:
            parts = []
            for i, ch in enumerate(CHANNELS):
                code = ((buf[i * 2] & 0x0F) << 8) | buf[i * 2 + 1]
                parts.append('ch%s=0x%03X' % (ch, code))
            s = 'Fast Write: ' + ' '.join(parts)
            self._emit(ANN_WRITE, ss, es, [s, 'FW 0x%02X..' % buf[0]])
            return

        # Fallback: unknown command
        self._warn(ss, es,
                   'Unknown command 0x%02X (%d bytes)' % (cmd, len(buf)))

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf  = []
            self.is_read  = False
            self.addr     = None
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS and addr != 0x00:
                self.state = 'IDLE'
                return
            self.addr    = addr
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state   = 'GET_DATA_READ'
            else:
                self.databuf = []
                self.state   = 'GET_DATA_WRITE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state    = 'IDLE'
            self.addr     = None
            self.is_read  = False
            self.databuf  = []
