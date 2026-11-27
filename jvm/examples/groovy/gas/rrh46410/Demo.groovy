///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gas.Rrh46410Full

import java.util.HexFormat

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Rrh46410Full(connection)                                                       // Create RRH46410 driver, (connection) → Rrh46410Full
    sensor.init()                                                                                   // Check product ID, start IAQ 2nd Gen mode, () → void

    // --- Identity ---
    // Get Product ID returns 0x2310 and the firmware version; the tracking number
    // identifies the individual module.
    def id = sensor.productId()                                                                     // Product ID and firmware, () → ProductId
    System.out.printf("product 0x%04X, firmware %d.%d.%d%n", id.productId(), id.major(), id.minor(), id.patch())
    System.out.printf("tracking %s, mode %d%n", HexFormat.of().formatHex(sensor.trackingNumber()),  // Tracking number, () → byte[6]
            sensor.getMode())                                                                       // Operation mode, () → int

    // --- Humidity compensation ---
    // The MOx element reacts to humidity; feeding the real value (e.g. from a
    // humidity sensor) improves the results. The module assumes 50 %RH.
    sensor.setHumidity(42.0d)                                                                       // Ambient humidity, (percent %RH) → void

    // --- Indoor air-quality monitor ---
    // A new sample arrives every 3 s; the first 100 samples (about 5 min) are the
    // warm-up during which the values stay fixed.
    int warmup = sensor.warmupSamples()                                                             // Warm-up length, () → int samples
    System.out.printf("sample period %.0f s, warm-up %d samples%n", sensor.samplePeriod(), warmup)  // Sample period, () → double s
    20.times {
        def r = sensor.read()                                                                       // Next sample, () → Measurement
        def progress = r.warmedUp() ? "ok" : "warm-up ${Math.min(100, (int) (r.sample() * 100 / warmup))}%"
        System.out.printf("IAQ %.1f (%s)  TVOC %.2f mg/m3  EtOH %.2f ppm  eCO2 %d ppm  rel %d  %s%n",
                r.iaq(), Rrh46410Full.airQualityLabel(r.iaq()), r.tvocMgM3(),                      // UBA rating, (iaq) → String
                r.etohPpm(), r.eco2Ppm(), r.relativeIaq(), progress)
    }

    // --- Interrupt instead of polling ---
    // With INT wired the driver reacts to its rising edge; otherwise it polls the
    // sample counter. The callback receives the decoded result.
    sensor.onInterrupt({ r -> System.out.printf("INT sample %d IAQ %.1f%n", r.sample(), r.iaq()) } as java.util.function.Consumer) // Subscribe, (callback) → void
    Thread.sleep(10_000)
    sensor.offInterrupt()                                                                           // Unsubscribe, () → void
    while (!sensor.pollInterrupt()) Thread.sleep(500)                                               // New sample pending, () → boolean
    if (sensor.waitForSample(4.0d)) {                                                               // Wait for a new sample, (timeout s) → boolean
        println "latest ${sensor.readResults()}"                                                   // Latest result, () → Measurement or null
    }

    // --- Other operation modes ---
    // PBAQ reports TVOC and ethanol for public buildings; ULP saves power with
    // one sample every 90 s. Each mode change restarts the warm-up.
    [(Rrh46410Full.MODE_PBAQ): 'PBAQ', (Rrh46410Full.MODE_ULP): 'ULP'].each { mode, name ->
        sensor.setMode(mode as int)                                                                 // Operation mode, (mode) → void
        System.out.printf("%s: sample every %.0f s, warm-up %d samples%n", name,
                sensor.samplePeriod(), sensor.warmupSamples())                                      // Mode timing, () → double s / int
    }
    sensor.setMode(Rrh46410Full.MODE_PBAQ)                                                          // Operation mode, (mode) → void
    println "PBAQ ${sensor.read()}"                                                                // Next sample, () → Measurement

    // --- GPIOs ---
    // GPIO1 and GPIO2 can drive an indicator LED or a fan relay.
    sensor.configGpio(0x03)                                                                         // Both outputs, (directions) → void
    3.times {
        sensor.setGpio(0x01)                                                                        // GPIO1 high, (mask) → void
        Thread.sleep(200)
        sensor.clearGpio(0x01)                                                                      // GPIO1 low, (mask) → void
        Thread.sleep(200)
    }
    println "GPIO levels ${Integer.toBinaryString(sensor.getGpio())}"                              // GPIO levels, () → int
    println "status ${sensor.status()}"                                                            // Last status byte, () → Status
    println "raw mode ${sensor.sendCommand(0x10, new byte[0], 1)[0]}"                              // Raw request, (command, params, responseLen) → byte[]

    // --- Wrap up ---
    // cleanSensor(true) runs the 1-minute cleaning once in the sensor's lifetime;
    // it is skipped here. Suspend stops measuring; reset restarts the module in SUSPEND.
    sensor.suspend()                                                                                // Suspend mode, () → void
    sensor.reset()                                                                                  // Reset, () → void
    println "mode after reset ${sensor.getMode()}"                                                 // Operation mode, () → int
} finally {
    connection.close()
}
