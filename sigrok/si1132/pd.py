import sigrokdecode as srd

ADDRS = {0x60}

REGS = {
    0x00: 'part_id',
    0x01: 'rev_id',
    0x02: 'seq_id',
    0x03: 'int_cfg',
    0x04: 'irq_enable',
    0x07: 'hw_key',
    0x08: 'meas_rate0',
    0x09: 'meas_rate1',
    0x0A: 'meas_rate_swapped',
    0x13: 'ucoef0',
    0x14: 'ucoef1',
    0x15: 'ucoef2',
    0x16: 'ucoef3',
    0x17: 'param_wr',
    0x18: 'command',
    0x20: 'response',
    0x21: 'irq_status',
    0x22: 'als_vis_data0',
    0x23: 'als_vis_data1',
    0x24: 'als_ir_data0',
    0x25: 'als_ir_data1',
    0x2C: 'aux_data0',
    0x2D: 'aux_data1',
    0x2E: 'param_rd',
    0x30: 'chip_stat',
}

PARAMS = {
    0x00: 'I2C_ADDR',
    0x01: 'CHLIST',
    0x06: 'ALS_ENCODING',
    0x0E: 'ALS_IR_ADCMUX',
    0x0F: 'AUX_ADCMUX',
    0x10: 'ALS_VIS_ADC_COUNTER',
    0x11: 'ALS_VIS_ADC_GAIN',
    0x12: 'ALS_VIS_ADC_MISC',
    0x1D: 'ALS_IR_ADC_COUNTER',
    0x1E: 'ALS_IR_ADC_GAIN',
    0x1F: 'ALS_IR_ADC_MISC',
}

COMMANDS = {
    0x00: 'NOP',
    0x01: 'RESET',
    0x02: 'BUSADDR',
    0x06: 'ALS_FORCE',
    0x0A: 'ALS_PAUSE',
    0x0E: 'ALS_AUTO',
    0x12: 'GET_CAL',
}

RESP_ERRORS = {
    0x80: 'INVALID_SETTING',
    0x8C: 'ALS_VIS_ADC_OVERFLOW',
    0x8D: 'ALS_IR_ADC_OVERFLOW',
    0x8E: 'AUX_ADC_OVERFLOW',
}

DATA_FIRST, DATA_LAST = 0x22, 0x2D
REG_HW_KEY = 0x07
REG_PARAM_WR = 0x17
REG_COMMAND = 0x18
REG_RESPONSE = 0x20
REG_IRQ_STATUS = 0x21
PARAM_CHLIST = 0x01
HW_KEY_VALUE = 0x17

ANN_REG_WRITE     = 0
ANN_REG_READ      = 1
ANN_BURST_READ    = 2
ANN_PTR_WRITE     = 3
ANN_WARNING       = 4
ANN_STATUS        = 5


def _decode_chlist(raw):
    names = [n for bit, n in ((0x80, 'UV'), (0x40, 'AUX'), (0x20, 'ALS_IR'), (0x10, 'ALS_VIS')) if raw & bit]
    return '+'.join(names) or 'none'


def _decode_command(cmd):
    if (cmd & 0xE0) == 0xA0:
        off = cmd & 0x1F
        return 'PARAM_SET %s (0x%02X)' % (PARAMS.get(off, 'param'), off)
    if (cmd & 0xE0) == 0x80:
        off = cmd & 0x1F
        return 'PARAM_QUERY %s (0x%02X)' % (PARAMS.get(off, 'param'), off)
    return COMMANDS.get(cmd, 'command 0x%02X' % cmd)


def _decode_response(raw):
    if raw & 0x80:
        return 'response 0x%02X: error %s' % (raw, RESP_ERRORS.get(raw, 'unknown'))
    return 'response 0x%02X: counter %d, no error' % (raw, raw & 0x0F)


def _decode_irq_status(raw):
    parts = []
    if raw & 0x20:
        parts.append('CMD_INT')
    if raw & 0x01:
        parts.append('ALS_INT')
    return 'irq_status 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'none')


