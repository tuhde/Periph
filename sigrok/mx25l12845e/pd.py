"""
MX25L12845E sigrok protocol decoder.

Decodes Macronix MX25L12845E 128-Mbit (16 MiB) serial NOR flash SPI traffic
(SPI Mode 0, one CS-framed transaction per command). The decoder annotates:

- the opcode byte, the 24-bit address (with its 4 KiB sector, 64 KiB block and
  256-byte page), dummy bytes and the data bytes (length and preview) of every
  command in the datasheet's command table that a single-I/O host can use;
- the status register (WIP, WEL, BP3:0 with the protected size, QE, SRWD), the
  security register (WPSEL, E_FAIL, P_FAIL, CP, LDSO, factory lock) and the
  RDID / RES / REMS identification, checked against C2h 20h 18h / 17h;
- a one-line summary per transaction, and for RDSR polling loops the number of
  polls and the final WIP state.

The decoder keeps state across transactions and warns about: a program, erase
or WRSR without a preceding WREN (the chip ignores it), a command other than
RDSR / RDSCUR issued while the last operation may still be running (WIP), any
command other than RDP / RES in deep power-down, a page program that crosses a
256-byte page boundary (the chip wraps inside the page), a program or erase
aimed at the area protected by BP3:0, a chip erase while BP3:0 != 0, QE set by
WRSR (disables WP#), OTP accesses outside 0x000-0x1FF or to the ESN bytes, wrong
JEDEC / electronic IDs, command codes the driver never uses (dual / quad / DTR /
parallel / lock commands) and unknown opcodes.

The decoder sits on top of the ``spi`` protocol decoder, which must be given the
CS, MOSI and MISO channels (transactions are only reported when CS is deasserted).
A CS that rises off a byte boundary is not visible to the ``spi`` decoder, so it
cannot be flagged here.
"""

import sigrokdecode as srd


# opcode -> (name, address bytes, dummy bytes, data direction, needs WEL, starts a busy period)
OPS = {
    0x06: ('WREN',      0, 0, None,   False, False),
    0x04: ('WRDI',      0, 0, None,   False, False),
    0x9F: ('RDID',      0, 0, 'miso', False, False),
    0x05: ('RDSR',      0, 0, 'miso', False, False),
    0x01: ('WRSR',      0, 0, 'mosi', True,  True),
    0x03: ('READ',      3, 0, 'miso', False, False),
    0x0B: ('FAST_READ', 3, 1, 'miso', False, False),
    0x5A: ('RDSFDP',    3, 1, 'miso', False, False),
    0x02: ('PP',        3, 0, 'mosi', True,  True),
    0x20: ('SE',        3, 0, None,   True,  True),
    0x52: ('BE32K',     3, 0, None,   True,  True),
    0xD8: ('BE',        3, 0, None,   True,  True),
    0xC7: ('CE',        0, 0, None,   True,  True),
    0x60: ('CE',        0, 0, None,   True,  True),
    0xB9: ('DP',        0, 0, None,   False, False),
    0xAB: ('RDP/RES',   0, 0, None,   False, False),   # refined by frame length below
    0x90: ('REMS',      0, 3, 'miso', False, False),
    0xB1: ('ENSO',      0, 0, None,   False, False),
    0xC1: ('EXSO',      0, 0, None,   False, False),
    0x2B: ('RDSCUR',    0, 0, 'miso', False, False),
    0x2F: ('WRSCUR',    0, 0, None,   False, True),
    0x30: ('CLSR',      0, 0, None,   False, False),
}

# Opcodes of the datasheet's command table that this driver never uses.
UNUSED_OPS = {
    0xBB: '2READ', 0xEB: '4READ', 0x38: '4PP', 0x0D: 'FASTDTRD', 0xBD: '2DTRD', 0xED: '4DTRD',
    0xAD: 'CP', 0xA3: 'HPM', 0x55: 'ENPLM', 0x45: 'EXPLM', 0x68: 'WPSEL', 0x36: 'SBLK', 0x39: 'SBULK',
    0x7E: 'GBLK', 0x98: 'GBULK', 0x3C: 'RDBLOCK', 0xEF: 'REMS2', 0xDF: 'REMS4', 0xCF: 'REMS4D',
}

