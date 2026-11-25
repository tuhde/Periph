package it.uhde.periph.chips.memory

import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.SPIExtensions
import it.uhde.periph.storage.BlockDevice
import it.uhde.periph.storage.BlockDeviceException
import it.uhde.periph.storage.BlockDeviceException.Kind
import it.uhde.periph.storage.RamBlockDevice
import spock.lang.Specification

class SdSpiSpec extends Specification {

    // Behavioural model of an SD card in SPI mode behind SPIExtensions. Needs 80 idle clocks
    // before CMD0, checks the command CRC7, models R1/R3/R7, CSD/CID, single and multi-block
    // transfers with tokens, busy and CRC16.
    static class SdModel implements SPIExtensions {
        boolean v2 = true
        boolean sdhc = true
        int csizeV2 = 7             // (7+1) * 512 KiB = 4 MiB = 8192 blocks
        int acmd41Calls = 0
        int acmd41Needed = 3
        boolean gotIdleClocks, selected, inIdle, crcOn, corruptReadCrc
        int currentSpeed
        int csTogglesInCommand
        byte[] mem = new byte[8192 * 512]
        List<Integer> cmdLog = []
        int state = 0               // 0 CMD, 1 WTOKEN, 2 WDATA
        List<Integer> cmdBuf = []
        LinkedList<Integer> out = new LinkedList<Integer>()
        boolean appNext, streaming, multiWrite
        long streamBlock, wBlock
        List<Integer> wData = []

        private void pushBlock(byte[] data, int off, int len) {
            out.add(0xFE)
            for (int i = 0; i < len; i++) out.add(data[off + i] & 0xFF)
            int c = SdSpiMinimal.crc16(data, off, len)
            if (corruptReadCrc) c ^= 1
            out.add(c >> 8)
            out.add(c & 0xFF)
        }

        private long blockOf(int arg) { sdhc ? (arg & 0xFFFFFFFFL) : (arg & 0xFFFFFFFFL).intdiv(512L) }

        private void doCommand() {
            boolean wasApp = appNext
            appNext = false
            byte[] cb = new byte[6]
            for (int i = 0; i < 6; i++) cb[i] = (byte) cmdBuf[i]
            int cmd = cb[0] & 0x3F
            int arg = ((cb[1] & 0xFF) << 24) | ((cb[2] & 0xFF) << 16) | ((cb[3] & 0xFF) << 8) | (cb[4] & 0xFF)
            cmdLog.add(wasApp ? (0x80 | cmd) : cmd)
            if (((cb[5] & 0xFF) >> 1) != SdSpiMinimal.crc7(cb, 5)) { out.add(0xFF); out.add(0x09); return }
            out.add(0xFF)   // Ncr gap
            int idle = inIdle ? 0x01 : 0x00
            if (wasApp) {
                if (cmd == 41) {
                    acmd41Calls++
                    if (acmd41Calls >= acmd41Needed) { inIdle = false; out.add(0x00) } else { out.add(0x01) }
                } else { out.add(idle) }
                return
            }
            switch (cmd) {
                case 0:
                    if (!gotIdleClocks) { out.clear(); return }
                    inIdle = true; out.add(0x01); break
                case 8:
                    if (!v2) { out.add(0x05) } else { [0x01, 0, 0, 0x01, 0xAA].each { out.add(it) } }
                    break
                case 9:
                    byte[] csd = new byte[16]
                    if (sdhc) {
                        csd[0] = (byte) 0x40; csd[7] = (byte) ((csizeV2 >> 16) & 0x3F); csd[8] = (byte) (csizeV2 >> 8); csd[9] = (byte) csizeV2
                    } else {
                        csd[5] = (byte) 9; csd[6] = (byte) 0x03; csd[7] = (byte) 0xFF; csd[8] = (byte) 0xC0; csd[9] = (byte) 0x03; csd[10] = (byte) 0x80
                    }
                    out.add(0x00); out.add(0xFF); pushBlock(csd, 0, 16); break
                case 10:
                    byte[] cid = [0x03, 'S', 'D', 'T', 'E', 'S', 'T', '1', 0x10, 0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x23, 0x01].collect { (byte) (it instanceof String ? ((String) it).charAt(0) : (int) it) } as byte[]
                    out.add(0x00); out.add(0xFF); pushBlock(cid, 0, 16); break
                case 12:
                    streaming = false; out.clear(); [0xFF, 0x00, 0x00, 0x00, 0xFF].each { out.add(it) }; break
                case 13: out.add(0x00); out.add(0x00); break
                case 16: out.add(idle); break
                case 17: out.add(0x00); out.add(0xFF); pushBlock(mem, (int) blockOf(arg) * 512, 512); break
                case 18: out.add(0x00); streaming = true; streamBlock = blockOf(arg); break
                case 24:
                case 25:
                    out.add(0x00); state = 1; wBlock = blockOf(arg); multiWrite = (cmd == 25); break
                case 32:
                case 33: out.add(0x00); break
                case 38: out.add(0x00); out.add(0x00); out.add(0x00); break
                case 55: appNext = true; out.add(idle); break
                case 58: [idle, sdhc ? 0xC0 : 0x80, 0xFF, 0x80, 0x00].each { out.add(it) }; break
                case 59: crcOn = (arg & 1) != 0; out.add(idle); break
                default: out.add(0x04)   // illegal command
            }
        }

