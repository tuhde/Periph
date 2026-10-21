"""Macronix MX25L12845E 128-Mbit (16 MiB) serial NOR flash (specs/memory/mx25l12845e.md).

``MX25L12845EMinimal`` is a byte-addressable store with transparent erase:
``read_bytes``, an erase-aware ``write_bytes`` (read-modify-erase-write per
4 KiB sector, programming only what changed) and ``erase_sector``.
``MX25L12845EFull`` adds the raw program / erase commands, status and block
protection, deep power-down, SFDP and the secured OTP, and implements the
common ``BlockDevice`` contract (specs/feature_block_device.md): 512-byte
blocks, 4 KiB erase units, ``needs_erase() == True``.

Plain single-I/O SPI Mode 0 over a bare SPI connection (``write`` and
``write_read``; CS is held low for each frame by the connection).
"""

import time

from ...storage.block_device import (
    BlockDevice, NotReady, OutOfRange, NotErased, WriteProtected, Timeout, Io,
)

if hasattr(time, "ticks_ms"):
    ticks_ms = time.ticks_ms
    ticks_diff = time.ticks_diff
    _sleep_ms = time.sleep_ms
else:
    def ticks_ms():
        return int(time.monotonic() * 1000)

    def ticks_diff(a, b):
        return a - b

    def _sleep_ms(ms):
        time.sleep(ms / 1000.0)

_WREN = 0x06
_RDID = 0x9F
_RES = 0xAB
_REMS = 0x90
_RDSR = 0x05
_WRSR = 0x01
_READ = 0x03
_FAST_READ = 0x0B
_PP = 0x02
_SE = 0x20
_BE32K = 0x52
_BE64K = 0xD8
_CE = 0xC7
_DP = 0xB9
_RDP = 0xAB
_RDSFDP = 0x5A
_ENSO = 0xB1
_EXSO = 0xC1
_RDSCUR = 0x2B
_WRSCUR = 0x2F
_CLSR = 0x30

_SR_WIP = 0x01
_SR_WEL = 0x02
_SR_BP_MASK = 0x3C
_SR_BP_SHIFT = 2
_SCUR_P_FAIL = 0x20
_SCUR_E_FAIL = 0x40

_T_WRSR_MS = 150
_T_PP_MS = 10
_T_SE_MS = 400
_T_BE_MS = 3000
_T_CE_MS = 250000
_READ_CHUNK = 128   # keeps frames within the buffers of some connections

PAGE_SIZE = 256
SECTOR_SIZE = 4096
SIZE = 16 * 1024 * 1024


def _addr(cmd, address):
    return bytes((cmd, (address >> 16) & 0xFF, (address >> 8) & 0xFF, address & 0xFF))


