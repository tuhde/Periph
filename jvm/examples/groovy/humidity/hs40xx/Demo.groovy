///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-groovy:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HS40xxFull

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x54").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new HS40xxFull(conn)                                                               // Create HS40xx driver, (connection) → void

    // --- Humidity watchdog ---
    // Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
    long id = sensor.readId()                                                                       // Read the 32-bit sensor ID, () → long
    printf("ID 0x%08X%n", id)
    for (int n = 0; n < 5; n++) {
        def v = sensor.read()                                                                       // Read temperature and humidity, () → double[] {°C, %RH}
        printf("%.2f C, %.1f %%RH%n", v[0], v[1])
        Thread.sleep(2000)
    }
    sensor.setAlertThresholds(60.0d, 55.0d, null, null, null, null, null, null)                     // Set alert thresholds, (rhHigh, rhHighReset, ...) → void
    sensor.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH)                                               // Enable alert sources, (source) → void
    sensor.startPeriodic(1.0d)                                                                      // Start periodic mode, (periodS) → void
    for (int n = 0; n < 15; n++) {
        Thread.sleep(1000)
        def p = sensor.readPeriodic()                                                               // Fetch the latest periodic result, () → double[] {°C, %RH, status}
        def alert = (((int) p[2]) & HS40xxFull.SOURCE_RH_HIGH) != 0 ? "ALERT" : "ok"
        printf("%.2f C, %.1f %%RH  %s%n", p[0], p[1], alert)
    }
    sensor.stopPeriodic()                                                                           // Stop periodic mode, () → void
    sensor.softReset()                                                                              // Soft reset, () → void
} finally {
    conn.close()
}
