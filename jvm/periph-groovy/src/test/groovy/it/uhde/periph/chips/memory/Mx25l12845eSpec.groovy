package it.uhde.periph.chips.memory

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.storage.BlockDevice
import it.uhde.periph.storage.BlockDeviceException
import it.uhde.periph.storage.BlockDeviceException.Kind
import it.uhde.periph.storage.RamBlockDevice
import spock.lang.Specification

class Mx25l12845eSpec extends Specification {

    static final int SIZE = Mx25l12845eMinimal.SIZE

    // Behavioural MX25L12845E: 16 MiB array programmable 1 -> 0 only, page wrap on PP, 4/32/64 KiB and
    // chip erase, WEL/WIP handling (WIP reads high for a few RDSR polls after every program/erase),
    // BP block protect with P_FAIL/E_FAIL, secured OTP, deep power-down, RES/REMS/RDID/SFDP.
    static class FlashModel implements Connection {
        byte[] mem = new byte[SIZE]
        byte[] otp = new byte[512]
        boolean inOtp, wel, dp, otpLocked, stuckBusy
        int sr, scur = 0x01, busyPolls, busyAfterOp = 2
        int pp, se, be32, be64, ce
        byte[] id = [0xC2, 0x20, 0x18] as byte[]

        FlashModel() {
            Arrays.fill(mem, (byte) 0xFF)
            Arrays.fill(otp, (byte) 0xFF)
            for (int i = 0; i < 16; i++) otp[i] = (byte) (0xA0 + i)
        }

        private int protectedFrom() {
            int bp = (sr >> 2) & 0xF
            if (bp == 0) return SIZE
            if (bp >= 8) return 0
            return SIZE - (65536 << bp)
        }

        private boolean isProtected(int a, int n) { a + n > protectedFrom() }

        private void startBusy() { busyPolls = busyAfterOp; wel = false }

        void enable() {}
        void disable() {}
        boolean isEnabled() { true }
        InputPin intPin() { null }
        OutputPin enPin() { null }
        void close() {}
        byte[] read(int n) { new byte[n] }
        void write(byte[] d) { frame(d, 0) }
        byte[] writeRead(byte[] d, int n) { frame(d, n) }

        private byte[] frame(byte[] d, int n) {
            int op = d[0] & 0xFF
            byte[] out = new byte[n]
            if (dp && op != 0xAB) return out
            boolean busy = stuckBusy || busyPolls > 0
            if (busy && op != 0x05 && op != 0x2B && op != 0x9F && op != 0x30) return out
            int a = d.length >= 4 ? ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF) : 0
            switch (op) {
                case 0x06: wel = true; break
                case 0x04: wel = false; break
                case 0x9F:
                    for (int i = 0; i < Math.min(n, 3); i++) out[i] = id[i]
                    break
                case 0x05:
                    out[0] = (byte) ((sr & 0xFC) | (wel ? 2 : 0) | (busy ? 1 : 0))
                    if (busyPolls > 0) busyPolls--
                    break
                case 0x01:
                    if (wel && d.length >= 2) { sr = d[1] & 0xFC; startBusy() }
                    break
                case 0x03: case 0x0B: case 0x5A:
                    for (int i = 0; i < n; i++) {
                        if (op == 0x5A) out[i] = (byte) (a + i)
                        else if (inOtp) out[i] = otp[(a + i) & 0x1FF]
                        else out[i] = mem[(a + i) & 0xFFFFFF]
                    }
                    break
                case 0x02:
                    if (wel) {
                        pp++
                        if (inOtp) {
                            if (otpLocked) scur |= 0x20
                            else for (int i = 4; i < d.length; i++) otp[(a + i - 4) & 0x1FF] &= d[i]
                        } else if (isProtected(a & ~255, 256)) {
                            scur |= 0x20
                        } else {
                            int page = a & ~255
                            for (int i = 4; i < d.length; i++) mem[page + ((a + i - 4) & 255)] &= d[i]
                        }
                        startBusy()
                    }
                    break
                case 0x20: case 0x52: case 0xD8:
                    if (wel) {
                        int size = op == 0x20 ? 4096 : op == 0x52 ? 32768 : 65536
                        int base = a & ~(size - 1)
                        if (op == 0x20) se++ else if (op == 0x52) be32++ else be64++
                        if (isProtected(base, size)) scur |= 0x40
                        else Arrays.fill(mem, base, base + size, (byte) 0xFF)
                        startBusy()
                    }
                    break
                case 0xC7: case 0x60:
                    if (wel) {
                        ce++
                        if (((sr >> 2) & 0xF) != 0) scur |= 0x40
                        else Arrays.fill(mem, (byte) 0xFF)
                        startBusy()
                    }
                    break
                case 0xB9: dp = true; break
                case 0xAB:
                    dp = false
                    if (n > 0) out[0] = (byte) 0x17
                    break
                case 0x90:
                    if (n >= 2) { out[0] = (byte) 0xC2; out[1] = (byte) 0x17 }
                    break
                case 0xB1: inOtp = true; break
                case 0xC1: inOtp = false; break
                case 0x2B: out[0] = (byte) scur; break
                case 0x2F:
                    if (wel) { scur |= 0x02; otpLocked = true; startBusy() }
                    break
                case 0x30: scur &= ~0x60; break
                default: break
            }
            return out
        }
    }

    private static Kind kind(Closure c) {
        try {
            c.call()
        } catch (BlockDeviceException e) {
            return e.kind()
        }
        return null
    }

    def 'init checks the ID, releases deep power-down and waits for idle'() {
        when:
        def m = new FlashModel()
        def f = new Mx25l12845eMinimal(m)
        def wrong = new FlashModel()
        wrong.id = [0xC2, 0x20, 0x17] as byte[]
        def stuck = new FlashModel()
        stuck.stuckBusy = true
        def asleep = new FlashModel()
        asleep.dp = true
        new Mx25l12845eMinimal(asleep)

        then:
        f.jedecId() == [0xC2, 0x20, 0x18] as byte[]
        f.size() == 16 * 1024 * 1024
        kind { new Mx25l12845eMinimal(wrong) } == Kind.NOT_READY
        kind { new Mx25l12845eMinimal(stuck, 20L) } == Kind.TIMEOUT
        !asleep.dp
    }

    def 'minimal write is erase-aware'() {
        given:
        def m = new FlashModel()
        def f = new Mx25l12845eMinimal(m)
        byte[] pattern = new byte[600]
        for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (i * 7 + 3)

        when:
        f.writeBytes(0x1F0, pattern)

        then:
        f.readBytes(0x1F0, 600) == pattern
        m.se == 0
        m.mem[0x1EF] == (byte) 0xFF && m.mem[0x1F0 + 600] == (byte) 0xFF

        when:
        int ppBefore = m.pp
        f.writeBytes(0x1F0, pattern)

        then:
        m.pp == ppBefore && m.se == 0

        when:
        byte[] more = new byte[600]
        for (int i = 0; i < more.length; i++) more[i] = (byte) (pattern[i] & 0xF0)
        f.writeBytes(0x1F0, more)

        then:
        f.readBytes(0x1F0, 600) == more && m.se == 0

        when:
        byte[] ff = new byte[10]
        Arrays.fill(ff, (byte) 0xFF)
        f.writeBytes(0x1F0, ff)

        then:
        m.se == 1
        f.readBytes(0x1F0, 10) == ff
        f.readBytes(0x200, 20) == Arrays.copyOfRange(more, 16, 36)

        when:
        byte[] big = new byte[9000]
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i ^ (i >> 8))
        f.writeBytes(0x2FF0, big)

        then:
        f.readBytes(0x2FF0, 9000) == big
        kind { f.writeBytes(SIZE - 2, Arrays.copyOf(big, 5)) } == Kind.OUT_OF_RANGE

        when:
        f.eraseSector(0x2000)

        then:
        m.mem[0x2000] == (byte) 0xFF
    }

    def 'block protection'() {
        given:
        def m = new FlashModel()
        def f = new Mx25l12845eFull(m)

        expect:
        f.protection() == 0

        when:
        f.setProtection(8)

        then:
        f.protection() == 8
        kind { f.pageProgram(0x100, [0] as byte[]) } == Kind.WRITE_PROTECTED
        m.mem[0x100] == (byte) 0xFF
        (f.readSecurity() & 0x60) == 0
        kind { f.eraseSector(0) } == Kind.WRITE_PROTECTED
        f.info().readOnly
        kind { f.setProtection(16) } == Kind.OUT_OF_RANGE

        when:
        f.setProtection(0)
        f.pageProgram(0x100, [0] as byte[])

        then:
        m.mem[0x100] == (byte) 0
    }

    def 'raw operations'() {
        given:
        def m = new FlashModel()
        def f = new Mx25l12845eFull(m)
        byte[] data = new byte[300]
        for (int i = 0; i < data.length; i++) data[i] = (byte) i

        when:
        f.pageProgram(0x1000, Arrays.copyOf(data, 256))

        then:
        f.readBytes(0x1000, 256) == Arrays.copyOf(data, 256)
        kind { f.pageProgram(0x10C0, Arrays.copyOf(data, 100)) } == Kind.OUT_OF_RANGE
        kind { f.pageProgram(0, Arrays.copyOf(data, 257)) } == Kind.OUT_OF_RANGE
        f.fastRead(0x1000, 256) == f.readBytes(0x1000, 256)

        when:
        f.eraseBlock32k(0x8000)
        f.eraseBlock64k(0x10000)

        then:
        m.be32 == 1 && m.be64 == 1

        when:
        f.chipErase()

        then:
        m.ce == 1 && m.mem[0x1000] == (byte) 0xFF
        f.electronicId() == 0x17
        f.deviceIds() == [0xC2, 0x17] as int[]

        when:
        f.deepPowerDown()

        then:
        m.dp

        when:
        f.releaseDeepPowerDown()

        then:
        !m.dp
        f.readSfdp(0, 8)[1] == (byte) 1
        !f.isBusy()

        when:
        f.writeStatus(0x04)

        then:
        m.sr == 0x04

        when:
        f.writeStatus(0)

        then:
        m.sr == 0
    }

    def 'secured OTP'() {
        given:
        def m = new FlashModel()
        def f = new Mx25l12845eFull(m)

        when:
        byte[] esn = f.serialNumber()

        then:
        esn[0] == (byte) 0xA0 && esn[15] == (byte) 0xAF
        !m.inOtp

        when:
        f.programOtp(16, [1, 2, 3, 4] as byte[])

        then:
        f.readOtp(16, 4) == [1, 2, 3, 4] as byte[]
        kind { f.programOtp(8, [1] as byte[]) } == Kind.OUT_OF_RANGE
        kind { f.programOtp(510, [1, 2, 3, 4] as byte[]) } == Kind.OUT_OF_RANGE

        when:
        f.lockOtp()

        then:
        m.otpLocked
    }

    def 'BlockDevice contract'() {
        given:
        def m = new FlashModel()
        def f = new Mx25l12845eFull(m)
        byte[] w = new byte[1024]
        for (int k = 0; k < w.length; k++) w[k] = (byte) (k * 5 + 1)
        byte[] ff = new byte[512]
        Arrays.fill(ff, (byte) 0xFF)

        expect:
        f.blockSize() == 512 && f.blockCount() == 32768L && f.eraseSize() == 4096 && f.needsErase()
        f.info().name == 'MX25L12845E'

        when:
        f.write(8, w)
        f.write(8, w)

        then:
        f.read(8, 2) == w
        kind { f.write(8, ff) } == Kind.NOT_ERASED
        m.se == 0

        when:
        f.erase(8, 8)

        then:
        m.mem[8 * 512] == (byte) 0xFF
        kind { f.erase(9, 8) } == Kind.OUT_OF_RANGE
        kind { f.erase(32768 - 8, 16) } == Kind.OUT_OF_RANGE
        kind { f.read(32768, 1) } == Kind.OUT_OF_RANGE

        when:
        f.sync()
        int se0 = m.se, be640 = m.be64, be320 = m.be32
        f.erase(0, 128 + 64 + 8)

        then:
        m.be64 - be640 == 1 && m.be32 - be320 == 1 && m.se - se0 == 1

        when:
        def m2 = new FlashModel()
        def g = new Mx25l12845eFull(m2, true)
        g.write(0, w)
        g.write(0, ff)

        then:
        m2.se == 1
        g.read(0, 1) == ff
        g.read(1, 1) == Arrays.copyOfRange(w, 512, 1024)

        when:
        def ram = new RamBlockDevice(512, 64, 8, false)
        byte[] x = new byte[512]
        Arrays.fill(x, (byte) 0x5A)
        Map<BlockDevice, Long> devs = [(g): 64L, (ram): 0L]
        devs.each { BlockDevice dev, Long blk ->
            dev.erase(blk, 8)
            dev.write(blk, x)
        }

        then:
        g.read(64, 1) == x
        ram.read(0, 1) == x
        kind { g.read(g.blockCount(), 1) } == Kind.OUT_OF_RANGE
        kind { ram.read(ram.blockCount(), 1) } == Kind.OUT_OF_RANGE
    }

    def 'wait times out while the chip is stuck busy'() {
        given:
        def m = new FlashModel()
        def f = new Mx25l12845eFull(m)
        m.stuckBusy = true

        expect:
        kind { f.waitUntilReady(5L) } == Kind.TIMEOUT
        f.isBusy()
    }
}
