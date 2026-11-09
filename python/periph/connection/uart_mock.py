def _crc8(data):
    crc = 0
    for byte in data:
        for _ in range(8):
            if ((crc >> 7) ^ (byte & 1)):
                crc = ((crc << 1) ^ 0x07) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
            byte >>= 1
    return crc


class UARTConnectionMock:
    """Scripted TMC2208-style UART chip simulator for unit tests.

    Behaves like a Trinamic single-wire UART slave: ``write()`` accepts 8-byte
    write datagrams (stores the register, bumps IFCNT) and 4-byte read
    requests (queues the reply from ``regs``). With ``echo=True`` every
    transmitted byte is also queued back, as on the shared PDN_UART wire.
    Everything written is kept in ``datagrams``; ``regs`` maps register
    address to the 32-bit value returned on reads and updated by writes.
    """

    def __init__(self, echo=True):
        self.echo = echo
        self.regs = {0x06: 0x21000100, 0x02: 0}
        self.datagrams = []
        self.writes = []
        self.corrupt_next_reply = False
        self._rx = bytearray()

    def write(self, data):
        data = bytes(data)
        self.datagrams.append(data)
        if self.echo:
            self._rx.extend(data)
        if len(data) == 8 and data[2] & 0x80:
            reg = data[2] & 0x7F
            value = int.from_bytes(data[3:7], 'big')
            self.regs[reg] = value
            self.writes.append((reg, value))
            self.regs[0x02] = (self.regs[0x02] + 1) & 0xFF
        elif len(data) == 4:
            reg = data[2] & 0x7F
            body = bytes([0x05, 0xFF, reg]) + \
                self.regs.get(reg, 0).to_bytes(4, 'big')
            reply = body + bytes([_crc8(body)])
            if self.corrupt_next_reply:
                reply = reply[:7] + bytes([reply[7] ^ 0xFF])
                self.corrupt_next_reply = False
            self._rx.extend(reply)

    def read(self, n):
        out = bytes(self._rx[:n])
        del self._rx[:len(out)]
        return out

    def write_read(self, data, n):
        self.write(data)
        return self.read(n)

    def flush(self):
        self._rx = bytearray()

    def last_write(self, reg):
        """Return the last value written to a register, or None."""
        values = [v for r, v in self.writes if r == reg]
        return values[-1] if values else None
