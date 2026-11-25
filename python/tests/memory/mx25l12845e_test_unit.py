import sys

from periph.chips.memory.mx25l12845e import MX25L12845EMinimal, MX25L12845EFull
from periph.storage.block_device import (
    NotReady, OutOfRange, NotErased, WriteProtected, Timeout,
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


class FlashModel:
    """Behavioural MX25L12845E: 16 MiB array programmable 1 -> 0 only, page wrap on PP, 4/32/64 KiB and
    chip erase, WEL/WIP handling (WIP reads high for a few RDSR polls after every program/erase),
    BP block protect with P_FAIL/E_FAIL, secured OTP, deep power-down, RES/REMS/RDID/SFDP.
    Every command frame is recorded."""

    def __init__(self):
        self.mem = bytearray(b'\xff' * (16 * 1024 * 1024))
        self.otp = bytearray(b'\xff' * 512)
        for i in range(16):
            self.otp[i] = 0xA0 + i
        self.in_otp = False
        self.wel = False
        self.dp = False
        self.otp_locked = False
        self.sr = 0
        self.scur = 0x01
        self.id = bytes((0xC2, 0x20, 0x18))
        self.busy_polls = 0
        self.busy_after_op = 2
        self.stuck_busy = False
        self.cmds = []
        self.pp = self.se = self.be32 = self.be64 = self.ce = 0

    def _protected_from(self):
        bp = (self.sr >> 2) & 0xF
        if bp == 0:
            return 16 * 1024 * 1024
        if bp >= 8:
            return 0
        return 16 * 1024 * 1024 - (65536 << bp)

    def _is_protected(self, a, n):
        return a + n > self._protected_from()

    def _start_busy(self):
        self.busy_polls = self.busy_after_op
        self.wel = False

    def write(self, d):
        self._frame(bytes(d), 0)

    def write_read(self, d, n):
        return self._frame(bytes(d), n)

    def _frame(self, d, n):
        op = d[0]
        self.cmds.append(op)
        out = bytearray(n)
        if self.dp and op != 0xAB:
            return bytes(out)
        busy = self.stuck_busy or self.busy_polls > 0
        if busy and op not in (0x05, 0x2B, 0x9F, 0x30):
            return bytes(out)
        a = ((d[1] << 16) | (d[2] << 8) | d[3]) if len(d) >= 4 else 0
        if op == 0x06:
            self.wel = True
        elif op == 0x04:
            self.wel = False
        elif op == 0x9F:
            for i in range(min(n, 3)):
                out[i] = self.id[i]
        elif op == 0x05:
            out[0] = (self.sr & 0xFC) | (2 if self.wel else 0) | (1 if busy else 0)
            if self.busy_polls > 0:
                self.busy_polls -= 1
        elif op == 0x01:
            if self.wel and len(d) >= 2:
                self.sr = d[1] & 0xFC
                self._start_busy()
        elif op in (0x03, 0x0B, 0x5A):
            for i in range(n):
                if op == 0x5A:
                    out[i] = (a + i) & 0xFF
                elif self.in_otp:
                    out[i] = self.otp[(a + i) & 0x1FF]
                else:
                    out[i] = self.mem[(a + i) & 0xFFFFFF]
        elif op == 0x02:
            if self.wel:
                data = d[4:]
                self.pp += 1
                if self.in_otp:
                    if self.otp_locked:
                        self.scur |= 0x20
                    else:
                        for i, b in enumerate(data):
                            self.otp[(a + i) & 0x1FF] &= b
                elif self._is_protected(a & ~255, 256):
                    self.scur |= 0x20
                else:
                    page = a & ~255
                    for i, b in enumerate(data):
                        self.mem[page + ((a + i) & 255)] &= b
                self._start_busy()
        elif op in (0x20, 0x52, 0xD8):
            if self.wel:
                size = 4096 if op == 0x20 else 32768 if op == 0x52 else 65536
                base = a & ~(size - 1)
                if op == 0x20:
                    self.se += 1
                elif op == 0x52:
                    self.be32 += 1
                else:
                    self.be64 += 1
                if self._is_protected(base, size):
                    self.scur |= 0x40
                else:
                    self.mem[base:base + size] = b'\xff' * size
                self._start_busy()
        elif op in (0xC7, 0x60):
            if self.wel:
                self.ce += 1
                if (self.sr >> 2) & 0xF:
                    self.scur |= 0x40
                else:
                    self.mem[:] = b'\xff' * len(self.mem)
                self._start_busy()
        elif op == 0xB9:
            self.dp = True
        elif op == 0xAB:
            self.dp = False
            if n:
                out[0] = 0x17
        elif op == 0x90:
            if n >= 2:
                out[0], out[1] = 0xC2, 0x17
        elif op == 0xB1:
            self.in_otp = True
        elif op == 0xC1:
            self.in_otp = False
        elif op == 0x2B:
            out[0] = self.scur
        elif op == 0x2F:
            if self.wel:
                self.scur |= 0x02
                self.otp_locked = True
                self._start_busy()
        elif op == 0x30:
            self.scur &= ~0x60
        return bytes(out)


def count(m, op):
    return sum(1 for o in m.cmds if o == op)


def test_init():
    m = FlashModel()
    f = MX25L12845EMinimal(m)
    check_true('init ok on C2 20 18', f.jedec_id() == b'\xc2\x20\x18')
    check_true('size 16 MiB', f.size() == 16 * 1024 * 1024)
    wrong = FlashModel()
    wrong.id = bytes((0xC2, 0x20, 0x17))
    check_true('wrong ID -> NotReady', raises(NotReady, lambda: MX25L12845EMinimal(wrong)))
    stuck = FlashModel()
    stuck.stuck_busy = True
    check_true('chip stuck busy -> Timeout at init', raises(Timeout, lambda: MX25L12845EMinimal(stuck, init_timeout_ms=20)))
    asleep = FlashModel()
    asleep.dp = True
    MX25L12845EMinimal(asleep)
    check_true('init releases deep power-down (RDP)', not asleep.dp)


def test_minimal_write():
    m = FlashModel()
    f = MX25L12845EMinimal(m)
    pattern = bytes((i * 7 + 3) & 0xFF for i in range(600))
    f.write_bytes(0x1F0, pattern)
    check_true('write across a page boundary into blank flash', f.read_bytes(0x1F0, 600) == pattern)
    check_true('blank flash: no erase needed', m.se == 0)
    check_true('neighbouring bytes untouched', m.mem[0x1EF] == 0xFF and m.mem[0x1F0 + 600] == 0xFF)
    pp_before = m.pp
    f.write_bytes(0x1F0, pattern)
    check_true('identical rewrite programs and erases nothing', m.pp == pp_before and m.se == 0)
    more = bytes(b & 0xF0 for b in pattern)
    f.write_bytes(0x1F0, more)
    check_true('1->0 only rewrite needs no erase', f.read_bytes(0x1F0, 600) == more and m.se == 0)
    f.write_bytes(0x1F0, b'\xff' * 10)
    check_true('0->1 needs a sector erase', m.se == 1 and f.read_bytes(0x1F0, 10) == b'\xff' * 10)
    check_true('rest of the sector survived the erase', f.read_bytes(0x200, 20) == more[16:36])
    big = bytes((i ^ (i >> 8)) & 0xFF for i in range(9000))
    f.write_bytes(0x2FF0, big)
    check_true('multi-sector write + read back', f.read_bytes(0x2FF0, 9000) == big)
    check_true('write past the end', raises(OutOfRange, lambda: f.write_bytes(SIZE - 2, big[:5])))
    f.erase_sector(0x2000)
    check_true('erase_sector', m.mem[0x2000] == 0xFF)


SIZE = MX25L12845EMinimal.SIZE


def test_protection():
    m = FlashModel()
    f = MX25L12845EFull(m)
    check_true('BP = 0 at power-up', f.protection() == 0)
    f.set_protection(8)
    check_true('BP reads back', f.protection() == 8)
    check_true('program into protected area -> WriteProtected', raises(WriteProtected, lambda: f.page_program(0x100, b'\x00')))
    check_true('protected data unchanged', m.mem[0x100] == 0xFF)
    check_true('fail flags cleared by the driver (CLSR)', (f.read_security() & 0x60) == 0)
    check_true('erase of protected sector -> WriteProtected', raises(WriteProtected, lambda: f.erase_sector(0)))
    check_true('info().read_only for BP >= 8', f.info()['read_only'])
    check_true('BP > 15 rejected', raises(OutOfRange, lambda: f.set_protection(16)))
    f.set_protection(0)
    f.page_program(0x100, b'\x00')
    check_true('program after unprotect', m.mem[0x100] == 0x00)


def test_raw_full():
    m = FlashModel()
    f = MX25L12845EFull(m)
    data = bytes(range(256)) + bytes(range(44))
    f.page_program(0x1000, data[:256])
    check_true('page_program 256', f.read_bytes(0x1000, 256) == data[:256])
    check_true('page-crossing program refused', raises(OutOfRange, lambda: f.page_program(0x10C0, data[:100])))
    check_true('page_program > 256 refused', raises(OutOfRange, lambda: f.page_program(0, data[:257])))
    check_true('READ and FAST_READ agree', f.fast_read(0x1000, 256) == f.read_bytes(0x1000, 256))
    f.erase_block_32k(0x8000)
    f.erase_block_64k(0x10000)
    check_true('32 / 64 KiB block erase', m.be32 == 1 and m.be64 == 1)
    f.chip_erase()
    check_true('chip erase', m.ce == 1 and m.mem[0x1000] == 0xFF)
    check_true('RES / REMS ids', f.electronic_id() == 0x17 and f.device_ids() == (0xC2, 0x17))
    f.deep_power_down()
    check_true('deep power-down', m.dp)
    f.release_deep_power_down()
    check_true('release deep power-down', not m.dp)
    check_true('read_sfdp', f.read_sfdp(0, 8)[1] == 1)
    check_true('idle after operations', not f.is_busy())
    f.write_status(0x04)
    check_true('write_status', m.sr == 0x04)
    f.write_status(0)


def test_otp():
    m = FlashModel()
    f = MX25L12845EFull(m)
    esn = f.serial_number()
    check_true('factory serial number', esn[0] == 0xA0 and esn[15] == 0xAF)
    check_true('OTP mode exited', not m.in_otp)
    f.program_otp(16, b'\x01\x02\x03\x04')
    check_true('program_otp + read_otp', f.read_otp(16, 4) == b'\x01\x02\x03\x04')
    check_true('ESN area refused', raises(OutOfRange, lambda: f.program_otp(8, b'\x01')))
    check_true('past the end of the OTP refused', raises(OutOfRange, lambda: f.program_otp(510, b'\x01\x02\x03\x04')))
    f.lock_otp()
    check_true('lock_otp (WRSCUR)', m.otp_locked)


def test_blockdevice():
    m = FlashModel()
    f = MX25L12845EFull(m)
    check_true('geometry', f.block_size() == 512 and f.block_count() == 32768 and f.erase_size() == 4096 and f.needs_erase())
    check_true('info() name', f.info()['name'] == 'MX25L12845E')
    w = bytes((k * 5 + 1) & 0xFF for k in range(1024))
    f.write(8, w)
    check_true('write + read blocks (auto_erase off)', f.read(8, 2) == w)
    f.write(8, w)
    check_true('identical rewrite is fine', f.read(8, 2) == w)
    check_true('0->1 without auto_erase -> NotErased', raises(NotErased, lambda: f.write(8, b'\xff' * 512)))
    check_true('NotErased did not erase anything', m.se == 0)
    f.erase(8, 8)
    check_true('erase aligned sector', m.mem[8 * 512] == 0xFF)
    check_true('unaligned erase -> OutOfRange', raises(OutOfRange, lambda: f.erase(9, 8)))
    check_true('erase past the end', raises(OutOfRange, lambda: f.erase(32768 - 8, 16)))
    check_true('read past the end', raises(OutOfRange, lambda: f.read(32768, 1)))
    f.sync()
    se0, be64_0, be32_0 = m.se, m.be64, m.be32
    f.erase(0, 128 + 64 + 8)
    check_true('erase picks 64K, then 32K, then 4K', m.be64 - be64_0 == 1 and m.be32 - be32_0 == 1 and m.se - se0 == 1)
    m2 = FlashModel()
    g = MX25L12845EFull(m2, auto_erase=True)
    g.write(0, w)
    g.write(0, b'\xff' * 512)
    check_true('auto_erase handles 0->1', m2.se == 1 and g.read(0, 1) == b'\xff' * 512)
    check_true('neighbouring block kept', g.read(1, 1) == w[512:])
    ram = RamBlockDevice(512, 64, 8)
    for name, dev, blk in (('SD-like flash', g, 64), ('RAM', ram, 0)):
        dev.erase(64 if blk else 0, 8)
        x = b'\x5a' * 512
        dev.write(blk, x)
        check_true('contract ' + name + ': erase+write+read', dev.read(blk, 1) == x)
        check_true('contract ' + name + ': out of range', raises(OutOfRange, lambda: dev.read(dev.block_count(), 1)))


def test_timeout():
    m = FlashModel()
    f = MX25L12845EFull(m)
    m.stuck_busy = True
    check_true('wait_ready times out', raises(Timeout, lambda: f.wait_ready(5)))
    check_true('is_busy true while stuck', f.is_busy())
    m.stuck_busy = False


test_init()
test_minimal_write()
test_protection()
test_raw_full()
test_otp()
test_blockdevice()
test_timeout()

print('\n{} passed, {} failed'.format(passed, failed))
if failed:
    sys.exit(1)
