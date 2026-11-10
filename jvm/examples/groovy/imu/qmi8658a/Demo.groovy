///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.QMI8658AFull

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = System.getenv().getOrDefault("I2C_ADDR", "0x6B").replaceFirst("^0[xX]", "") as int

def conn = new I2CConnection(bus, addr)
try {
    def imu = new QMI8658AFull(conn)                           // Create QMI8658A driver, (connection) → void

    // --- Identify the part and run the built-in checks ---
    // The revision, firmware version and USID identify the silicon; the
    // self-tests need the board lying still and the gyro calibration trims the
    // X/Y gains before real use.
    def info = imu.deviceInfo() // Read device info, () → DeviceInfo (revision, firmware, usid)
    printf("revision 0x%02X firmware %d.%d.%d%n", info.revision, info.firmware[0], info.firmware[1], info.firmware[2])
    def accelTest = imu.selfTestAccel() // Run accel self-test, () → SelfTestResult (passed, values m/s²)
    printf("accel self-test: %s%n", accelTest.passed ? "PASS" : "FAIL")
    def gyroTest = imu.selfTestGyro() // Run gyro self-test, () → SelfTestResult (passed, values rad/s)
    printf("gyro self-test: %s%n", gyroTest.passed ? "PASS" : "FAIL")
    def cal = imu.calibrateGyro() // Calibrate gyroscope, () → Calibration (success, codStatus)
    printf("gyro calibration: %s%n", cal.success ? "OK" : "FAILED")

    // --- Configure ±4 g / ±512 dps at 112.1 Hz ---
    // ODR code 6 gives 112.1 Hz for both sensors; mode 3 filters keep the
    // noise bandwidth near 15 Hz.
    imu.configureAccel(1, 6) // Configure accelerometer, (fullScale, odr) → void
    imu.configureGyro(5, 6) // Configure gyroscope, (fullScale, odr) → void
    imu.configureFilters(true, 3, true, 3) // Configure low-pass filters, (accelFilter, accelMode, gyroFilter, gyroMode) → void

    // --- Step counting and tap detection ---
    // The pedometer defaults suit ~50 Hz, so the window parameters are scaled
    // for 112 Hz; the tap engine runs best above 200 Hz and is only a coarse
    // detector at this rate.
    imu.configurePedometer(112, 200.0, 100.0, 450, 45, 10, 0, 1) // Configure pedometer, (sampleCount, …) → void
    imu.enablePedometer(true) // Enable pedometer, (enabled) → void
    imu.configureTap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4) // Configure tap engine, (priority, …) → void
    imu.enableTap(true) // Enable tap engine, (enabled) → void

    // --- Stream into the FIFO ---
    // Stream mode with 32 samples and a watermark of 16 is drained once per
    // second; each sample is a 12-byte frame (accel XYZ then gyro XYZ).
    imu.configureFifo(2, 1, 16) // Configure FIFO, (mode, size, watermark) → void
    Thread.sleep(200)

    for (int iteration = 0; iteration < 600; iteration++) {
    // --- Sample, tilt and step count ---
    if (imu.dataReady()) { // Check for new data, () → boolean
        def sm = imu.readAll() // Read one coherent sample, () → Sample (accel m/s², gyro rad/s, tempC °C)
        def roll = Math.toDegrees(Math.atan2(sm.accel[1], sm.accel[2]))
        def pitch = Math.toDegrees(Math.atan2(-sm.accel[0], Math.hypot(sm.accel[1], sm.accel[2])))
        def gmag = Math.sqrt(sm.gyro[0] * sm.gyro[0] + sm.gyro[1] * sm.gyro[1] + sm.gyro[2] * sm.gyro[2])
        def steps = imu.stepCount() // Read step count, () → int
        printf("roll %.1f pitch %.1f |gyro| %.3f rad/s  %.1f C  steps %d%n", roll, pitch, gmag, sm.tempC, steps)
    }

    // --- Report taps ---
    // pollInterrupt() flags a tap without the INT pin wired.
    def status = imu.pollInterrupt() // Poll interrupt status, () → int
    if ((status & QMI8658AFull.SOURCE_TAP) != 0) {
        def tap = imu.tapStatus() // Read tap event, () → TapStatus (count, axis, negative)
        printf("TAP %s on axis %d%n", tap.count == 2 ? "double" : "single", tap.axis)
    }

    // --- Drain the FIFO once per second ---
    if (iteration % 10 == 9) {
        def n = imu.fifoCount() // Read FIFO fill, () → int bytes
        def data = imu.readFifo(-1) // Drain the FIFO, (count) → byte[]
        printf("FIFO: %d bytes (%d)%n", n, data.length, data.length / 12)
    }
    Thread.sleep(100)
    }
} finally {
    conn.close()
}