ALLOWED_WHILE_BUSY = ('RDSR', 'RDSCUR')

ARRAY_SIZE = 0x1000000
PAGE_SIZE = 256

ANN_SUMMARY = 0
ANN_INSTR   = 1
ANN_ADDR    = 2
ANN_DATA    = 3
ANN_STATUS  = 4
ANN_INFO    = 5
ANN_WARN    = 6

PREVIEW_BYTES = 8


def _hex(values, limit=PREVIEW_BYTES):
    shown = ' '.join('%02X' % v for v in values[:limit])
    return shown + (' …' if len(values) > limit else '')


def _protected_from(bp):
    """First protected byte address for BP3:0 = bp (the top of the array is protected)."""
    if bp == 0:
        return ARRAY_SIZE
    if bp >= 8:
        return 0
    return ARRAY_SIZE - (0x10000 << bp)   # 2^bp blocks of 64 KiB


def _bp_text(bp):
    if bp == 0:
        return 'none protected'
    if bp >= 8:
        return 'all 256 blocks protected'
    return 'top %d x 64 KiB blocks protected' % (1 << bp)


def _sr_text(sr):
    bp = (sr >> 2) & 0xF
    parts = ['WIP' if sr & 0x01 else 'idle', 'WEL' if sr & 0x02 else 'no WEL', 'BP=%d (%s)' % (bp, _bp_text(bp))]
    if sr & 0x40:
        parts.append('QE')
    if sr & 0x80:
        parts.append('SRWD')
    return ', '.join(parts)


def _sr_cfg_text(sr):
    """The writable status register fields only (WRSR cannot change WIP / WEL)."""
    bp = (sr >> 2) & 0xF
    parts = ['BP=%d (%s)' % (bp, _bp_text(bp)), 'QE=%d' % (1 if sr & 0x40 else 0), 'SRWD=%d' % (1 if sr & 0x80 else 0)]
    return ', '.join(parts)