class MX25L12845EMinimal:
    """MX25L12845E — minimal driver: byte-addressed read, erase-aware write.

    Runs the init sequence (RDP, RDID check C2 20 18, wait until not busy).

    Args:
        connection: SPI connection (``write``, ``write_read``).
        init_timeout_ms: How long init waits for a chip that is still busy (a reset
            during a chip erase can leave it busy for up to 200 s).

    Raises:
        NotReady: The JEDEC ID did not match.
        Timeout: The chip stayed busy.
    """

    SIZE = SIZE
    PAGE_SIZE = PAGE_SIZE
    SECTOR_SIZE = SECTOR_SIZE

    def __init__(self, connection, init_timeout_ms=_T_CE_MS):
        self._c = connection
        self._sector = bytearray(SECTOR_SIZE)
        self._send(bytes((_RDP,)))   # leave deep power-down if a previous session left it there
        _sleep_ms(1)                 # tRES2 = 100 us
        if self.jedec_id() != b"\xc2\x20\x18":
            raise NotReady("MX25L12845E not found (JEDEC ID {})".format(self.jedec_id().hex()))
        self._wait_ready(init_timeout_ms)   # a reset during an erase can leave the chip busy

    # ---- bus primitives --------------------------------------------------

    def _send(self, frame):
        self._c.write(frame)

    def _status(self):
        return self._c.write_read(bytes((_RDSR,)), 1)[0]

    def _wren(self):
        self._send(bytes((_WREN,)))
        if not self._status() & _SR_WEL:
            raise Io("WEL not set after WREN")   # the next command would be ignored

    def _wait_ready(self, timeout_ms):
        start = ticks_ms()
        while self._status() & _SR_WIP:
            if ticks_diff(ticks_ms(), start) >= timeout_ms:
                raise Timeout("chip busy")
            if timeout_ms > 20:
                _sleep_ms(1)   # long operations: do not hammer the bus

    def _check_fail(self):
        scur = self._c.write_read(bytes((_RDSCUR,)), 1)[0]
        if scur & (_SCUR_P_FAIL | _SCUR_E_FAIL):
            self._send(bytes((_CLSR,)))
            if self._status() & _SR_BP_MASK:
                raise WriteProtected("program/erase hit the block-protected area")
            raise Io("program/erase failed (security register 0x{:02X})".format(scur))

    def _erase_cmd(self, cmd, address, timeout_ms):
        self._wren()
        self._send(_addr(cmd, address))
        self._wait_ready(timeout_ms)
        self._check_fail()

    def _program(self, address, data):
        self._wren()
        self._send(_addr(_PP, address) + bytes(data))
        self._wait_ready(_T_PP_MS)
        self._check_fail()

    def _read_raw(self, cmd, address, dummy, length):
        frame = _addr(cmd, address) + (b"\x00" if dummy else b"")
        return self._c.write_read(frame, length)

    def _do_read(self, address, length):
        out = bytearray()
        while length:
            n = _READ_CHUNK if length > _READ_CHUNK else length
            out += self._c.write_read(_addr(_READ, address), n)
            address += n
            length -= n
        return bytes(out)

    # ---- Minimal API -----------------------------------------------------

    def jedec_id(self):
        """The three RDID bytes (manufacturer, memory type, density)."""
        return bytes(self._c.write_read(bytes((_RDID,)), 3))

    def size(self):
        """Capacity in bytes (16 777 216)."""
        return SIZE

    def read_bytes(self, address, length):
        """Read ``length`` bytes starting at ``address`` (READ, 03h), any length and alignment.

        Raises:
            OutOfRange: The range leaves the array.
        """
        if address < 0 or address + length > SIZE:
            raise OutOfRange("read beyond end")
        return self._do_read(address, length)

    def erase_sector(self, address):
        """Erase the 4 KiB sector containing ``address`` (SE, 20h)."""
        if address < 0 or address >= SIZE:
            raise OutOfRange("address beyond end")
        self._erase_cmd(_SE, address, _T_SE_MS)

    def write_bytes(self, address, data):
        """Erase-aware write; blocks until the chip is ready.

        Per 4 KiB sector: if only 1->0 transitions are needed just the changed
        pages are programmed, otherwise the sector is erased and rewritten with
        the merged contents.

        Raises:
            OutOfRange: The range leaves the array.
            WriteProtected: The chip reported a failure while block-protect bits are set.
        """
        data = bytes(data)
        length = len(data)
        if address < 0 or address + length > SIZE:
            raise OutOfRange("write beyond end")
        pos = 0
        while pos < length:
            base = address & ~(SECTOR_SIZE - 1)
            off = address - base
            n = min(SECTOR_SIZE - off, length - pos)
            chunk = data[pos:pos + n]
            old = self._do_read(base, SECTOR_SIZE)
            needs_erase = False
            for i in range(n):
                if (old[off + i] & chunk[i]) != chunk[i]:   # would need a 0 -> 1 transition
                    needs_erase = True
                    break
            if not needs_erase:
                done = 0
                while done < n:
                    a = address + done
                    part = min(PAGE_SIZE - (a & (PAGE_SIZE - 1)), n - done)
                    if old[off + done:off + done + part] != chunk[done:done + part]:
                        self._program(a, chunk[done:done + part])
                    done += part
            else:
                merged = bytearray(old)
                merged[off:off + n] = chunk
                self._erase_cmd(_SE, base, _T_SE_MS)
                for page in range(0, SECTOR_SIZE, PAGE_SIZE):
                    block = bytes(merged[page:page + PAGE_SIZE])
                    if block != b"\xff" * PAGE_SIZE:   # blank pages need no program
                        self._program(base + page, block)
            address += n
            pos += n


