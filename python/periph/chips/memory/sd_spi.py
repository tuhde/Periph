"""SD / SDHC / SDXC / microSD card in SPI mode (specs/memory/sd_spi.md).

``SDSPIMinimal`` runs the SPI-mode init sequence in the constructor and offers
single 512-byte block reads and writes. ``SDSPIFull`` adds multi-block
transfers, erase, CID/CSD/OCR access and CRC checking, and implements the
common ``BlockDevice`` contract (specs/feature_block_device.md).

The connection must be an SPI connection with the optional extensions
``idle_clocks``, ``set_speed``, ``select`` / ``deselect`` (specs/transport_spi.md)
and must clock 0xFF on MOSI while reading. Cards are 3.3 V only.
"""

import time

from ...storage.block_device import (
    BlockDevice, NotReady, OutOfRange, Crc, Timeout, Io,
)

if hasattr(time, "ticks_ms"):
    ticks_ms = time.ticks_ms
    ticks_diff = time.ticks_diff
else:
    def ticks_ms():
        return int(time.monotonic() * 1000)

    def ticks_diff(a, b):
        return a - b

_CRC16_T = (0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
            0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF)

CARD_NONE = 0
CARD_SDV1 = 1
CARD_SDV2 = 2
CARD_SDHC = 3

_INIT_TIMEOUT_MS = 1000
_TOKEN_TIMEOUT_MS = 200
_BUSY_TIMEOUT_MS = 500
_ERASE_TIMEOUT_MS = 30000


def crc7(data):
    """CRC7 (polynomial x^7 + x^3 + 1) of ``data``, not shifted."""
    crc = 0
    for b in data:
        for _ in range(8):
            crc <<= 1
            if (b ^ crc) & 0x80:
                crc ^= 0x09
            b = (b << 1) & 0xFF
    return crc & 0x7F


def crc16(data):
    """CRC16-CCITT (polynomial 0x1021, initial value 0) of ``data``."""
    crc = 0
    for b in data:
        crc ^= b << 8
        crc = ((crc << 4) & 0xFFFF) ^ _CRC16_T[crc >> 12]
        crc = ((crc << 4) & 0xFFFF) ^ _CRC16_T[crc >> 12]
    return crc


