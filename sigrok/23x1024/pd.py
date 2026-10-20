"""
23X1024 sigrok protocol decoder.

Decodes Microchip 23LC1024 / 23A1024 1-Mbit serial SRAM SPI transactions
(SPI Mode 0, one CS-framed transaction per instruction). The decoder annotates:

- the instruction byte (READ 0x03, WRITE 0x02, RDMR 0x05, WRMR 0x01, EDIO 0x3B,
  EQIO 0x38, RSTIO 0xFF) and, for READ/WRITE, the 24-bit address and the data
  bytes, each on the byte's own span, plus a one-line summary of the whole
  transaction (instruction, address, length, data preview);
- the MODE register for RDMR/WRMR, decoded into Byte / Page / Sequential, with
  the reserved value 0b11 and non-zero reserved bits 5:0 flagged;
- EDIO / EQIO as informational (the decoder cannot follow SDI/SQI traffic, so
  anything after them is flagged as undecodable until RSTIO);
- warnings for address bits 23:17 set, a transfer that runs past the end of the
  array (Sequential) or across a 32-byte page boundary (Page, once a WRMR has
  been seen), more than one data byte in Byte mode, truncated frames and
  unknown instructions.

The decoder sits on top of the ``spi`` protocol decoder, which must be given
the CS channel (transactions are only reported when CS is deasserted).
"""

import sigrokdecode as srd


INSTR_WRMR = 0x01
INSTR_WRITE = 0x02
INSTR_READ = 0x03
INSTR_RDMR = 0x05
INSTR_EQIO = 0x38
INSTR_EDIO = 0x3B
INSTR_RSTIO = 0xFF

INSTR_NAMES = {
    INSTR_WRMR: 'WRMR', INSTR_WRITE: 'WRITE', INSTR_READ: 'READ',
    INSTR_RDMR: 'RDMR', INSTR_EQIO: 'EQIO', INSTR_EDIO: 'EDIO',
    INSTR_RSTIO: 'RSTIO',
}

ARRAY_SIZE = 0x20000
PAGE_SIZE = 32

# MODE register bits 7:6.
MODE_NAMES = {0x00: 'Byte', 0x80: 'Page', 0x40: 'Sequential', 0xC0: 'Reserved'}

ANN_SUMMARY = 0
ANN_MODE    = 1
ANN_INSTR   = 2
ANN_ADDR    = 3
ANN_DATA    = 4
ANN_INFO    = 5
ANN_WARN    = 6

PREVIEW_BYTES = 8


def _hex(values, limit=PREVIEW_BYTES):
    shown = ' '.join('%02X' % v for v in values[:limit])
    return shown + (' …' if len(values) > limit else '')


