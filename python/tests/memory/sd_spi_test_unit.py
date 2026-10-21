import sys

from periph.chips.memory.sd_spi import SDSPIMinimal, SDSPIFull, crc7, crc16, CARD_SDHC, CARD_SDV1, CARD_SDV2
from periph.storage.block_device import (
    BlockError, NotReady, OutOfRange, Crc, NotErased, BlockDeviceVFS,
)
from periph.storage.ram_block_device import RamBlockDevice

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def raises(exc, fn):
    try:
        fn()
    except exc:
        return True
    except Exception:
        return False
    return False


class SdModel:
    """Behavioural model of an SD card in SPI mode, seen through the SPI
    connection interface plus the optional extensions. Models the init
    sequence (needs 80 idle clocks first), command CRC7 checking, R1/R3/R7
    responses, CSD/CID, single/multi block read and write with tokens, busy and
    CRC16."""

    def __init__(self, v2=True, sdhc=True):
        self.v2 = v2
        self.sdhc = sdhc
        self.csize_v2 = 7  # (7+1) * 512 KiB = 4 MiB = 8192 blocks
        self.acmd41_calls = 0
        self.acmd41_needed = 3
        self.got_idle_clocks = False
        self.selected = False
        self.in_idle = False
        self.crc_on = False
        self.corrupt_read_crc = False
        self.speed = 0
        self.mem = bytearray(8192 * 512)
        self.cmd_log = []
        self.cs_toggles_inside_command = 0
        self.state = 'CMD'
        self.cmd_buf = bytearray()
        self.out = []
        self.app_next = False
        self.streaming = False
        self.stream_block = 0
        self.w_block = 0
        self.multi_write = False
        self.w_data = bytearray()

    # ---- connection interface ----
    def idle_clocks(self, n):
        if not self.selected and n >= 80:
            self.got_idle_clocks = True

    def set_speed(self, hz):
        self.speed = hz
        return True

    def select(self):
        self.selected = True

    def deselect(self):
        self.selected = False
        if self.state == 'CMD' and self.cmd_buf:
            self.cs_toggles_inside_command += 1

    def write(self, data):
        for b in data:
            self._feed(b)

    def read(self, n):
        return bytes(self._feed(0xFF) for _ in range(n))

    def write_read(self, data, n):
        self.write(data)
        return self.read(n)

    # ---- model ----
    def _push_block(self, data):
        self.out.append(0xFE)
        self.out.extend(data)
        c = crc16(data)
        if self.corrupt_read_crc:
            c ^= 1
        self.out.append(c >> 8)
        self.out.append(c & 0xFF)

    def _block_of(self, arg):
        return arg if self.sdhc else arg // 512

    def _do_command(self):
        was_app = self.app_next
        self.app_next = False
        cb = self.cmd_buf
        cmd = cb[0] & 0x3F
        arg = (cb[1] << 24) | (cb[2] << 16) | (cb[3] << 8) | cb[4]
        self.cmd_log.append((0x80 | cmd) if was_app else cmd)
        if (cb[5] >> 1) != crc7(cb[:5]):
            self.out.extend([0xFF, 0x09])
            return
        self.out.append(0xFF)  # Ncr gap
        idle = 0x01 if self.in_idle else 0x00
        if was_app:
            if cmd == 41:
                self.acmd41_calls += 1
                if self.acmd41_calls >= self.acmd41_needed:
                    self.in_idle = False
                    self.out.append(0x00)
                else:
                    self.out.append(0x01)
            else:
                self.out.append(idle)
            return
        if cmd == 0:
            if not self.got_idle_clocks:
                self.out.clear()
                return
            self.in_idle = True
            self.out.append(0x01)
        elif cmd == 8:
            if not self.v2:
                self.out.append(0x05)
            else:
                self.out.extend([0x01, 0, 0, 0x01, 0xAA])
        elif cmd == 9:
            csd = bytearray(16)
            if self.sdhc:
                csd[0] = 0x40
                csd[7] = (self.csize_v2 >> 16) & 0x3F
                csd[8] = (self.csize_v2 >> 8) & 0xFF
                csd[9] = self.csize_v2 & 0xFF
            else:
                csd[5] = 9
                csd[6] = 0x03
                csd[7] = 0xFF
                csd[8] = 0xC0
                csd[9] = 0x03
                csd[10] = 0x80
            self.out.extend([0x00, 0xFF])
            self._push_block(csd)
        elif cmd == 10:
            cid = bytes([0x03, ord('S'), ord('D'), ord('T'), ord('E'), ord('S'), ord('T'), ord('1'),
                         0x10, 0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x23, 0x01])
            self.out.extend([0x00, 0xFF])
            self._push_block(cid)
        elif cmd == 12:
            self.streaming = False
            self.out = [0xFF, 0x00, 0x00, 0x00, 0xFF]
        elif cmd == 13:
            self.out.extend([0x00, 0x00])
        elif cmd == 16:
            self.out.append(idle)
        elif cmd == 17:
            self.out.extend([0x00, 0xFF])
            b = self._block_of(arg)
            self._push_block(self.mem[b * 512:(b + 1) * 512])
        elif cmd == 18:
            self.out.append(0x00)
            self.streaming = True
            self.stream_block = self._block_of(arg)
        elif cmd in (24, 25):
            self.out.append(0x00)
            self.state = 'WTOKEN'
            self.w_block = self._block_of(arg)
            self.multi_write = (cmd == 25)
        elif cmd in (32, 33):
            self.out.append(0x00)
        elif cmd == 38:
            self.out.extend([0x00, 0x00, 0x00])
        elif cmd == 55:
            self.app_next = True
            self.out.append(idle)
        elif cmd == 58:
            self.out.extend([idle, 0xC0 if self.sdhc else 0x80, 0xFF, 0x80, 0x00])
        elif cmd == 59:
            self.crc_on = bool(arg & 1)
            self.out.append(idle)
        else:
            self.out.append(0x04)  # illegal command

    def _feed(self, b):
        if self.state == 'WTOKEN':
            if b in (0xFE, 0xFC):
                self.state = 'WDATA'
                self.w_data = bytearray()
            elif b == 0xFD:
                self.state = 'CMD'
                self.out.extend([0xFF, 0x00, 0x00, 0xFF])
                return 0xFF
        elif self.state == 'WDATA':
            self.w_data.append(b)
            if len(self.w_data) == 514:
                got = (self.w_data[512] << 8) | self.w_data[513]
                ok = (not self.crc_on) or got == crc16(self.w_data[:512])
                if ok:
                    self.mem[self.w_block * 512:(self.w_block + 1) * 512] = self.w_data[:512]
                    self.w_block += 1
                self.out.extend([0xE5 if ok else 0xEB, 0x00, 0x00, 0xFF])
                self.state = 'WTOKEN' if self.multi_write else 'CMD'
                return 0xFF  # the data response follows on the NEXT byte clock
        else:
            if self.cmd_buf or (b & 0xC0) == 0x40:
                self.cmd_buf.append(b)
                if len(self.cmd_buf) == 6:
                    self._do_command()
                    self.cmd_buf = bytearray()
                return 0xFF
        if self.out:
            return self.out.pop(0)
        if self.streaming and self.state == 'CMD':
            self._push_block(self.mem[self.stream_block * 512:(self.stream_block + 1) * 512])
            self.stream_block += 1
            return self.out.pop(0)
        return 0xFF