class SDSPIMinimal:
    """SD card in SPI mode — Minimal driver.

    Runs the init sequence (CMD0, CMD8, ACMD41, CMD58, ...) and offers single
    512-byte block reads and writes.

    Args:
        connection: SPI connection with ``idle_clocks``, ``set_speed``,
            ``select`` and ``deselect``.
        speed_hz: Data-phase clock after init (init always runs at 400 kHz).
        crc: Enable CMD59 and send/verify CRC16 on data blocks.

    Raises:
        NotReady: No card responded, or the card is unsupported.
        Timeout: The card never left the idle state.
    """

    BLOCK_SIZE = 512

    def __init__(self, connection, speed_hz=12_000_000, crc=False):
        self._c = connection
        self._speed = speed_hz
        self._want_crc = crc
        self._crc_on = False
        self._type = CARD_NONE
        self._block_addressing = False
        self._block_count = 0
        self._csd = bytes(16)
        self.begin()

    # ---- bus primitives ------------------------------------------------

    def _rx(self):
        return self._c.read(1)[0]

    def _wait_ready(self, ms):
        start = ticks_ms()
        while True:
            if self._rx() == 0xFF:
                return
            if ticks_diff(ticks_ms(), start) >= ms:
                raise Timeout("card busy")

    def _release(self):
        self._c.deselect()
        self._c.idle_clocks(8)  # let the card free MISO

    def _command(self, cmd, arg):
        """Send a command with CS already selected; return R1 (0xFF on no response)."""
        if cmd != 12 and cmd != 0:
            self._wait_ready(_BUSY_TIMEOUT_MS)
        frame = bytearray(6)
        frame[0] = 0x40 | cmd
        frame[1] = (arg >> 24) & 0xFF
        frame[2] = (arg >> 16) & 0xFF
        frame[3] = (arg >> 8) & 0xFF
        frame[4] = arg & 0xFF
        frame[5] = (crc7(frame[:5]) << 1) | 1
        self._c.write(bytes(frame))
        if cmd == 12:
            self._rx()  # discard the stuff byte that follows CMD12
        for _ in range(10):  # Ncr <= 8 bytes
            r = self._rx()
            if not r & 0x80:
                return r
        return 0xFF

    def _app_command(self, cmd, arg):
        r = self._command(55, 0)
        if r > 1:
            return r
        return self._command(cmd, arg)

    def _read_data(self, length):
        start = ticks_ms()
        while True:
            token = self._rx()
            if token != 0xFF:
                break
            if ticks_diff(ticks_ms(), start) >= _TOKEN_TIMEOUT_MS:
                raise Timeout("no data token")
        if token != 0xFE:
            raise Io("data error token 0x{:02X}".format(token))
        data = self._c.read(length)
        crc = self._c.read(2)
        if self._crc_on and ((crc[0] << 8) | crc[1]) != crc16(data):
            raise Crc("data CRC16 mismatch")
        return data

    def _write_data(self, token, data):
        self._wait_ready(_BUSY_TIMEOUT_MS)
        self._c.write(bytes((token,)))
        if token == 0xFD:
            return
        self._c.write(bytes(data))
        if self._crc_on:
            c = crc16(data)
            self._c.write(bytes((c >> 8, c & 0xFF)))
        else:
            self._c.write(b"\xff\xff")
        resp = self._rx() & 0x1F
        if resp == 0x05:
            return
        if resp == 0x0B:
            raise Crc("write CRC rejected")
        raise Io("write rejected 0x{:02X}".format(resp))

    def _read_register(self, cmd):
        self._c.select()
        try:
            if self._command(cmd, 0) != 0:
                raise Io("CMD{} failed".format(cmd))
            return self._read_data(16)
        finally:
            self._release()

    def _address(self, block):
        return block if self._block_addressing else block * self.BLOCK_SIZE

    # ---- init ------------------------------------------------------------

    def begin(self):
        """Run the SPI-mode init sequence (also usable to re-init a swapped card).

        Raises:
            NotReady: No card responded, or the card is unsupported.
            Timeout: The card never left the idle state.
        """
        self._type = CARD_NONE
        self._block_count = 0
        self._crc_on = False
        self._c.set_speed(400_000)
        self._c.deselect()
        self._c.idle_clocks(80)

        idle = False
        for _ in range(10):  # CMD0 with CS low enters SPI mode; fresh cards need a few tries
            self._c.select()
            try:
                idle = self._command(0, 0) == 0x01
            finally:
                self._release()
            if idle:
                break
        if not idle:
            raise NotReady("no card responded to CMD0")

        self._c.select()
        try:
            v2 = False
            r = self._command(8, 0x1AA)
            if r == 0x01:
                r7 = self._c.read(4)
                if (r7[2] & 0x0F) != 0x01 or r7[3] != 0xAA:
                    raise NotReady("CMD8 echo mismatch")
                v2 = True
            elif not r & 0x04:  # anything but "illegal command" (v1 card) is a failure
                raise NotReady("CMD8 failed")

            start = ticks_ms()
            while True:
                r = self._app_command(41, 0x40000000 if v2 else 0)
                if r == 0x00:
                    break
                if ticks_diff(ticks_ms(), start) >= _INIT_TIMEOUT_MS:
                    raise Timeout("ACMD41 never completed")

            self._type = CARD_SDV2 if v2 else CARD_SDV1
            self._block_addressing = False
            if v2 and self._command(58, 0) == 0x00:
                ocr = self._c.read(4)
                if ocr[0] & 0x40:
                    self._block_addressing = True
                    self._type = CARD_SDHC
            if not self._block_addressing:
                self._command(16, self.BLOCK_SIZE)
            if self._want_crc and self._command(59, 1) == 0x00:
                self._crc_on = True
        except Exception:
            self._type = CARD_NONE
            raise
        finally:
            self._release()

        self._c.set_speed(self._speed)
        self._csd = self._read_register(9)
        self._block_count = self._csd_blocks(self._csd)

    @staticmethod
    def _csd_blocks(csd):
        if (csd[0] >> 6) == 1:  # CSD v2: C_SIZE = CSD[69:48]
            csize = ((csd[7] & 0x3F) << 16) | (csd[8] << 8) | csd[9]
            return (csize + 1) * 1024
        read_bl_len = csd[5] & 0x0F  # CSD v1
        csize = ((csd[6] & 0x03) << 10) | (csd[7] << 2) | (csd[8] >> 6)
        mult = ((csd[9] & 0x03) << 1) | (csd[10] >> 7)
        return ((csize + 1) << (mult + 2) << read_bl_len) // 512

    # ---- Minimal API -----------------------------------------------------

    def block_count(self):
        """Capacity in 512-byte blocks (from the CSD)."""
        return self._block_count

    def sector_count(self):
        """Alias of ``block_count()`` (FatFs terminology)."""
        return self._block_count

    def card_type(self):
        """Card generation: ``CARD_SDV1``, ``CARD_SDV2`` or ``CARD_SDHC``."""
        return self._type

    def read_block(self, block, buf=None):
        """Read one 512-byte block (CMD17).

        Args:
            block: Block index.
            buf: Optional writable buffer of 512 bytes to fill.

        Returns:
            bytes: The block data (``buf`` content when given).

        Raises:
            OutOfRange: ``block`` beyond the card.
        """
        if block < 0 or block >= self._block_count:
            raise OutOfRange("block {} out of range".format(block))
        self._c.select()
        try:
            if self._command(17, self._address(block)) != 0:
                raise Io("CMD17 failed")
            data = self._read_data(self.BLOCK_SIZE)
        finally:
            self._release()
        if buf is not None:
            buf[:] = data
            return buf
        return data

    def write_block(self, block, data):
        """Write one 512-byte block (CMD24).

        Args:
            block: Block index.
            data: 512 bytes.

        Raises:
            OutOfRange: ``block`` beyond the card.
        """
        if len(data) != self.BLOCK_SIZE:
            raise ValueError("data must be 512 bytes")
        if block < 0 or block >= self._block_count:
            raise OutOfRange("block {} out of range".format(block))
        self._c.select()
        try:
            if self._command(24, self._address(block)) != 0:
                raise Io("CMD24 failed")
            self._write_data(0xFE, data)
            self._wait_ready(_BUSY_TIMEOUT_MS)
        finally:
            self._release()


