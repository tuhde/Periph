import sigrokdecode as srd

REGISTERS = {
    0x00: 'WR',   # Wiper Register (SRAM) / Initial Value Register (EEPROM) at one address
    0x02: 'CR',   # Control Register: bit 7 = MODE
}

ADDRS = (0x28, 0x29, 0x2A, 0x2B)

CR_MODE = 0x80

ANN_REG  = 0
ANN_WARN = 1


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); derive a
    missing short tier from the existing text so a narrow PulseView zoom still
    has something to show."""
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
    id = 'ds3502'
    name = 'DS3502'
    longname = 'DS3502 7-bit nonvolatile high-voltage I2C digital potentiometer'
    desc = 'Decode DS3502 I2C wiper (WR/IVR) and control register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ds3502']
    tags = ['IC']

    annotations = (
        ('reg',  'Register'),
        ('warn', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG,)),
        ('warnings', 'Warnings', (ANN_WARN,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann and data[0] == ANN_REG:
            data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.ptr_reg = None        # last address-counter value written (used by stand-alone reads)
        self.reg = None            # memory address of the transaction being decoded
        self.data = []             # (ss, es, value) data bytes of this transaction
        self.ss_block = None
        self.addr_span = None      # (ss, es) of the address byte, to annotate a NACK
        self.mode_eeprom = True    # MODE = 0 after power-up until the first CR write
        self.busy = False          # an EEPROM write was started by the previous write

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg, 'WARN']])

    def _emit(self, ss, es, rw, reg, val, extra=''):
        if reg == 0x00:
            code = val & 0x7F
            text = '%s WR [0x00] = %d (ratio %.3f)%s' % (rw, code, code / 127.0, extra)
        else:
            text = '%s CR [0x02] = 0x%02X (MODE = %d)' % (rw, val, 1 if val & CR_MODE else 0)
        self.put(ss, es, self.out_ann, [ANN_REG, [text, '%s %s' % (rw, REGISTERS[reg])]])
        self.put(ss, es, self.out_python, ('REG_READ' if rw == 'R' else 'REG_WRITE', (reg, val)))

    def _emit_write(self):
        for bss, bes, val in self.data:
            if self.reg not in REGISTERS:
                self._warn(bss, bes, 'Write to unused address 0x%02X' % self.reg)
                continue
            if self.reg == 0x00:
                if val & 0x80:
                    self._warn(bss, bes, 'Bit 7 of the wiper byte is unused')
                if self.mode_eeprom:
                    self._emit(bss, bes, 'W', 0x00, val, ' WR+IVR (EEPROM write follows STOP, tW 10-20 ms)')
                    self._warn(bss, bes, 'Write with MODE = 0 wears the EEPROM')
                    self.busy = True
                else:
                    self._emit(bss, bes, 'W', 0x00, val, ' WR only')
            else:
                self._emit(bss, bes, 'W', 0x02, val)
                self.mode_eeprom = not (val & CR_MODE)

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.ss_block = ss
            self.reg = None
            self.data = []

        elif ptype == 'START REPEAT':
            if self.state == 'WAIT_DATA_W':
                self.ptr_reg = self.reg     # dummy write (address counter) followed by a repeated-start read
            self.state = 'IDLE'
            self.ss_block = ss
            self.reg = None
            self.data = []

        elif ptype == 'ADDRESS WRITE':
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.state = 'WAIT_REG'
            self.addr_span = (ss, es)

        elif ptype == 'ADDRESS READ':
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.reg = self.ptr_reg
            self.state = 'WAIT_DATA_R'
            self.addr_span = (ss, es)

        elif ptype == 'NACK':
            if self.addr_span is not None and self.state in ('WAIT_REG', 'WAIT_DATA_R') and not self.data:
                if self.busy:
                    self.put(self.addr_span[0], self.addr_span[1], self.out_ann,
                             [ANN_REG, ['busy (EEPROM write)', 'busy']])
                self.state = 'IDLE'
            self.addr_span = None

        elif ptype == 'ACK':
            if self.addr_span is not None:
                self.busy = False          # the chip answered: any EEPROM write has finished
            self.addr_span = None

        elif ptype == 'DATA WRITE':
            self.addr_span = None
            if self.state == 'WAIT_REG':
                if pdata not in REGISTERS:
                    self._warn(ss, es, 'Address 0x%02X is not 0x00 or 0x02' % pdata)
                self.reg = pdata
                self.state = 'WAIT_DATA_W'
            elif self.state in ('WAIT_DATA_W', 'WAIT_STOP_W'):
                self.data.append((ss, es, pdata))
                self.state = 'WAIT_STOP_W'
            else:
                self.state = 'IDLE'

        elif ptype == 'DATA READ':
            self.addr_span = None
            if self.state in ('WAIT_DATA_R', 'WAIT_STOP_R'):
                self.data.append((ss, es, pdata))
                self.state = 'WAIT_STOP_R'
            else:
                self.state = 'IDLE'

        elif ptype == 'STOP':
            if self.state == 'WAIT_STOP_W' and self.reg is not None:
                self._emit_write()
                self.ptr_reg = self.reg
            elif self.state == 'WAIT_DATA_W':
                self.ptr_reg = self.reg      # dummy write: only the address counter was loaded
            elif self.state == 'WAIT_STOP_R' and self.reg in REGISTERS:
                for bss, bes, val in self.data:
                    self._emit(bss, bes, 'R', self.reg, val)
            self.state = 'IDLE'
            self.reg = None
            self.data = []
