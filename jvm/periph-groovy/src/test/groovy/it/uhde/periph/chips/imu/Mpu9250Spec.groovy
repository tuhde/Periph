package it.uhde.periph.chips.imu

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Mpu9250Spec extends Specification {

    // AK8963 register constants (private in MPU9250Full, so mirrored here).
    private static final int AK8963_REG_CNTL1 = 0x0A
    private static final int AK8963_REG_ASAX  = 0x10
    private static final int AK8963_REG_ASAY  = 0x11
    private static final int AK8963_REG_ASAZ  = 0x12
    private static final int AK8963_REG_HXL   = 0x03

    // Returns a List (not int[]) so callers can concatenate with +, e.g.
    // s16(a) + s16(b) - Groovy's int[] has no + operator, unlike List.
    private static List<Integer> s16(int value) {
        [(value >> 8) & 0xFF, value & 0xFF]
    }

    private static List<Integer> s16le(int value) {
        [value & 0xFF, (value >> 8) & 0xFF]
    }

    private static byte[] lastWrite(MockConnection connection) {
        def writes = connection.writes()
        writes[writes.size() - 1]
    }

    private static MPU9250Full newInitializedSensor(MockConnection connection, MockConnection magConnection) {
        connection.setRegister(MPU9250Minimal.REG_WHO_AM_I, MPU9250Minimal.WHO_AM_I_VALUE)
        new MPU9250Full(connection, magConnection)
    }

    def "init writes sequence"() {
        given:
        def connection = new MockConnection()

        when:
        newInitializedSensor(connection, new MockConnection())

        then:
        // MPU9250 additionally writes ACCEL_CONFIG2, unlike MPU6050.
        def writes = connection.writes()
        writes.size() == 8
        writes[0] == [MPU9250Minimal.REG_PWR_MGMT_1, 0x80] as byte[]
        writes[1] == [MPU9250Minimal.REG_PWR_MGMT_1, 0x01] as byte[]
        writes[2] == [MPU9250Minimal.REG_WHO_AM_I] as byte[]
        writes[3] == [MPU9250Minimal.REG_GYRO_CONFIG, 0x00] as byte[]
        writes[4] == [MPU9250Minimal.REG_ACCEL_CONFIG, 0x00] as byte[]
        writes[5] == [MPU9250Minimal.REG_ACCEL_CONFIG2, 0x03] as byte[]
        writes[6] == [MPU9250Minimal.REG_CONFIG, 0x03] as byte[]
        writes[7] == [MPU9250Minimal.REG_SMPLRT_DIV, 0x04] as byte[]
    }

    def "WHO_AM_I mismatch throws"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(MPU9250Minimal.REG_WHO_AM_I, 0x00)

        when:
        new MPU9250Minimal(connection)

        then:
        thrown(IOException)
    }

    def "full API"() {
        given:
        def connection = new MockConnection()
        def magConnection = new MockConnection()
        def sensor = newInitializedSensor(connection, magConnection)

        when: "accel(): raw (16384, -8192, 4096) at default ACCEL_FS_SEL=0 (16384 LSB/g)"
        connection.setRegister(MPU9250Minimal.REG_ACCEL_XOUT_H, *(s16(16384) + s16(-8192) + s16(4096)))
        def a = sensor.accel()

        then:
        Math.abs(a[0] - 9.80665d) < 1e-9
        Math.abs(a[1] - (-4.903325d)) < 1e-9
        Math.abs(a[2] - 2.4516625d) < 1e-9

        when: "gyro(): raw (131, -131, 262) at default GYRO_FS_SEL=0 -> (1, -1, 2) dps"
        connection.setRegister(MPU9250Minimal.REG_GYRO_XOUT_H, *(s16(131) + s16(-131) + s16(262)))
        def g = sensor.gyro()

        then:
        Math.abs(g[0] - Math.toRadians(1)) < 1e-9
        Math.abs(g[1] - Math.toRadians(-1)) < 1e-9
        Math.abs(g[2] - Math.toRadians(2)) < 1e-9

        when:
        sensor.configureGyro(2)

        then:
        lastWrite(connection) == [MPU9250Minimal.REG_GYRO_CONFIG, 2 << 3] as byte[]

        when: "sensitivity for FS_SEL=2 is 32.8 LSB/(deg/s); raw=328 -> 10 dps"
        connection.setRegister(MPU9250Minimal.REG_GYRO_XOUT_H, *(s16(328) + s16(0) + s16(0)))
        def g2 = sensor.gyro()

        then:
        Math.abs(g2[0] - Math.toRadians(10)) < 1e-6

        when:
        sensor.configureAccel(1)

        then:
        lastWrite(connection) == [MPU9250Minimal.REG_ACCEL_CONFIG, 1 << 3] as byte[]

        when: "sensitivity for AFS_SEL=1 is 8192 LSB/g; raw=8192 -> 1g"
        connection.setRegister(MPU9250Minimal.REG_ACCEL_XOUT_H, *(s16(8192) + s16(0) + s16(0)))
        def a2 = sensor.accel()

        then:
        Math.abs(a2[0] - 9.80665d) < 1e-6

        when:
        sensor.configureDlpf(5, 2)

        then:
        def dlpfWrites = connection.writes()
        dlpfWrites[dlpfWrites.size() - 2] == [MPU9250Minimal.REG_CONFIG, 5] as byte[]
        lastWrite(connection) == [MPU9250Minimal.REG_ACCEL_CONFIG2, 2] as byte[]

        when:
        sensor.configureSampleRate(9)

        then:
        lastWrite(connection) == [MPU9250Minimal.REG_SMPLRT_DIV, 9] as byte[]

        when: "temperature(): raw=340 -> 340/333.87 + 21.0"
        connection.setRegister(MPU9250Minimal.REG_TEMP_OUT_H, *s16(340))

        then:
        Math.abs(sensor.temperature() - (340.0d / 333.87d + 21.0d)) < 1e-9

        when:
        connection.setRegister(MPU9250Minimal.REG_ACCEL_XOUT_H, *(s16(100) + s16(-200) + s16(300)))
        def accelRaw = sensor.accelRaw()

        then:
        accelRaw == [100, -200, 300] as int[]

        when:
        connection.setRegister(MPU9250Minimal.REG_GYRO_XOUT_H, *(s16(-50) + s16(60) + s16(-70)))
        def gyroRaw = sensor.gyroRaw()

        then:
        gyroRaw == [-50, 60, -70] as int[]

        when:
        connection.setRegister(MPU9250Minimal.REG_INT_STATUS, 0x01)

        then:
        sensor.dataReady()

        when:
        connection.setRegister(MPU9250Minimal.REG_INT_STATUS, 0x00)

        then:
        !sensor.dataReady()

        when: "setSleep(): PWR_MGMT_1 is 0x01 in the register map after init"
        sensor.setSleep(true)

        then:
        lastWrite(connection) == [MPU9250Minimal.REG_PWR_MGMT_1, 0x41] as byte[]

        when:
        sensor.setSleep(false)

        then:
        lastWrite(connection) == [MPU9250Minimal.REG_PWR_MGMT_1, 0x01] as byte[]

        when:
        connection.setRegister(MPU9250Minimal.REG_FIFO_COUNTH, 0x03, 0x45)

        then:
        sensor.fifoCount() == (((0x03 & 0x1F) << 8) | 0x45)

        when:
        connection.setRegister(MPU9250Minimal.REG_FIFO_COUNTH, 0x00, 0x02)
        connection.setRegister(MPU9250Minimal.REG_FIFO_R_W, 0xAA, 0xBB)
        def fifoData = sensor.readFifo()

        then:
        fifoData == [0xAA, 0xBB] as byte[]

        when:
        connection.setRegister(MPU9250Minimal.REG_FIFO_COUNTH, 0x00, 0x00)
        def fifoEmpty = sensor.readFifo()

        then:
        fifoEmpty.length == 0

        when: "enableFifo(): FIFO_EN write, then a USER_CTRL read, then the USER_CTRL write"
        sensor.enableFifo(true, true, false)
        def writes2 = connection.writes()
        int n = writes2.size()

        then:
        writes2[n - 3] == [MPU9250Minimal.REG_FIFO_EN, (1 << 3) | (1 << 4)] as byte[]
        writes2[n - 2] == [MPU9250Minimal.REG_USER_CTRL] as byte[]
        writes2[n - 1] == [MPU9250Minimal.REG_USER_CTRL, 0x40] as byte[]

        when: "resetFifo(): USER_CTRL is 0x40 in the register map after enableFifo()"
        sensor.resetFifo()

        then:
        lastWrite(connection) == [MPU9250Minimal.REG_USER_CTRL, 0x44] as byte[]
    }

    def "magnetometer API"() {
        given:
        def connection = new MockConnection()
        def magConnection = new MockConnection()
        def sensor = newInitializedSensor(connection, magConnection)

        when: "enableMag(): INT_PIN_CFG write (primary connection), AK8963 CNTL1 power-down, fuse ROM access, ASAX/ASAY/ASAZ reads, power-down, mode write - all on magConnection"
        magConnection.setRegister(AK8963_REG_ASAX, 200)
        magConnection.setRegister(AK8963_REG_ASAY, 100)
        magConnection.setRegister(AK8963_REG_ASAZ, 50)
        sensor.enableMag(16, 6)

        then:
        lastWrite(connection) == [MPU9250Minimal.REG_INT_PIN_CFG, 0x22] as byte[]
        def magWrites = magConnection.writes()
        magWrites.size() == 7
        magWrites[0] == [AK8963_REG_CNTL1, 0x00] as byte[]
        magWrites[1] == [AK8963_REG_CNTL1, 0x0F] as byte[]
        magWrites[2] == [AK8963_REG_ASAX] as byte[]
        magWrites[3] == [AK8963_REG_ASAY] as byte[]
        magWrites[4] == [AK8963_REG_ASAZ] as byte[]
        magWrites[5] == [AK8963_REG_CNTL1, 0x00] as byte[]
        magWrites[6] == [AK8963_REG_CNTL1, 0x16] as byte[]  // 16-bit | mode=6

        when: "mag(): raw (1000, -500, 250) with scale factors derived from ASAX/ASAY/ASAZ above: (200-128)/256+1=1.28125, (100-128)/256+1=0.890625, (50-128)/256+1=0.6953125"
        magConnection.setRegister(AK8963_REG_HXL, *(s16le(1000) + s16le(-500) + s16le(250) + [0x00]))
        def m = sensor.mag()

        then:
        Math.abs(m[0] - (1000 * 0.15d * 1.28125d)) < 1e-9
        Math.abs(m[1] - (-500 * 0.15d * 0.890625d)) < 1e-9
        Math.abs(m[2] - (250 * 0.15d * 0.6953125d)) < 1e-9

        when:
        magConnection.setRegister(AK8963_REG_HXL, *(s16le(111) + s16le(-222) + s16le(333) + [0x00]))
        def magRaw = sensor.magRaw()

        then:
        magRaw == [111, -222, 333] as int[]
    }

    def "mag not enabled throws"() {
        given:
        def sensor = newInitializedSensor(new MockConnection(), new MockConnection())

        when:
        sensor.mag()

        then:
        thrown(IllegalStateException)

        when:
        sensor.magRaw()

        then:
        thrown(IllegalStateException)
    }
}
