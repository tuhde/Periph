from .block_device import BlockDevice, OutOfRange, NotErased


class RamBlockDevice(BlockDevice):
    """RAM-backed BlockDevice mock for tests.

    With ``erase_blocks > 1`` it models NOR-like semantics: erased blocks read
    0xFF and programming an un-erased block while ``auto_erase`` is False raises
    NotErased.

    Args:
        block_size: Bytes per block.
        block_count: Number of blocks.
        erase_blocks: Blocks per erase unit (1 = no erase needed).
        auto_erase: Erase automatically before programming.
    """

    def __init__(self, block_size, block_count, erase_blocks=1, auto_erase=False):
        self._bs = block_size
        self._bc = block_count
        self._eb = erase_blocks or 1
        self._auto = auto_erase
        self._mem = bytearray(block_size * block_count)
        if self._eb > 1:
            for i in range(len(self._mem)):
                self._mem[i] = 0xFF
        self._erased = [True] * block_count

    def block_size(self):
        return self._bs

    def block_count(self):
        return self._bc

    def erase_size(self):
        return self._bs * self._eb

    def needs_erase(self):
        return self._eb > 1

    def read(self, block, count=1):
        if block < 0 or block + count > self._bc:
            raise OutOfRange("read beyond end")
        return bytes(self._mem[block * self._bs:(block + count) * self._bs])

    def write(self, block, data):
        count = len(data) // self._bs
        if block < 0 or block + count > self._bc:
            raise OutOfRange("write beyond end")
        if self.needs_erase():
            for i in range(count):
                b = block + i
                if not self._erased[b]:
                    if not self._auto:
                        raise NotErased("block {} not erased".format(b))
                    self.erase(b - b % self._eb, self._eb)
        self._mem[block * self._bs:block * self._bs + len(data)] = data
        for i in range(count):
            self._erased[block + i] = False

    def erase(self, block, count=1):
        if not self.needs_erase():
            return
        if block % self._eb or count % self._eb or block + count > self._bc:
            raise OutOfRange("unaligned erase")
        for i in range(block * self._bs, (block + count) * self._bs):
            self._mem[i] = 0xFF
        for i in range(count):
            self._erased[block + i] = True

    def sync(self):
        pass

    def info(self):
        return {
            "block_size": self._bs, "block_count": self._bc, "erase_size": self.erase_size(),
            "needs_erase": self.needs_erase(), "read_only": False, "bad_blocks": False, "name": "RAM",
        }
