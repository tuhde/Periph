package it.uhde.periph.chips.comms

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Rda5807mSpec extends Specification {

    // Register bit constants and frequency<->channel math, mirrored from
    // RDA5807MMinimal/RDA5807MFull (protected there, so re-declared here to
    // build expected values). BAND_*/SPACE_* are public on the driver
    // itself so those are referenced directly.
    private static final int[] BAND_BASE_KHZ = [87000, 76000, 76000, 65000]
    private static final int[] SPACE_KHZ = [100, 200, 50, 25]

    private static final int DHIZ = 0x8000
    private static final int DMUTE = 0x4000
    private static final int MONO = 0x2000
    private static final int BASS = 0x1000
    private static final int SEEKUP = 0x0200
    private static final int SEEK = 0x0100
    private static final int SKMODE = 0x0080
    private static final int RDS_EN = 0x0008
    private static final int NEW_METHOD = 0x0004
    private static final int SOFT_RESET = 0x0002
    private static final int ENABLE = 0x0001
    private static final int TUNE = 0x0010
    private static final int DE = 0x0800
    private static final int SOFTMUTE_EN = 0x0200
    private static final int AFCD = 0x0100
    private static final int INT_MODE = 0x8000
    private static final int BAND_65M_50M = 0x0200
    private static final int RDSR = 0x8000
    private static final int STC = 0x4000
    private static final int SF = 0x2000
    private static final int ST = 0x0400
    private static final int FM_TRUE = 0x0100
    private static final int FM_READY = 0x0080

    private static int freqToChan(int band, int space, boolean east50, double freqMhz) {
        int base = (band == 3 && east50) ? 50000 : BAND_BASE_KHZ[band]
        int freqKhz = (int) Math.round(freqMhz * 1000.0d)
        int chan = (int) Math.round((freqKhz - base) / (double) SPACE_KHZ[space])
        Math.max(0, Math.min(1023, chan))
    }

    private static double chanToFreq(int band, int space, boolean east50, int chan) {
        int base = (band == 3 && east50) ? 50000 : BAND_BASE_KHZ[band]
        (base + chan * (double) SPACE_KHZ[space]) / 1000.0d
    }

    private static byte[] regsBytes(int[] regs) {
        byte[] buf = new byte[12]
        for (int i = 0; i < 6; i++) {
            buf[i * 2] = (byte) (regs[i] >> 8)
            buf[i * 2 + 1] = (byte) (regs[i] & 0xFF)
        }
        buf
    }

    private static byte[] statusBytes(int... words) {
        byte[] buf = new byte[words.length * 2]
        for (int i = 0; i < words.length; i++) {
            buf[i * 2] = (byte) (words[i] >> 8)
            buf[i * 2 + 1] = (byte) (words[i] & 0xFF)
        }
        buf
    }

    /** Construct a fresh RDA5807MFull with a queued STC-set status so the
     * blocking waitStc() inside the constructor resolves on its first poll.
     * Returns [connection, sensor, regs, band, space, east50] where regs is
     * the expected post-init shadow register array (TUNE already cleared,
     * mirroring what the driver does once it observes STC). */
    private static List newSensor(double frequencyMhz = 100.0d, int volume = 8) {
        def connection = new MockConnection()
        connection.queueRead(statusBytes(STC))
        int band = RDA5807MMinimal.BAND_WORLD
        int space = RDA5807MMinimal.SPACE_100K
        boolean east50 = false
        int chan0 = freqToChan(band, space, east50, frequencyMhz)
        int[] regs = [
            DHIZ | DMUTE | SKMODE | NEW_METHOD | ENABLE,
            (chan0 << 6) | TUNE | (band << 2) | space,
            SOFTMUTE_EN | DE,
            INT_MODE | (8 << 8) | (volume & 0x0F),
            0x0000,
            (16 << 10) | BAND_65M_50M | 0x0002,
        ]
        def sensor = new RDA5807MFull(connection, frequencyMhz, volume)
        regs[1] &= ~TUNE
        [connection, sensor, regs, band, space, east50]
    }

    private static byte[] lastWrite(MockConnection connection) {
        def writes = connection.writes()
        writes[writes.size() - 1]
    }

    def "init writes regs"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()
        int[] expected = regs.clone()
        expected[1] |= TUNE

        expect:
        connection.writes()[0] == regsBytes(expected)
    }

    def "frequency"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()
        connection.queueRead(statusBytes(250))

        expect:
        sensor.frequency() == chanToFreq(band, space, east50, 250)
    }

    def "set frequency writes"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()
        connection.queueRead(statusBytes(STC))

        when:
        sensor.setFrequency(103.5d)
        int chan1 = freqToChan(band, space, east50, 103.5d)
        regs[1] = (chan1 << 6) | TUNE | (band << 2) | space

        then:
        lastWrite(connection) == regsBytes(regs)
    }

    def "set volume"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()

        when:
        sensor.setVolume(5)
        regs[3] = (regs[3] & ~0x000F) | (5 & 0x0F)

        then:
        lastWrite(connection) == regsBytes(regs)
    }

    def "mute"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()

        when:
        sensor.mute(true)
        regs[0] &= ~DMUTE

        then:
        lastWrite(connection) == regsBytes(regs)

        when:
        sensor.mute(false)
        regs[0] |= DMUTE

        then:
        lastWrite(connection) == regsBytes(regs)
    }

    def "seek up found"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()
        connection.queueRead(statusBytes(STC | 300))

        when:
        Double result = sensor.seek(true)
        regs[0] |= SEEKUP
        regs[0] |= SEEK
        byte[] firstWrite = regsBytes(regs)
        regs[0] &= ~SEEK
        byte[] secondWrite = regsBytes(regs)
        def writes = connection.writes()

        then:
        writes[writes.size() - 2] == firstWrite
        writes[writes.size() - 1] == secondWrite
        result == chanToFreq(band, space, east50, 300)
    }

    def "seek fails"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()
        connection.queueRead(statusBytes(STC | SF))

        expect:
        sensor.seek(false) == null
    }

    def "configure retunes"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()
        connection.queueRead(statusBytes(500)) // configure() reads current frequency() first
        double currentFreq = chanToFreq(band, space, east50, 500)
        connection.queueRead(statusBytes(STC)) // for the resulting retune's waitStc

        when:
        sensor.configure(RDA5807MMinimal.BAND_US_EUROPE, RDA5807MMinimal.SPACE_50K, false,
                10, false, 3, true, null)

        int newBand = RDA5807MMinimal.BAND_US_EUROPE
        int newSpace = RDA5807MMinimal.SPACE_50K
        regs[2] &= ~DE
        regs[2] |= AFCD
        regs[3] = (regs[3] & ~0x0F00) | ((10 & 0x0F) << 8)
        regs[0] &= ~SKMODE
        regs[0] = (regs[0] & ~0x0070) | ((3 & 0x07) << 4)
        int chan2 = freqToChan(newBand, newSpace, east50, currentFreq)
        regs[1] = (chan2 << 6) | TUNE | (newBand << 2) | newSpace

        then:
        lastWrite(connection) == regsBytes(regs)
    }

    def "configure no retune"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()
        connection.queueRead(statusBytes(0)) // configure() still reads frequency() first

        when:
        sensor.configure(null, null, null, 4, null, null, null, null)
        regs[3] = (regs[3] & ~0x0F00) | ((4 & 0x0F) << 8)

        then:
        lastWrite(connection) == regsBytes(regs)
    }

    def "bass, mono, softmute, rds"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()

        when:
        sensor.setBassBoost(true)
        regs[0] |= BASS

        then:
        lastWrite(connection) == regsBytes(regs)

        when:
        sensor.setMono(true)
        regs[0] |= MONO

        then:
        lastWrite(connection) == regsBytes(regs)

        when:
        sensor.setSoftmute(false)
        regs[2] &= ~SOFTMUTE_EN

        then:
        lastWrite(connection) == regsBytes(regs)

        when:
        sensor.enableRds(true)
        regs[0] |= RDS_EN

        then:
        lastWrite(connection) == regsBytes(regs)
    }

    def "rds ready and group"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()

        when:
        connection.queueRead(statusBytes(RDSR))

        then:
        sensor.rdsReady()

        when:
        connection.queueRead(statusBytes(0))

        then:
        !sensor.rdsReady()

        when:
        connection.queueRead(statusBytes(RDSR, 0, 0x1122, 0x3344, 0x5566, 0x7788))
        int[] group = sensor.readRdsGroup()

        then:
        group == [0x1122, 0x3344, 0x5566, 0x7788] as int[]

        when:
        connection.queueRead(statusBytes(0, 0, 0, 0, 0, 0))

        then:
        sensor.readRdsGroup() == null
    }

    def "status flags"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()

        when:
        connection.queueRead(statusBytes(ST))

        then:
        sensor.isStereo()

        when:
        connection.queueRead(statusBytes(0, FM_TRUE))

        then:
        sensor.isStation()

        when:
        connection.queueRead(statusBytes(0, FM_READY))

        then:
        sensor.isReady()

        when:
        connection.queueRead(statusBytes(0, (100 << 9) & 0xFFFF))

        then:
        sensor.signalStrength() == 100
    }

    def "standby"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()

        when:
        sensor.standby(true)
        regs[0] &= ~ENABLE

        then:
        lastWrite(connection) == regsBytes(regs)

        when:
        connection.queueRead(statusBytes(STC)) // standby(false)'s internal setFrequency's waitStc
        sensor.standby(false)
        regs[0] |= ENABLE
        byte[] enableWrite = regsBytes(regs)
        int chan3 = freqToChan(band, space, east50, 100.0d) // newSensor()'s default frequency, unchanged so far
        regs[1] = (chan3 << 6) | TUNE | (band << 2) | space
        byte[] retuneWrite = regsBytes(regs)
        def writes = connection.writes()

        then:
        writes[writes.size() - 2] == enableWrite
        writes[writes.size() - 1] == retuneWrite
    }

    def "soft reset"() {
        given:
        def (connection, sensor, regs, band, space, east50) = newSensor()
        connection.queueRead(statusBytes(STC)) // softReset()'s internal setFrequency's waitStc

        when:
        sensor.softReset()
        regs[0] |= SOFT_RESET
        byte[] setWrite = regsBytes(regs)
        regs[0] &= ~SOFT_RESET
        byte[] clearWrite = regsBytes(regs)
        int chan4 = freqToChan(band, space, east50, 100.0d) // newSensor()'s default frequency, unchanged so far
        regs[1] = (chan4 << 6) | TUNE | (band << 2) | space
        byte[] retuneWrite = regsBytes(regs)
        def writes = connection.writes()

        then:
        writes[writes.size() - 3] == setWrite
        writes[writes.size() - 2] == clearWrite
        writes[writes.size() - 1] == retuneWrite
    }
}
