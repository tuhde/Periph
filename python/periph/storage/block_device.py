"""Common block-device contract for storage chips (SD, SD NAND, SPI NOR, SPI NAND).

See specs/feature_block_device.md. Only the Full chip classes implement
``BlockDevice``. Periph does not ship a filesystem; ``BlockDeviceVFS`` adapts a
BlockDevice to the MicroPython / CircuitPython ``vfs.mount`` block protocol.
"""


class BlockError(Exception):
    """Base class of all block-device errors."""


class NotReady(BlockError):
    """Device absent or initialisation failed."""


class OutOfRange(BlockError):
    """Block index/count beyond block_count(), or unaligned erase()."""


class NotErased(BlockError):
    """Program to an un-erased block while auto_erase is off."""


class WriteProtected(BlockError):
    """Card WP / status-register block protection."""


class BadBlock(BlockError):
    """Target erase unit is marked bad (NAND)."""


class Ecc(BlockError):
    """Uncorrectable ECC error on read (NAND)."""


class Crc(BlockError):
    """SD command/data CRC mismatch."""


class Timeout(BlockError):
    """Busy never cleared."""


class Io(BlockError):
    """Transport failure."""


class BlockDevice:
    """Contract implemented by the Full class of every storage chip.

    Units are bytes and block indices; a block is the read/program unit exposed
    to a filesystem.
    """

    def block_size(self):
        """Logical block size in bytes (read/program unit)."""
        raise NotImplementedError

    def block_count(self):
        """Number of addressable logical blocks."""
        raise NotImplementedError

    def erase_size(self):
        """Erase unit in bytes; equals block_size() when needs_erase() is False."""
        raise NotImplementedError

    def needs_erase(self):
        """True if a block must be erased before it is programmed."""
        raise NotImplementedError

    def read(self, block, count=1):
        """Read ``count`` consecutive blocks.

        Args:
            block: First block index.
            count: Number of blocks.

        Returns:
            bytes: ``count * block_size()`` bytes.
        """
        raise NotImplementedError

    def write(self, block, data):
        """Program ``len(data) // block_size()`` consecutive blocks starting at ``block``."""
        raise NotImplementedError

    def erase(self, block, count=1):
        """Erase the erase units covering ``[block, block + count)``. No-op on SD."""
        raise NotImplementedError

    def sync(self):
        """Block until all pending program/erase work has finished."""
        raise NotImplementedError

    def info(self):
        """Geometry and capabilities.

        Returns:
            dict: ``block_size``, ``block_count``, ``erase_size``, ``needs_erase``,
            ``read_only``, ``bad_blocks``, ``name``.
        """
        raise NotImplementedError

    def is_bad(self, unit):
        """Optional (``info()['bad_blocks']``): whether erase unit ``unit`` is bad."""
        return False

    def mark_bad(self, unit):
        """Optional: persist a bad-block mark for erase unit ``unit``."""


class BlockDeviceVFS:
    """Adapter exposing a BlockDevice through MicroPython's block-device protocol.

    ``vfs.mount(vfs.VfsFat(BlockDeviceVFS(dev)), "/sd")`` mounts a FAT volume.
    Only whole-block transfers are supported for writes (``off`` must be 0 or
    omitted), which is what FAT uses; LittleFS needs partial programs and is
    not supported yet.

    Args:
        dev: A BlockDevice.
    """

    def __init__(self, dev):
        self._dev = dev

    def readblocks(self, block_num, buf, offset=0):
        """Read into ``buf`` starting at byte ``offset`` of block ``block_num``."""
        bs = self._dev.block_size()
        n = len(buf)
        first = block_num + offset // bs
        skip = offset % bs
        count = (skip + n + bs - 1) // bs
        data = self._dev.read(first, count)
        buf[:] = data[skip:skip + n]

    def writeblocks(self, block_num, buf, offset=None):
        """Write whole blocks from ``buf`` starting at ``block_num``."""
        if offset:
            raise NotImplementedError("partial block writes")
        self._dev.write(block_num, bytes(buf))

    def ioctl(self, op, arg):
        """Block-device control: 1 init, 2 deinit, 3 sync, 4 count, 5 block size, 6 erase."""
        if op in (1, 2):
            return 0
        if op == 3:
            self._dev.sync()
            return 0
        if op == 4:
            return self._dev.block_count()
        if op == 5:
            return self._dev.block_size()
        if op == 6:
            self._dev.erase(arg, 1)
            return 0
        return None
