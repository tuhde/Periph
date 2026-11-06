"""Register table and field decoding shared by the MCP23008 (I2C) and
MCP23S08 (SPI) sigrok decoders. Both chips have an identical register map."""

REGISTERS = {
    0x00: 'IODIR',
    0x01: 'IPOL',
    0x02: 'GPINTEN',
    0x03: 'DEFVAL',
    0x04: 'INTCON',
    0x05: 'IOCON',
    0x06: 'GPPU',
    0x07: 'INTF',
    0x08: 'INTCAP',
    0x09: 'GPIO',
    0x0A: 'OLAT',
}

NREGS = len(REGISTERS)        # sequential access rolls over 0x0A -> 0x00
READONLY = {0x07, 0x08}


def next_reg(reg, step=1):
    """Register address after `step` auto-incremented bytes (roll-over 0x0A -> 0x00)."""
    return (reg + step) % NREGS


def _pins(byte, bit_set=True):
    return ','.join('GP%d' % i for i in range(8) if bool((byte >> i) & 1) == bit_set) or 'none'


def decode_reg(reg, byte):
    """Return the symbolic detail string for a register value."""
    if reg == 0x00:
        return 'IN=%s OUT=%s' % (_pins(byte, True), _pins(byte, False))
    if reg == 0x06:
        return 'PU=%s' % _pins(byte, True)
    if reg == 0x05:
        flags = [n for n, m in (('SEQOP', 0x20), ('DISSLW', 0x10), ('HAEN', 0x08),
                                ('ODR', 0x04), ('INTPOL', 0x02)) if byte & m]
        return ' '.join(flags) or 'none'
    if reg == 0x01:
        return 'INV=%s' % _pins(byte, True)
    if reg == 0x02:
        return 'INTEN=%s' % _pins(byte, True)
    if reg == 0x04:
        return 'CMP_DEFVAL=%s' % _pins(byte, True)
    if reg == 0x07:
        return 'INT=%s' % _pins(byte, True)
    if reg == 0x08:
        return 'CAP=0x%02X' % byte
    if reg in (0x09, 0x0A):
        return ','.join('GP%d=%d' % (i, (byte >> i) & 1) for i in range(8))
    return '0x%02X' % byte


def ann_reg(reg, byte):
    name = REGISTERS.get(reg, 'REG 0x%02X' % reg)
    return '%s: %s' % (name, decode_reg(reg, byte))


def reg_warnings(reg, byte):
    """Warnings for a write of `byte` to `reg`."""
    out = []
    if reg in READONLY:
        out.append('Write to read-only register %s' % REGISTERS[reg])
    if reg == 0x05 and (byte & 0xC1):
        out.append('IOCON write with unimplemented bits 7:6/0 set (0x%02X)' % byte)
    return out


def with_short(strings):
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