class MX25L12845EFull(MX25L12845EMinimal, BlockDevice):
    """MX25L12845E — full driver: raw commands, protection, power, OTP, SFDP; implements ``BlockDevice``.

    Args:
        connection: SPI connection.
        auto_erase: ``BlockDevice.write`` erases sectors itself when a 0->1
            transition is needed; with False (default) it raises ``NotErased``.
        init_timeout_ms: See ``MX25L12845EMinimal``.
    """

    def __init__(self, connection, auto_erase=False, init_timeout_ms=_T_CE_MS):
        super().__init__(connection, init_timeout_ms)
        self._auto_erase = auto_erase

    # ---- raw operations --------------------------------------------------

    def fast_read(self, address, length):
        """FAST_READ (0Bh) of ``length`` bytes."""
        if address < 0 or address + length > SIZE:
            raise OutOfRange("read beyond end")
        out = bytearray()
        while length:
            n = _READ_CHUNK if length > _READ_CHUNK else length
            out += self._read_raw(_FAST_READ, address, True, n)
            address += n
            length -= n
        return bytes(out)

    def page_program(self, address, data):
        """One PP of 1..256 bytes that must not cross a 256-byte page boundary; the bytes must be erased."""
        n = len(data)
        if n == 0 or n > PAGE_SIZE or address < 0 or address + n > SIZE:
            raise OutOfRange("bad page program range")
        if (address & (PAGE_SIZE - 1)) + n > PAGE_SIZE:
            raise OutOfRange("page program would wrap inside the page")
        self._program(address, data)

    def erase_block_32k(self, address):
        """Erase the 32 KiB block containing ``address`` (BE32K, 52h)."""
        if address < 0 or address >= SIZE:
            raise OutOfRange("address beyond end")
        self._erase_cmd(_BE32K, address, _T_BE_MS)

    def erase_block_64k(self, address):
        """Erase the 64 KiB block containing ``address`` (BE, D8h)."""
        if address < 0 or address >= SIZE:
            raise OutOfRange("address beyond end")
        self._erase_cmd(_BE64K, address, _T_BE_MS)

    def chip_erase(self):
        """Erase the whole chip (CE, C7h); 80 s typical, 200 s max; needs BP3:0 == 0."""
        self._wren()
        self._send(bytes((_CE,)))
        self._wait_ready(_T_CE_MS)
        self._check_fail()

    # ---- status / protection ---------------------------------------------

    def read_status(self):
        """The status register."""
        return self._status()

    def write_status(self, value):
        """WREN + WRSR; waits for ``tW``."""
        self._wren()
        self._send(bytes((_WRSR, value & 0xFF)))
        self._wait_ready(_T_WRSR_MS)

    def protection(self):
        """BP3:0 (0..15) from the status register."""
        return (self._status() & _SR_BP_MASK) >> _SR_BP_SHIFT

    def set_protection(self, bp):
        """Set BP3:0 (non-volatile); QE and SRWD are preserved."""
        if not 0 <= bp <= 15:
            raise OutOfRange("BP must be 0..15")
        self.write_status((self._status() & ~_SR_BP_MASK) | (bp << _SR_BP_SHIFT))

    def is_busy(self):
        """True while a program, erase or WRSR is running (WIP)."""
        return bool(self._status() & _SR_WIP)

    def wait_ready(self, timeout_ms):
        """Wait until WIP = 0; raises Timeout."""
        self._wait_ready(timeout_ms)

    def read_security(self):
        """The security register (RDSCUR)."""
        return self._c.write_read(bytes((_RDSCUR,)), 1)[0]

    def clear_fail_flags(self):
        """CLSR: clear P_FAIL / E_FAIL."""
        self._send(bytes((_CLSR,)))

    # ---- power / identification ------------------------------------------

    def deep_power_down(self):
        """DP: enter deep power-down (only RDP / RES are accepted afterwards)."""
        self._send(bytes((_DP,)))

    def release_deep_power_down(self):
        """RDP: leave deep power-down and wait ``tRES2``."""
        self._send(bytes((_RDP,)))
        _sleep_ms(1)

    def electronic_id(self):
        """RES electronic ID (17h)."""
        return self._c.write_read(bytes((_RES, 0, 0, 0)), 1)[0]

    def device_ids(self):
        """REMS: ``(manufacturer, device)`` = (0xC2, 0x17)."""
        r = self._c.write_read(bytes((_REMS, 0, 0, 0)), 2)
        return r[0], r[1]

    def read_sfdp(self, address, length):
        """RDSFDP (5Ah): ``length`` bytes of the SFDP table from ``address``."""
        out = bytearray()
        while length:
            n = _READ_CHUNK if length > _READ_CHUNK else length
            out += self._read_raw(_RDSFDP, address, True, n)
            address += n
            length -= n
        return bytes(out)

    # ---- secured OTP (4 Kbit) --------------------------------------------

    def read_otp(self, offset, length):
        """Read ``length`` bytes at OTP offset ``offset`` (0..511)."""
        if offset < 0 or offset + length > 512:
            raise OutOfRange("OTP range")
        self._send(bytes((_ENSO,)))
        try:
            return self._do_read(offset, length)
        finally:
            self._send(bytes((_EXSO,)))

    def program_otp(self, offset, data):
        """Program OTP bytes at ``offset`` (16..511 only: 0..15 are the ESN area); one-time programmable."""
        data = bytes(data)
        if offset < 16 or not data or offset + len(data) > 512:
            raise OutOfRange("OTP range (0..15 is the ESN area)")
        self._send(bytes((_ENSO,)))
        try:
            pos = 0
            while pos < len(data):
                n = min(PAGE_SIZE - (offset & (PAGE_SIZE - 1)), len(data) - pos)
                self._program(offset, data[pos:pos + n])
                offset += n
                pos += n
        finally:
            self._send(bytes((_EXSO,)))

    def lock_otp(self):
        """Lock the customer OTP area for good (WRSCUR, sets LDSO) — IRREVERSIBLE."""
        self._wren()
        self._send(bytes((_WRSCUR,)))
        self._wait_ready(_T_WRSR_MS)

    def serial_number(self):
        """The 16 bytes of OTP 000h-00Fh (the factory ESN when ``read_security() & 1``; blank 0xFF otherwise)."""
        return self.read_otp(0, 16)

    # ---- BlockDevice -----------------------------------------------------

    def block_size(self):
        return 512

    def block_count(self):
        return SIZE // 512

    def erase_size(self):
        return SECTOR_SIZE

    def needs_erase(self):
        return True

    def read(self, block, count=1):
        """Read ``count`` blocks of 512 bytes."""
        if block < 0 or block + count > self.block_count():
            raise OutOfRange("read beyond end")
        return self._do_read(block * 512, count * 512)

    def write(self, block, data):
        """Program ``len(data) // 512`` blocks; see the class docstring for ``auto_erase``."""
        data = bytes(data)
        count = len(data) // 512
        if count == 0 or len(data) % 512 or block < 0 or block + count > self.block_count():
            raise OutOfRange("write range")
        address = block * 512
        if self._auto_erase:
            return self.write_bytes(address, data)
        pos = 0
        while pos < len(data):   # program page by page, only where no 0 -> 1 transition is needed
            n = min(PAGE_SIZE - (address & (PAGE_SIZE - 1)), len(data) - pos)
            old = self._do_read(address, n)
            new = data[pos:pos + n]
            for i in range(n):
                if (old[i] & new[i]) != new[i]:
                    raise NotErased("block needs an erase before it can be programmed")
            if old != new:
                self._program(address, new)
            address += n
            pos += n

    def erase(self, block, count=1):
        """Erase ``count`` blocks; the range must be 4 KiB-aligned (multiples of 8 blocks)."""
        if block % 8 or count % 8 or block < 0 or block + count > self.block_count():
            raise OutOfRange("erase range must be 4 KiB aligned")
        address = block * 512
        left = count * 512
        while left:
            if (address & 0xFFFF) == 0 and left >= 65536:
                self.erase_block_64k(address)
                step = 65536
            elif (address & 0x7FFF) == 0 and left >= 32768:
                self.erase_block_32k(address)
                step = 32768
            else:
                self.erase_sector(address)
                step = SECTOR_SIZE
            address += step
            left -= step

    def sync(self):
        """Wait until the chip is idle."""
        self._wait_ready(_T_CE_MS)

    def info(self):
        """Geometry and capabilities (see ``BlockDevice.info``)."""
        return {
            "block_size": 512, "block_count": self.block_count(), "erase_size": SECTOR_SIZE,
            "needs_erase": True, "read_only": self.protection() >= 8, "bad_blocks": False,
            "name": "MX25L12845E",
        }