class Decoder(srd.Decoder):
    api_version = 3
    id = '23x1024'
    name = '23X1024'
    longname = 'Microchip 23LC1024 / 23A1024 1-Mbit SPI serial SRAM'
    desc = 'Decode 23X1024 SPI READ / WRITE / mode-register transactions.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['23x1024']
    tags = ['IC', 'Memory']

    annotations = (
        ('summary', 'Transaction summary'),
        ('mode',    'MODE register'),
        ('instr',   'Instruction byte'),
        ('addr',    '24-bit address'),
        ('data',    'Data bytes'),
        ('info',    'Informational'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_SUMMARY, ANN_MODE, ANN_INFO)),
        ('fields',   'Fields',   (ANN_INSTR, ANN_ADDR, ANN_DATA)),
        ('warnings', 'Warnings', (ANN_WARN,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.mode = None            # last MODE value seen via WRMR/RDMR; None = unknown
        self.non_spi = False        # True after EDIO/EQIO until RSTIO

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _ann(self, ss, es, cls, strings):
        self.put(ss, es, self.out_ann, [cls, list(strings)])

    def _warn(self, ss, es, msg, tag):
        self._ann(ss, es, ANN_WARN, [msg, tag])

    def _py(self, ss, es, kind, value):
        self.put(ss, es, self.out_python, (kind, value))

    def decode(self, ss, es, data):
        if data[0] != 'TRANSFER':
            return
        mosi, miso = data[1], data[2]
        if not mosi:
            if mosi is None:
                self._warn(ss, es, 'MOSI channel required to decode instructions', 'MOSI?')
            return
        self._transaction(ss, es, mosi, miso)

    def _transaction(self, ss, es, mosi, miso):
        instr = mosi[0].val
        name = INSTR_NAMES.get(instr)
        self._ann(mosi[0].ss, mosi[0].es, ANN_INSTR,
                  [name or 'UNKNOWN 0x%02X' % instr, name or '?', '0x%02X' % instr])

        if self.non_spi and instr != INSTR_RSTIO:
            self._warn(ss, es, 'Chip is in SDI/SQI mode (EDIO/EQIO seen): SPI decode not valid until RSTIO',
                       'SDI/SQI')
            return

        if instr in (INSTR_READ, INSTR_WRITE):
            self._read_write(ss, es, instr, mosi, miso)
        elif instr == INSTR_RDMR:
            self._rdmr(ss, es, mosi, miso)
        elif instr == INSTR_WRMR:
            self._wrmr(ss, es, mosi)
        elif instr in (INSTR_EDIO, INSTR_EQIO):
            kind = 'SDI' if instr == INSTR_EDIO else 'SQI'
            self.non_spi = True
            self._ann(ss, es, ANN_INFO, ['%s (0x%02X) enter %s mode' % (name, instr, kind),
                                         '%s: enter %s' % (name, kind), name])
            self._warn(ss, es, 'Entering %s mode: following traffic uses %d data lines and cannot be '
                       'decoded as SPI until RSTIO' % (kind, 2 if instr == INSTR_EDIO else 4),
                       kind)
            self._py(ss, es, name, None)
        elif instr == INSTR_RSTIO:
            self.non_spi = False
            self._ann(ss, es, ANN_INFO, ['RSTIO (0xFF) return to SPI mode', 'RSTIO: SPI', 'RSTIO'])
            self._py(ss, es, 'RSTIO', None)
        else:
            self._warn(ss, es, 'Unknown instruction 0x%02X' % instr, 'INSTR?')

    def _read_write(self, ss, es, instr, mosi, miso):
        name = INSTR_NAMES[instr]
        if len(mosi) < 4:
            self._warn(ss, es, '%s frame has %d byte(s); needs instruction + 3 address bytes' % (name, len(mosi)),
                       'SHORT')
            return
        addr_bytes = [b.val for b in mosi[1:4]]
        addr = (addr_bytes[0] << 16) | (addr_bytes[1] << 8) | addr_bytes[2]
        self._ann(mosi[1].ss, mosi[3].es, ANN_ADDR,
                  ['Address 0x%05X' % (addr & 0x1FFFF), 'A=0x%05X' % (addr & 0x1FFFF), '0x%05X' % (addr & 0x1FFFF)])
        if addr_bytes[0] & 0xFE:
            self._warn(mosi[1].ss, mosi[3].es,
                       'Address bits 23:17 set (0x%06X); they are don\'t-care and ignored by the chip' % addr,
                       'A23:17')

        if instr == INSTR_WRITE:
            payload = [b.val for b in mosi[4:]]
            span = (mosi[4].ss, mosi[-1].es) if payload else None
        else:
            if miso is None:
                self._warn(ss, es, 'MISO channel required to decode READ data', 'MISO?')
                return
            payload = [b.val for b in miso[4:]]
            span = (miso[4].ss, miso[-1].es) if payload else None

        addr &= 0x1FFFF
        n = len(payload)
        if span:
            self._ann(span[0], span[1], ANN_DATA,
                      ['%d byte(s): %s' % (n, _hex(payload)), '%d B' % n, _hex(payload, 2)])
        label = '%s 0x%05X' % (name, addr)
        if n:
            self._ann(ss, es, ANN_SUMMARY,
                      ['%s, %d byte(s): %s' % (label, n, _hex(payload)),
                       '%s [%d]' % (label, n), '%s' % name])
        else:
            self._ann(ss, es, ANN_SUMMARY, ['%s, no data' % label, label, name])
        self._py(ss, es, name, (addr, bytes(payload)))
        self._check_range(ss, es, addr, n)

    def _check_range(self, ss, es, addr, n):
        if n == 0:
            return
        if self.mode == 0x00 and n > 1:
            self._warn(ss, es, 'Byte mode transfers exactly one data byte; %d extra byte(s) ignored' % (n - 1),
                       'BYTE>1')
        elif self.mode == 0x80 and (addr % PAGE_SIZE) + n > PAGE_SIZE:
            self._warn(ss, es, 'Page mode: transfer from 0x%05X, %d byte(s) wraps inside its 32-byte page '
                       '(page start 0x%05X)' % (addr, n, addr - addr % PAGE_SIZE), 'PAGE-WRAP')
        elif self.mode in (None, 0x40) and addr + n > ARRAY_SIZE:
            self._warn(ss, es, 'Transfer from 0x%05X, %d byte(s) runs past 0x1FFFF and wraps to 0x00000' % (addr, n),
                       'WRAP')

    def _mode_text(self, value):
        mode = value & 0xC0
        text = '%s mode' % MODE_NAMES[mode]
        return mode, text

    def _mode_checks(self, ss, es, value):
        if value & 0xC0 == 0xC0:
            self._warn(ss, es, 'MODE bits 7:6 = 0b11 is reserved', 'RSVD')
        if value & 0x3F:
            self._warn(ss, es, 'MODE bits 5:0 are reserved and should be 0 (got 0x%02X)' % (value & 0x3F),
                       'RSVD<5:0>')

    def _rdmr(self, ss, es, mosi, miso):
        if miso is None or len(miso) < 2:
            self._warn(ss, es, 'RDMR frame without a MODE byte on MISO', 'SHORT')
            return
        value = miso[1].val
        mode, text = self._mode_text(value)
        self._ann(miso[1].ss, miso[1].es, ANN_DATA, ['MODE 0x%02X' % value, '0x%02X' % value])
        self._ann(ss, es, ANN_MODE, ['RDMR: MODE=0x%02X (%s)' % (value, text), 'RDMR %s' % MODE_NAMES[mode], 'RDMR'])
        self._mode_checks(miso[1].ss, miso[1].es, value)
        if mode != 0xC0:
            self.mode = mode
        self._py(ss, es, 'RDMR', value)

    def _wrmr(self, ss, es, mosi):
        if len(mosi) < 2:
            self._warn(ss, es, 'WRMR frame without a MODE byte', 'SHORT')
            return
        value = mosi[1].val
        mode, text = self._mode_text(value)
        self._ann(mosi[1].ss, mosi[1].es, ANN_DATA, ['MODE 0x%02X' % value, '0x%02X' % value])
        self._ann(ss, es, ANN_MODE, ['WRMR: MODE=0x%02X (%s)' % (value, text), 'WRMR %s' % MODE_NAMES[mode], 'WRMR'])
        self._mode_checks(mosi[1].ss, mosi[1].es, value)
        if mode != 0xC0:
            self.mode = mode
        self._py(ss, es, 'WRMR', value)