def _scur_text(v):
    flags = []
    for bit, name in ((0x80, 'WPSEL'), (0x40, 'E_FAIL'), (0x20, 'P_FAIL'), (0x10, 'CP mode'), (0x02, 'OTP locked (LDSO)')):
        if v & bit:
            flags.append(name)
    flags.append('OTP factory-locked' if v & 0x01 else 'OTP not factory-locked')
    return ', '.join(flags)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mx25l12845e'
    name = 'MX25L12845E'
    longname = 'Macronix MX25L12845E 128-Mbit serial NOR flash'
    desc = 'Decode MX25L12845E SPI read / program / erase / status transactions.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['mx25l12845e']
    tags = ['IC', 'Memory']

    annotations = (
        ('summary', 'Transaction summary'),
        ('instr',   'Instruction byte'),
        ('addr',    '24-bit address'),
        ('data',    'Data bytes'),
        ('status',  'Status / security register'),
        ('info',    'Informational'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_SUMMARY, ANN_STATUS, ANN_INFO)),
        ('fields',   'Fields',   (ANN_INSTR, ANN_ADDR, ANN_DATA)),
        ('warnings', 'Warnings', (ANN_WARN,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.wel = False        # a WREN was seen and not yet consumed
        self.busy = False       # a program/erase/WRSR was started and no RDSR has shown WIP = 0 since
        self.bp = None          # last known BP3:0 (None = unknown)
        self.dp = False         # deep power-down entered
        self.otp = False        # secured OTP mode entered
        self.blocked = False    # the current command is ignored by the chip (protected area, BP != 0 for CE)

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
            self._warn(ss, es, 'MOSI channel required to decode commands', 'MOSI?')
            return
        self._transaction(ss, es, mosi, miso)

    # ------------------------------------------------------------------

    def _transaction(self, ss, es, mosi, miso):
        op = mosi[0].val
        entry = OPS.get(op)
        if entry is None:
            known = UNUSED_OPS.get(op)
            if known:
                self._ann(mosi[0].ss, mosi[0].es, ANN_INSTR, [known + ' (not used by the driver)', known, '0x%02X' % op])
                self._ann(ss, es, ANN_SUMMARY, ['%s 0x%02X: valid chip command the driver does not use' % (known, op), known])
                self._warn(ss, es, '%s (0x%02X) is not part of the single-I/O command set the driver uses' % (known, op), 'UNUSED')
            else:
                self._ann(mosi[0].ss, mosi[0].es, ANN_INSTR, ['UNKNOWN 0x%02X' % op, '?', '0x%02X' % op])
                self._warn(ss, es, 'Unknown opcode 0x%02X (the datasheet warns that unlisted codes may enter a hidden mode)' % op, 'OPCODE?')
            return

        name, alen, dummy, direction, needs_wel, starts_busy = entry
        if op == 0xAB:
            name = 'RDP' if len(mosi) == 1 else 'RES'
            dummy = 0 if name == 'RDP' else 3
            direction = None if name == 'RDP' else 'miso'
        self._ann(mosi[0].ss, mosi[0].es, ANN_INSTR, [name, name[:3], '0x%02X' % op])

        self.blocked = False
        wel_before = self.wel
        # ---- state checks before the command takes effect -------------------
        if self.dp and name not in ('RDP', 'RES'):
            self._warn(ss, es, '%s in deep power-down: only RDP / RES are accepted' % name, 'DP')
            return
        if self.busy and name not in ALLOWED_WHILE_BUSY:
            self._warn(mosi[0].ss, mosi[0].es,
                       '%s while the previous program/erase/WRSR may still be running (WIP not seen low): ignored by the chip' % name,
                       'BUSY')
        if needs_wel and not self.wel:
            self._warn(mosi[0].ss, mosi[0].es, '%s without a preceding WREN: WEL is not set, the chip ignores it' % name, 'NO-WREN')

        # ---- generic frame parse -------------------------------------------
        addr = None
        pos = 1
        if alen:
            if len(mosi) < 1 + alen:
                self._warn(ss, es, '%s frame has %d byte(s); needs opcode + %d address byte(s)' % (name, len(mosi), alen), 'SHORT')
                return
            addr = (mosi[1].val << 16) | (mosi[2].val << 8) | mosi[3].val
            pos = 1 + alen
            self._addr_ann(mosi, addr)
        if dummy and len(mosi) >= pos + dummy:
            self._ann(mosi[pos].ss, mosi[pos + dummy - 1].es, ANN_INFO, ['%d dummy byte(s)' % dummy, 'dummy'])
        pos += dummy
        payload = None
        span = None
        if direction == 'mosi':
            payload = [b.val for b in mosi[pos:]]
            span = (mosi[pos].ss, mosi[-1].es) if payload else None
        elif direction == 'miso':
            if not miso:
                self._warn(ss, es, 'MISO channel required to decode %s data' % name, 'MISO?')
                return
            payload = [b.val for b in miso[pos:]]
            span = (miso[pos].ss, miso[-1].es) if payload else None
        if span:
            self._ann(span[0], span[1], ANN_DATA, ['%d byte(s): %s' % (len(payload), _hex(payload)), '%d B' % len(payload), _hex(payload, 2)])

        handler = getattr(self, '_op_' + name.replace('/', '_'), None)
        if handler:
            handler(ss, es, mosi, miso, addr, payload)
        else:
            self._ann(ss, es, ANN_SUMMARY, [name, name])
        self._py(ss, es, name, (addr, bytes(payload) if payload else b''))

        # ---- state updates after the command ---------------------------------
        if needs_wel:
            self.wel = False
        if starts_busy and (wel_before or not needs_wel) and not self.blocked:
            self.busy = True

    # ---- helpers -------------------------------------------------------

    def _addr_ann(self, mosi, addr):
        text = 'Address 0x%06X (sector %d, 64K block %d, page 0x%04X, +0x%02X)' % (
            addr, addr >> 12, addr >> 16, addr >> 8, addr & 0xFF)
        short = '0x%06X' % addr
        self._ann(mosi[1].ss, mosi[3].es, ANN_ADDR, [text, 'A=' + short, short])

    def _check_protected(self, ss, es, name, addr, length):
        if self.bp:
            first = _protected_from(self.bp)
            if addr + max(length, 1) > first:
                self.blocked = True
                self._warn(ss, es, '%s at 0x%06X reaches the area protected by BP=%d (from 0x%06X): the chip will not change it and sets %s_FAIL'
                           % (name, addr, self.bp, first, 'P' if name == 'PP' else 'E'), 'PROTECTED')

    # ---- per-command handlers ----------------------------------------------

    def _op_WREN(self, ss, es, mosi, miso, addr, payload):
        self.wel = True
        self._ann(ss, es, ANN_SUMMARY, ['WREN: write enable latch set', 'WREN'])

    def _op_WRDI(self, ss, es, mosi, miso, addr, payload):
        self.wel = False
        self._ann(ss, es, ANN_SUMMARY, ['WRDI: write enable latch cleared', 'WRDI'])

    def _op_RDID(self, ss, es, mosi, miso, addr, payload):
        ids = payload[:3]
        ok = ids == [0xC2, 0x20, 0x18]
        self._ann(miso[1].ss, miso[-1].es, ANN_STATUS,
                  ['JEDEC ID %s%s' % (_hex(ids), ' = Macronix MX25L12845E' if ok else ''), 'ID %s' % _hex(ids)])
        self._ann(ss, es, ANN_SUMMARY, ['RDID: %s%s' % (_hex(ids), '' if ok else ' (expected C2 20 18)'), 'RDID'])
        if len(ids) == 3 and not ok:
            self._warn(ss, es, 'JEDEC ID %s, expected C2 20 18 (MX25L12845E)' % _hex(ids), 'ID')

    def _op_RES(self, ss, es, mosi, miso, addr, payload):
        self.dp = False
        v = payload[0] if payload else None
        self._ann(ss, es, ANN_SUMMARY, ['RES: electronic ID 0x%02X%s' % (v, '' if v == 0x17 else ' (expected 0x17)') if v is not None else 'RES', 'RES'])
        if v is not None and v != 0x17:
            self._warn(ss, es, 'Electronic ID 0x%02X, expected 0x17' % v, 'ID')

    def _op_RDP(self, ss, es, mosi, miso, addr, payload):
        self.dp = False
        self._ann(ss, es, ANN_SUMMARY, ['RDP: release from deep power-down (wait tRES2 = 100 us)', 'RDP'])

    def _op_REMS(self, ss, es, mosi, miso, addr, payload):
        if len(payload) >= 2:
            self._ann(ss, es, ANN_SUMMARY, ['REMS: manufacturer 0x%02X, device 0x%02X' % (payload[0], payload[1]), 'REMS'])
            if (payload[0], payload[1]) not in ((0xC2, 0x17), (0x17, 0xC2)):
                self._warn(ss, es, 'REMS returned %s, expected C2 17' % _hex(payload), 'ID')
        else:
            self._ann(ss, es, ANN_SUMMARY, ['REMS', 'REMS'])

    def _op_DP(self, ss, es, mosi, miso, addr, payload):
        self.dp = True
        self._ann(ss, es, ANN_SUMMARY, ['DP: deep power-down (only RDP / RES accepted until released)', 'DP'])

    def _op_RDSR(self, ss, es, mosi, miso, addr, payload):
        if not payload:
            self._warn(ss, es, 'RDSR without a data byte', 'SHORT')
            return
        first, last = payload[0], payload[-1]
        self._ann(miso[1].ss, miso[1].es, ANN_STATUS, ['Status 0x%02X: %s' % (first, _sr_text(first)), 'SR 0x%02X' % first])
        if len(payload) > 1 and last != first:
            self._ann(miso[-1].ss, miso[-1].es, ANN_STATUS, ['Status 0x%02X: %s' % (last, _sr_text(last)), 'SR 0x%02X' % last])
        self.bp = (last >> 2) & 0xF
        if not last & 0x01:
            self.busy = False
        if len(payload) > 1:
            self._ann(ss, es, ANN_SUMMARY, ['RDSR poll x%d: 0x%02X → 0x%02X (%s)' % (len(payload), first, last, _sr_text(last)), 'RDSR x%d' % len(payload)])
        else:
            self._ann(ss, es, ANN_SUMMARY, ['RDSR: 0x%02X (%s)' % (first, _sr_text(first)), 'RDSR 0x%02X' % first])

    def _op_WRSR(self, ss, es, mosi, miso, addr, payload):
        if not payload:
            self._warn(ss, es, 'WRSR without a data byte', 'SHORT')
            return
        v = payload[0]
        self._ann(mosi[1].ss, mosi[1].es, ANN_STATUS, ['New status 0x%02X: %s' % (v, _sr_cfg_text(v)), 'SR 0x%02X' % v])
        self._ann(ss, es, ANN_SUMMARY, ['WRSR: 0x%02X (%s)' % (v, _sr_cfg_text(v)), 'WRSR 0x%02X' % v])
        if v & 0x40:
            self._warn(ss, es, 'WRSR sets QE: WP# becomes SIO2 and hardware protection is disabled', 'QE')
        if v & 0x80:
            self._warn(ss, es, 'WRSR sets SRWD: with WP# low the status register becomes read-only', 'SRWD')
        self.bp = (v >> 2) & 0xF

    def _read_summary(self, name, ss, es, addr, payload):
        n = len(payload)
        if self.otp:
            self._ann(ss, es, ANN_SUMMARY, ['%s (secured OTP) 0x%03X, %d byte(s): %s' % (name, addr & 0x1FF, n, _hex(payload)), '%s OTP [%d]' % (name, n)])
            if addr > 0x1FF or addr + n > 0x200:
                self._warn(ss, es, 'OTP read 0x%06X + %d leaves the 512-byte secured OTP (0x000-0x1FF)' % (addr, n), 'OTP-RANGE')
        else:
            self._ann(ss, es, ANN_SUMMARY, ['%s 0x%06X, %d byte(s): %s' % (name, addr, n, _hex(payload)) if n else '%s 0x%06X, no data' % (name, addr), '%s [%d]' % (name, n), name])
            if addr + n > ARRAY_SIZE:
                self._ann(ss, es, ANN_INFO, ['read wraps from the end of the array to address 0', 'wrap'])

    def _op_READ(self, ss, es, mosi, miso, addr, payload):
        self._read_summary('READ', ss, es, addr, payload)

    def _op_FAST_READ(self, ss, es, mosi, miso, addr, payload):
        self._read_summary('FAST_READ', ss, es, addr, payload)

    def _op_RDSFDP(self, ss, es, mosi, miso, addr, payload):
        sig = ''
        if addr == 0 and len(payload) >= 4:
            sig = ' (signature %s)' % ('"SFDP" ok' if bytes(payload[:4]) == b'SFDP' else 'WRONG, expected "SFDP"')
        self._ann(ss, es, ANN_SUMMARY, ['RDSFDP 0x%06X, %d byte(s): %s%s' % (addr, len(payload), _hex(payload), sig), 'RDSFDP [%d]' % len(payload)])

    def _op_PP(self, ss, es, mosi, miso, addr, payload):
        n = len(payload)
        if self.otp:
            self._ann(ss, es, ANN_SUMMARY, ['PP (secured OTP) 0x%03X, %d byte(s): %s' % (addr & 0x1FF, n, _hex(payload)), 'PP OTP [%d]' % n])
            if addr < 0x10:
                self._warn(ss, es, 'OTP program at 0x%03X touches the ESN bytes 0x000-0x00F' % addr, 'ESN')
            if addr > 0x1FF or addr + n > 0x200:
                self._warn(ss, es, 'OTP program 0x%06X + %d leaves the 512-byte secured OTP' % (addr, n), 'OTP-RANGE')
            return
        self._ann(ss, es, ANN_SUMMARY, ['PP 0x%06X, %d byte(s): %s' % (addr, n, _hex(payload)), 'PP [%d]' % n, 'PP'])
        if n == 0:
            self._warn(ss, es, 'PP without data bytes', 'SHORT')
        if n > PAGE_SIZE:
            self._warn(ss, es, 'PP sends %d bytes; a page holds 256, the extra bytes overwrite the start of the page' % n, 'PAGE-OVERFLOW')
        elif (addr & 0xFF) + n > PAGE_SIZE:
            self._warn(ss, es, 'PP from 0x%06X with %d byte(s) crosses the page boundary: bytes wrap to the start of the page 0x%06X'
                       % (addr, n, addr & ~0xFF), 'PAGE-WRAP')
        self._check_protected(ss, es, 'PP', addr, min(n, PAGE_SIZE))

    def _erase(self, name, size, ss, es, addr):
        base = addr & ~(size - 1)
        self._ann(ss, es, ANN_SUMMARY, ['%s 0x%06X (erases 0x%06X-0x%06X, %d KiB)' % (name, addr, base, base + size - 1, size // 1024), name])
        self._check_protected(ss, es, name, base, size)

    def _op_SE(self, ss, es, mosi, miso, addr, payload):
        self._erase('SE', 4096, ss, es, addr)

    def _op_BE32K(self, ss, es, mosi, miso, addr, payload):
        self._erase('BE32K', 32768, ss, es, addr)

    def _op_BE(self, ss, es, mosi, miso, addr, payload):
        self._erase('BE', 65536, ss, es, addr)

    def _op_CE(self, ss, es, mosi, miso, addr, payload):
        self._ann(ss, es, ANN_SUMMARY, ['CE: chip erase (80 s typ, 200 s max)', 'CE'])
        if self.bp:
            self.blocked = True
            self._warn(ss, es, 'CE while BP=%d: the chip only accepts a chip erase when BP3:0 = 0' % self.bp, 'CE-BP')

    def _op_ENSO(self, ss, es, mosi, miso, addr, payload):
        self.otp = True
        self._ann(ss, es, ANN_SUMMARY, ['ENSO: enter the 4 Kbit secured OTP (array access unavailable)', 'ENSO'])

    def _op_EXSO(self, ss, es, mosi, miso, addr, payload):
        self.otp = False
        self._ann(ss, es, ANN_SUMMARY, ['EXSO: exit the secured OTP', 'EXSO'])

    def _op_RDSCUR(self, ss, es, mosi, miso, addr, payload):
        if not payload:
            return
        v = payload[-1]
        self._ann(miso[1].ss, miso[-1].es, ANN_STATUS, ['Security 0x%02X: %s' % (v, _scur_text(v)), 'SCUR 0x%02X' % v])
        self._ann(ss, es, ANN_SUMMARY, ['RDSCUR: 0x%02X (%s)' % (v, _scur_text(v)), 'RDSCUR 0x%02X' % v])
        if v & 0x60:
            self._ann(miso[-1].ss, miso[-1].es, ANN_INFO, ['%s set: clear with CLSR (30h)' % ('E_FAIL' if v & 0x40 else 'P_FAIL'), 'FAIL'])

    def _op_WRSCUR(self, ss, es, mosi, miso, addr, payload):
        self._ann(ss, es, ANN_SUMMARY, ['WRSCUR: lock down the secured OTP (LDSO = 1) — irreversible', 'WRSCUR'])
        self._warn(ss, es, 'WRSCUR permanently locks the 4 Kbit secured OTP', 'LOCK')

    def _op_CLSR(self, ss, es, mosi, miso, addr, payload):
        self._ann(ss, es, ANN_SUMMARY, ['CLSR: clear P_FAIL / E_FAIL', 'CLSR'])
