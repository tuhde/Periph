///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-groovy:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.color.As7262Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x49").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new As7262Full(conn)                                                               // Create AS7262 driver, (connection) → void
    sensor.init()                                                                                   // Reset and configure defaults, () → void

    // --- Spectrometer ---
    // Versions, gain sweep, integration time comparison, LED reflectance difference, continuous mode.
    def hw = sensor.hardwareVersion()                                                               // Hardware version, () → int[] {type, version}
    def fw = sensor.firmwareVersion()                                                               // Firmware version, () → int[] {major, minor, sub}
    printf("type 0x%X hw 0x%X fw %d.%d.%d%n", sensor.deviceType(), hw[1], fw[0], fw[1], fw[2])
    printf("chip %.0f C%n", sensor.temperature())
    for (double gain : [1.0d, 3.7d, 16.0d, 64.0d]) {
        sensor.setGain(gain)                                                                        // Set gain, (gain) → void
        def cal = sensor.read()                                                                     // Read calibrated, () → Channels
        def raw = sensor.readRaw()                                                                  // Read raw, () → RawChannels
        printf("gain %.1f V %.2f raw V %d%n", sensor.getGain(), cal.violet, raw.violet)
    }
    sensor.setGain(16.0)                                                                            // Set gain, (gain) → void
    for (double ms : [50.0d, 500.0d]) {
        sensor.setIntegrationTime(ms)                                                               // Set integration time, (ms) → void
        def t0 = System.currentTimeMillis()
        sensor.read()                                                                               // One-shot read, () → Channels
        printf("integration %.1f ms took %d ms%n", sensor.getIntegrationTime(), System.currentTimeMillis() - t0)
    }
    sensor.setIntegrationTime(165.2)                                                                // Set integration time, (ms) → void
    sensor.setMode(3)                                                                               // Set bank mode, (mode) → void
    def base = sensor.read().toArray()                                                              // Read calibrated, () → Channels
    sensor.setIndicatorLed(true, 4.0)                                                               // Indicator LED, (on, currentMa) → void
    sensor.setLed(true, 25.0)                                                                       // Driver LED, (on, currentMa) → void
    def lit = sensor.read().toArray()                                                               // Read calibrated, () → Channels
    sensor.setLed(false, 25.0)                                                                      // Driver LED off, (on, currentMa) → void
    sensor.setIndicatorLed(false, 4.0)                                                              // Indicator LED off, (on, currentMa) → void
    int best = 0
    for (int i = 1; i < 6; i++) if (lit[i] - base[i] > lit[best] - base[best]) best = i
    printf("reflectance dominant channel %d%n", best)
    sensor.measure()                                                                                // Start one-shot, () → void
    while (!sensor.dataReady()) Thread.sleep(50)                                                    // Data ready, () → boolean
    def last = sensor.readCalibratedLast()                                                          // Last calibrated results, () → Channels
    def lastRaw = sensor.readRawLast()                                                              // Last raw counts, () → RawChannels
    printf("last V %.2f raw V %d channel 0 %.2f%n", last.violet, lastRaw.violet, sensor.readChannel(0))
    sensor.setContinuous(2)                                                                         // Continuous mode, (mode) → void
    sensor.onInterrupt({ int status -> println(sensor.readCalibratedLast().violet) } as java.util.function.IntConsumer)  // Subscribe to INT, (callback) → void
    Thread.sleep(5000)
    sensor.offInterrupt()                                                                           // Unsubscribe, () → void
    sensor.setMode(3)                                                                               // Back to one-shot, (mode) → void
    sensor.writeVirtual(0x05, 0x3B)                                                                 // Write virtual register, (reg, value) → void
    printf("INT_T %d%n", sensor.readVirtual(0x05))
    sensor.softReset()                                                                              // Soft reset, () → void
} finally {
    conn.close()
}