        private int feed(int b) {
            if (state == 1) {
                if (b == 0xFE || b == 0xFC) { state = 2; wData.clear() }
                else if (b == 0xFD) { state = 0; [0xFF, 0x00, 0x00, 0xFF].each { out.add(it) }; return 0xFF }
            } else if (state == 2) {
                wData.add(b)
                if (wData.size() == 514) {
                    byte[] d = new byte[512]
                    for (int i = 0; i < 512; i++) d[i] = (byte) wData[i]
                    int got = (wData[512] << 8) | wData[513]
                    boolean ok = !crcOn || got == SdSpiMinimal.crc16(d, 0, 512)
                    if (ok) { System.arraycopy(d, 0, mem, (int) wBlock * 512, 512); wBlock++ }
                    out.add(ok ? 0xE5 : 0xEB); out.add(0x00); out.add(0x00); out.add(0xFF)
                    state = multiWrite ? 1 : 0
                    return 0xFF   // the data response follows on the NEXT byte clock
                }
            } else if (!cmdBuf.isEmpty() || (b & 0xC0) == 0x40) {
                cmdBuf.add(b)
                if (cmdBuf.size() == 6) { doCommand(); cmdBuf.clear() }
                return 0xFF
            }
            if (!out.isEmpty()) return out.removeFirst()
            if (streaming && state == 0) {
                pushBlock(mem, (int) streamBlock * 512, 512)
                streamBlock++
                return out.removeFirst()
            }
            return 0xFF
        }

        void idleClocks(int n) { if (!selected && n >= 80) gotIdleClocks = true }
        void setSpeed(int hz) { currentSpeed = hz }
        void select() { selected = true }
        void deselect() { selected = false; if (state == 0 && !cmdBuf.isEmpty()) csTogglesInCommand++ }
        void readInto(byte[] buf) { for (int i = 0; i < buf.length; i++) buf[i] = (byte) feed(0xFF) }
        void write(byte[] data) { for (byte x : data) feed(x & 0xFF) }
        byte[] read(int n) { byte[] b = new byte[n]; readInto(b); b }
        byte[] writeRead(byte[] data, int n) { write(data); read(n) }
        void enable() {}
        void disable() {}
        boolean isEnabled() { true }
        InputPin intPin() { null }
        OutputPin enPin() { null }
        void close() {}
    }

    static class DeadCard extends SdModel {
        void readInto(byte[] buf) { Arrays.fill(buf, (byte) 0xFF) }
    }

    def "crc helpers match the standard check values"() {
        expect:
        SdSpiMinimal.crc16('123456789'.bytes, 0, 9) == 0x31C3
        ((SdSpiMinimal.crc7([0x40, 0, 0, 0, 0] as byte[], 5) << 1) | 1) == 0x95
    }

    def "init of a v2 SDHC card"() {
        given:
        def m = new SdModel()
        def sd = new SdSpiMinimal(m)

        expect:
        sd.cardType() == SdSpiMinimal.CardType.SDHC
        sd.blockCount() == 8192L
        m.gotIdleClocks
        m.currentSpeed == 12000000
        m.csTogglesInCommand == 0
    }

    def "init of a v1 byte-addressed card sends CMD16"() {
        given:
        def m = new SdModel(v2: false, sdhc: false)
        def sd = new SdSpiMinimal(m)

        expect:
        sd.cardType() == SdSpiMinimal.CardType.SD_V1
        sd.blockCount() == 2097152L
        m.cmdLog.contains(16)
    }

    def "no card is NOT_READY"() {
        when:
        new SdSpiMinimal(new DeadCard())

        then:
        def e = thrown(BlockDeviceException)
        e.kind() == Kind.NOT_READY
    }

    def "minimal block round trip and range check"() {
        given:
        def sd = new SdSpiMinimal(new SdModel())
        byte[] w = new byte[512]
        for (int i = 0; i < 512; i++) w[i] = (byte) (i * 7 + 3)

        when:
        sd.writeBlock(5, w)

        then:
        Arrays.equals(w, sd.readBlock(5))

        when:
        sd.readBlock(8192)

        then:
        thrown(BlockDeviceException)
    }

    def "full multi-block round trip and info"() {
        given:
        def sd = new SdSpiFull(new SdModel())
        byte[] w = new byte[4 * 512]
        for (int k = 0; k < w.length; k++) w[k] = (byte) (k ^ (k >> 8))

        expect:
        sd.blockSize() == 512
        !sd.needsErase()
        sd.info().name == 'TEST1'

        when:
        sd.write(100, w)
        sd.sync()

        then:
        Arrays.equals(w, sd.read(100, 4))
        (sd.ocr() & 0x40000000) != 0
        sd.isPresent()

        when:
        sd.erase(100, 4)
        sd.write(8190, w)

        then:
        def e = thrown(BlockDeviceException)
        e.kind() == Kind.OUT_OF_RANGE
    }

    def "a corrupt read CRC16 is reported"() {
        given:
        def m = new SdModel()
        def sd = new SdSpiFull(m)
        m.corruptReadCrc = true

        when:
        sd.readBlock(1)

        then:
        def e = thrown(BlockDeviceException)
        e.kind() == Kind.CRC

        when:
        sd.setCrc(false)

        then:
        sd.readBlock(1).length == 512
    }

    def "SD and RAM satisfy the same BlockDevice contract"() {
        expect:
        [new SdSpiFull(new SdModel()), new RamBlockDevice(512, 64, 1, false)].every { BlockDevice dev ->
            byte[] w = new byte[512]
            Arrays.fill(w, (byte) 0xA5)
            dev.write(3, w)
            boolean ok = Arrays.equals(w, dev.read(3, 1))
            boolean range = false
            try { dev.read(dev.blockCount(), 1) } catch (BlockDeviceException e) { range = e.kind() == Kind.OUT_OF_RANGE }
            ok && range
        }
    }
}