def _decode_param(offset, value):
    name = PARAMS.get(offset, 'param 0x%02X' % offset)
    if offset == PARAM_CHLIST:
        return '%s = 0x%02X (%s)' % (name, value, _decode_chlist(value))
    if offset in (0x11, 0x1E):
        return '%s = %d (integration time x %d)' % (name, value & 7, 1 << (value & 7))
    if offset in (0x12, 0x1F):
        return '%s = 0x%02X (%s range)' % (name, value, 'high' if value & 0x20 else 'normal')
    if offset == 0x0F:
        return '%s = 0x%02X (%s)' % (name, value, {0x65: 'temperature', 0x75: 'VDD'}.get(value, 'unknown'))
    return '%s = 0x%02X' % (name, value)


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
    id = 'si1132'
    name = 'Si1132'
    longname = 'Si1132 UV index and ambient light sensor'
    desc = 'Decode Si1132 I2C UV index and ambient light sensor register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['si1132']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write',     'Register write'),
        ('reg-read',      'Register read'),
        ('burst-read',    'Multi-byte burst read'),
        ('ptr-write',     'Register pointer write'),
        ('warning',       'Warning'),
        ('status', 'Status flags'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_BURST_READ, ANN_PTR_WRITE)),
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
        ('REG_READ' | 'REG_WRITE', (register, bytes)), or
        ('I2C_READ' | 'I2C_WRITE', (address, bytes)) for chips without a register pointer."""
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
        if reg is None:
            reg = getattr(self, 'reg_byte', None)
        if reg is not None:
            super().put(ss, es, out_py, ('REG_' + rw, (reg, buf)))
        else:
            super().put(ss, es, out_py, ('I2C_' + rw, (getattr(self, 'addr', None), buf)))

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
        self.hw_key_written = False
        self.chlist   = 0
        self.param_wr = 0

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

    def _emit_status(self, flags, raw, ss, es):
        self.put(ss, es, self.out_ann,
                 [ANN_STATUS, ['status %s' % flags, flags, '0x%02X' % raw]])
        self.put(ss, es, self.out_python, ('STATUS', (raw, flags)))

    def _finish_read(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        if not buf:
            return

        if reg == 0x00:
            ok = buf[0] == 0x32
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ, ['PART_ID: 0x%02X (%s)' % (buf[0], 'Si1132' if ok else 'unexpected'),
                                      'ID 0x%02X' % buf[0]]])
            if not ok:
                self._warn(ss, es, 'Unexpected chip ID 0x%02X, expected 0x32' % buf[0])
            return

        if reg == REG_RESPONSE and len(buf) == 1:
            self.put(ss, es, self.out_ann, [ANN_REG_READ, [_decode_response(buf[0]), 'resp 0x%02X' % buf[0]]])
            if buf[0] & 0x80:
                self._warn(ss, es, 'RESPONSE error 0x%02X (%s)' % (buf[0], RESP_ERRORS.get(buf[0], 'unknown')))
            return

        if reg == REG_IRQ_STATUS and len(buf) == 1:
            flags = []
            if buf[0] & 0x20:
                flags.append('CMD_INT')
            if buf[0] & 0x01:
                flags.append('ALS_INT')
            self.put(ss, es, self.out_ann, [ANN_REG_READ, [_decode_irq_status(buf[0]), 'status 0x%02X' % buf[0]]])
            self._emit_status(' '.join(flags) or 'idle', buf[0], ss, es)
            return

        if reg == 0x02 and len(buf) == 1:
            self.put(ss, es, self.out_ann, [ANN_REG_READ, ['SEQ_ID: 0x%02X' % buf[0], 'SEQ 0x%02X' % buf[0]]])
            if buf[0] == 0x01:
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ, ['SEQ_ID 0x01: MEAS_RATE bytes swapped (erratum)', 'ERRATA']])
            return

        if reg == DATA_FIRST and len(buf) >= 4:
            vis = (buf[1] << 8) | buf[0]
            ir = (buf[3] << 8) | buf[2]
            text = 'visible=%d infrared=%d' % (vis, ir)
            short = 'V=%d I=%d' % (vis, ir)
            if len(buf) >= 12:
                aux = (buf[11] << 8) | buf[10]
                text += ' aux=%d (UV index %.2f)' % (aux, aux / 100.0)
                short += ' UV=%.2f' % (aux / 100.0)
            self.put(ss, es, self.out_ann, [ANN_BURST_READ, [text, short]])
            return

        if reg in (0x2C, 0x2D) and len(buf) >= 2 and reg == 0x2C:
            aux = (buf[1] << 8) | buf[0]
            self.put(ss, es, self.out_ann,
                     [ANN_BURST_READ, ['aux=%d (UV index %.2f)' % (aux, aux / 100.0), 'UV=%.2f' % (aux / 100.0)]])
            return

        hex_bytes = ' '.join('0x%02X' % b for b in buf)
        self.put(ss, es, self.out_ann,
                 [ANN_REG_READ, ['Read %s: %s' % (self._reg_name(reg), hex_bytes), 'R 0x%02X' % reg]])

    def _emit_status(self, flags, raw, ss, es):
        self.put(ss, es, self.out_ann,
                 [ANN_STATUS, ['status %s' % flags, flags, '0x%02X' % raw]])
        self.put(ss, es, self.out_python, ('STATUS', (raw, flags)))

    def _finish_write(self, reg):
        buf = self.databuf
        ss, es = self.ss_block, self.es
        name = self._reg_name(reg)

        if not buf:
            self.put(ss, es, self.out_ann,
                     [ANN_PTR_WRITE, ['Pointer -> %s (0x%02X)' % (name, reg), 'PTR 0x%02X' % reg]])
            return

        if len(buf) == 1:
            v = buf[0]
            if reg == REG_HW_KEY:
                self.hw_key_written = (v == HW_KEY_VALUE)
                text = 'hw_key 0x%02X%s' % (v, '' if v == HW_KEY_VALUE else ' (expected 0x17)')
                self.put(ss, es, self.out_ann, [ANN_REG_WRITE, [text, 'HW_KEY']])
                if v != HW_KEY_VALUE:
                    self._warn(ss, es, 'HW_KEY written with 0x%02X, expected 0x17' % v)
                return
            if reg == REG_PARAM_WR:
                self.param_wr = v
                self.put(ss, es, self.out_ann, [ANN_REG_WRITE, ['param_wr 0x%02X' % v, 'PARAM_WR']])
                return
            if reg == REG_COMMAND:
                self.put(ss, es, self.out_ann,
                         [ANN_REG_WRITE, ['command 0x%02X: %s' % (v, _decode_command(v)), 'CMD 0x%02X' % v]])
                if (v & 0xE0) == 0xA0:
                    off = v & 0x1F
                    self.put(ss, es, self.out_ann,
                             [ANN_REG_WRITE, [_decode_param(off, self.param_wr), PARAMS.get(off, 'param')]])
                    if off == PARAM_CHLIST:
                        self.chlist = self.param_wr
                elif v == 0x01:
                    self.hw_key_written = False
                elif v in (0x06, 0x0E):
                    if not self.hw_key_written:
                        self._warn(ss, es, 'Measurement requested before HW_KEY was written')
                    if self.chlist == 0:
                        self._warn(ss, es, 'Measurement requested with an empty CHLIST')
                return
            if reg == 0x03:
                self.put(ss, es, self.out_ann,
                         [ANN_REG_WRITE, ['int_cfg 0x%02X: INT_OE=%d' % (v, v & 1), 'INT_CFG']])
                return
            if reg == 0x04:
                self.put(ss, es, self.out_ann,
                         [ANN_REG_WRITE, ['irq_enable 0x%02X: ALS_IE=%d' % (v, v & 1), 'IRQ_EN']])
                return
            if reg == REG_IRQ_STATUS:
                self.put(ss, es, self.out_ann,
                         [ANN_REG_WRITE, ['irq_status clear 0x%02X' % v, 'IRQ clr']])
                return

        if reg in (0x08, 0x09, 0x0A) and len(buf) == 1:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, ['%s 0x%02X' % (name, buf[0]), '%s' % name]])
            return

        if reg == 0x13 and len(buf) == 4:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, ['UCOEF = %s' % ' '.join('0x%02X' % b for b in buf), 'UCOEF']])
            return

        hex_bytes = ' '.join('0x%02X' % b for b in buf)
        self.put(ss, es, self.out_ann,
                 [ANN_REG_WRITE, ['Write %s: %s' % (name, hex_bytes), 'W 0x%02X' % reg]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                # Pointer set followed by repeated-start read: preserve reg_ptr
                pass
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state   = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = byte & 0x3F
                self.databuf = []
                self.state   = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []