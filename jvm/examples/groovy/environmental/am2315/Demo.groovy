///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.environmental.AM2315Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x5C").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new AM2315Full(conn)                                        // Create AM2315 driver, (connection) → void

    def info = sensor.readDeviceInfo()                                       // Read device information, () → long[] {model, version, device id}
    printf("model %d, version %d, device id 0x%08X%n", info[0], info[1], info[2])

    // --- Weather-station logger: a sample every 5 s, min/max tracking ---
    // The sensor measures after a communication and returns that result on the next
    // one, so every sample is the previous measurement; the driver enforces the >= 2 s
    // spacing and the CRC-16 check.
    double tMin = 1000, tMax = -1000, hMin = 1000, hMax = -1000
    for (int i = 1; i <= 30; i++) {
        def v = (i % 10 == 0) ? sensor.readFresh()                                                  // Real-time value, () → double[] {°C, %RH}
                              : sensor.read()                                                       // Read temperature and humidity, () → double[] {°C, %RH}
        double t = v[0], rh = v[1]
        tMin = Math.min(tMin, t); tMax = Math.max(tMax, t)
        hMin = Math.min(hMin, rh); hMax = Math.max(hMax, rh)
        // Magnus formula
        double g = Math.log(Math.max(rh, 0.1d) / 100.0d) + 17.62d * t / (243.12d + t)
        double dew = 243.12d * g / (17.62d - g)
        printf("%s  %.1f C  %.1f %%RH  dew point %.1f C  (T %.1f..%.1f, RH %.1f..%.1f)%n",
                java.time.LocalTime.now().withNano(0), t, rh, dew, tMin, tMax, hMin, hMax)
        Thread.sleep(3000)
    }

    // --- User registers as a persistent counter ---
    sensor.writeUserRegisters(30, 0xBEEF)                                    // Write user registers, (user1, user2) → void
    def user = sensor.readUserRegisters()                                    // Read user registers, () → int[] {user1, user2}
    printf("user registers %d 0x%04X%n", user[0], user[1])
} finally {
    conn.close()
}