class SDSPIFull(SDSPIMinimal, BlockDevice):
    """SD card in SPI mode — Full driver, implements ``BlockDevice``.

    Adds multi-block transfers (CMD18/CMD25), erase, CID/CSD/OCR access and
    CRC checking (on by default).

    Args:
        connection: SPI connection with the optional extensions.
        speed_hz: Data-phase clock after init.
        crc: CMD59 and CRC16 checking (default True).
    """

    def __init__(self, connection, speed_hz=12_000_000, crc=True):
        super().__init__(connection, speed_hz, crc)
        try:
            cid = self._read_register(10)
            self._name = bytes(cid[3:8]).decode("ascii", "replace")  # PNM = CID[103:64]
        except Exception:
            self._name = ""

    def block_size(self):
        return self.BLOCK_SIZE

    def erase_size(self):
        return self.BLOCK_SIZE

    def needs_erase(self):
        return False

    def read(self, block, count=1):
        """Read ``count`` consecutive blocks (CMD18 for ``count > 1``)."""
        if count < 1:
            return b""
        if block < 0 or block + count > self._block_count:
            raise OutOfRange("read beyond end")
        if count == 1:
            return self.read_block(block)
        out = bytearray()
        self._c.select()
        try:
            if self._command(18, self._address(block)) != 0:
                raise Io("CMD18 failed")
            try:
                for _ in range(count):
                    out += self._read_data(self.BLOCK_SIZE)
            finally:
                self._command(12, 0)
                self._wait_ready(_BUSY_TIMEOUT_MS)
        finally:
            self._release()
        return bytes(out)

    def write(self, block, data):
        """Program ``len(data) // 512`` consecutive blocks (CMD25 for more than one)."""
        count = len(data) // self.BLOCK_SIZE
        if count == 0 or len(data) % self.BLOCK_SIZE:
            raise ValueError("data must be a multiple of 512 bytes")
        if block < 0 or block + count > self._block_count:
            raise OutOfRange("write beyond end")
        if count == 1:
            return self.write_block(block, data)
        mv = memoryview(data)
        self._c.select()
        try:
            self._app_command(23, count)  # pre-erase hint; cards may ignore it
            if self._command(25, self._address(block)) != 0:
                raise Io("CMD25 failed")
            err = None
            try:
                for i in range(count):
                    self._write_data(0xFC, mv[i * 512:(i + 1) * 512])
            except Exception as e:
                err = e
            self._wait_ready(_BUSY_TIMEOUT_MS)
            self._write_data(0xFD, b"")
            self._rx()  # stuff byte after the stop token
            self._wait_ready(_BUSY_TIMEOUT_MS)
            if err is not None:
                raise err
        finally:
            self._release()

    def erase(self, block, count=1):
        """Erase ``count`` blocks (CMD32/33/38); contents afterwards are card dependent."""
        if count < 1:
            return
        if block < 0 or block + count > self._block_count:
            raise OutOfRange("erase beyond end")
        self._c.select()
        try:
            if (self._command(32, self._address(block)) != 0
                    or self._command(33, self._address(block + count - 1)) != 0
                    or self._command(38, 0) != 0):
                raise Io("erase command failed")
            self._wait_ready(_ERASE_TIMEOUT_MS)
        finally:
            self._release()

    def sync(self):
        """Wait until the card has finished programming."""
        self._c.select()
        try:
            self._wait_ready(_BUSY_TIMEOUT_MS)
        finally:
            self._release()

    def info(self):
        """Geometry and capabilities (see ``BlockDevice.info``)."""
        return {
            "block_size": self.BLOCK_SIZE, "block_count": self._block_count,
            "erase_size": self.BLOCK_SIZE, "needs_erase": False, "read_only": False,
            "bad_blocks": False, "name": self._name,
        }

    def cid(self):
        """The 16-byte CID register (manufacturer, name, revision, serial, date)."""
        return bytes(self._read_register(10))

    def csd(self):
        """The 16-byte CSD register as read during init."""
        return bytes(self._csd)

    def ocr(self):
        """The OCR word (CMD58)."""
        self._c.select()
        try:
            if self._command(58, 0) != 0:
                raise Io("CMD58 failed")
            o = self._c.read(4)
        finally:
            self._release()
        return (o[0] << 24) | (o[1] << 16) | (o[2] << 8) | o[3]

    def set_crc(self, enabled):
        """Enable/disable CMD59 and CRC16 checking."""
        self._c.select()
        try:
            if self._command(59, 1 if enabled else 0) != 0:
                raise Io("CMD59 failed")
        finally:
            self._release()
        self._crc_on = bool(enabled)

    def set_speed(self, hz):
        """Change the SCK frequency after init; returns False if the platform cannot."""
        return self._c.set_speed(hz)

    def is_present(self):
        """True if a card responds to CMD13."""
        self._c.select()
        try:
            r = self._command(13, 0)
            if r != 0xFF:
                self._rx()  # R2 second byte
        finally:
            self._release()
        return r != 0xFF