def test_crc_helpers():
    check_true('crc16 check value', crc16(b'123456789') == 0x31C3)
    check_true('crc7 of CMD0 frame -> 0x95', ((crc7(bytes([0x40, 0, 0, 0, 0])) << 1) | 1) == 0x95)


def test_init_v2_sdhc():
    m = SdModel()
    sd = SDSPIMinimal(m)
    check_true('SDHC init succeeds', sd.card_type() == CARD_SDHC)
    check_true('80 idle clocks sent before CMD0', m.got_idle_clocks)
    check_true('block count from CSD v2', sd.block_count() == 8192)
    check_true('speed raised to 12 MHz after init', m.speed == 12000000)
    check_true('no CS release mid-command', m.cs_toggles_inside_command == 0)


def test_init_v1():
    m = SdModel(v2=False, sdhc=False)
    sd = SDSPIMinimal(m)
    check_true('SDv1 detected', sd.card_type() == CARD_SDV1)
    check_true('block count from CSD v1 (1 GiB)', sd.block_count() == 2097152)
    check_true('CMD16 sent for byte-addressed card', 16 in m.cmd_log)


def test_no_card():
    class Dead:
        def idle_clocks(self, n): pass
        def set_speed(self, hz): return True
        def select(self): pass
        def deselect(self): pass
        def read(self, n): return b'\xff' * n
        def write(self, d): pass
    check_true('no card -> NotReady', raises(NotReady, lambda: SDSPIMinimal(Dead())))


def test_minimal_block_io():
    m = SdModel()
    sd = SDSPIMinimal(m)
    w = bytes((i * 7 + 3) & 0xFF for i in range(512))
    sd.write_block(5, w)
    check_true('block round-trip', sd.read_block(5) == w)
    buf = bytearray(512)
    sd.read_block(5, buf)
    check_true('read_block into buffer', bytes(buf) == w)
    check_true('read past end -> OutOfRange', raises(OutOfRange, lambda: sd.read_block(8192)))


def test_full_multi():
    m = SdModel()
    sd = SDSPIFull(m)
    check_true('geometry', sd.block_size() == 512 and sd.erase_size() == 512 and not sd.needs_erase())
    check_true('info().name from CID PNM', sd.info()['name'] == 'TEST1')
    w = bytes((k ^ (k >> 8)) & 0xFF for k in range(4 * 512))
    sd.write(100, w)
    sd.sync()
    check_true('multi-block round-trip', sd.read(100, 4) == w)
    sd.erase(100, 4)
    check_true('multi write past end -> OutOfRange', raises(OutOfRange, lambda: sd.write(8190, w)))
    check_true('OCR CCS bit', sd.ocr() & 0x40000000)
    check_true('is_present', sd.is_present())
    check_true('card is SDHC', sd.card_type() == CARD_SDHC)


def test_crc_error():
    m = SdModel()
    sd = SDSPIFull(m)
    m.corrupt_read_crc = True
    check_true('corrupt read CRC16 -> Crc', raises(Crc, lambda: sd.read_block(1)))
    sd.set_crc(False)
    check_true('CRC ignored when off', len(sd.read_block(1)) == 512)


def test_blockdevice_contract():
    m = SdModel()
    sd = SDSPIFull(m)
    ram = RamBlockDevice(512, 64)
    for name, dev in (('SD', sd), ('RAM', ram)):
        w = bytes([0xA0]) * 512
        dev.write(3, w)
        check_true(name + ' contract: write+read', dev.read(3, 1) == w)
        check_true(name + ' contract: out of range', raises(OutOfRange, lambda: dev.read(dev.block_count(), 1)))


def test_ram_nor_semantics():
    nor = RamBlockDevice(512, 16, erase_blocks=8)
    check_true('NOR: erased reads 0xFF', nor.read(0, 1) == b'\xff' * 512)
    nor.write(0, b'\x00' * 512)
    check_true('NOR: program without erase -> NotErased', raises(NotErased, lambda: nor.write(0, b'\x01' * 512)))
    nor.erase(0, 8)
    nor.write(0, b'\x02' * 512)
    check_true('NOR: erase then program', nor.read(0, 1) == b'\x02' * 512)
    check_true('NOR: unaligned erase -> OutOfRange', raises(OutOfRange, lambda: nor.erase(1, 8)))
    auto = RamBlockDevice(512, 16, erase_blocks=8, auto_erase=True)
    auto.write(2, b'\x01' * 512)
    auto.write(2, b'\x02' * 512)
    check_true('NOR: auto_erase rewrites', auto.read(2, 1) == b'\x02' * 512)


def test_vfs_shim():
    ram = RamBlockDevice(512, 16)
    vfs = BlockDeviceVFS(ram)
    check_true('ioctl count/size', vfs.ioctl(4, 0) == 16 and vfs.ioctl(5, 0) == 512)
    vfs.writeblocks(1, bytes(range(256)) * 4)
    buf = bytearray(512)
    vfs.readblocks(1, buf)
    check_true('VFS writeblocks/readblocks', bytes(buf) == bytes(range(256)) * 2)
    part = bytearray(4)
    vfs.readblocks(1, part, 2)
    check_true('VFS partial read at offset', bytes(part) == bytes([2, 3, 4, 5]))


test_crc_helpers()
test_init_v2_sdhc()
test_init_v1()
test_no_card()
test_minimal_block_io()
test_full_multi()
test_crc_error()
test_blockdevice_contract()
test_ram_nor_semantics()
test_vfs_shim()

print('\n{} passed, {} failed'.format(passed, failed))
if failed:
    sys.exit(1)
