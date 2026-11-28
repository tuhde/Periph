///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gas.Rrh62000Full

import java.util.HexFormat

static String iaqLabel(double iaq) {
    if (iaq < 2) return 'very good'
    if (iaq < 3) return 'good'
    if (iaq < 4) return 'medium'
    if (iaq < 5) return 'poor'
    'bad'
}

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x69").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
        def sensor = new Rrh62000Full(connection)                                              // Create RRH62000 driver, (connection, accessIntervalMs=1000) → Rrh62000Full
        sensor.init()                                                                          // Wait for start-up, check firmware version, () → void

        // --- Identity and settings ---
        // The module may only be accessed once per second; every call below waits
        // for that interval, so this section takes several seconds.
        int[] fw = sensor.firmwareVersion()                                                    // Firmware version, () → int[] {major, minor}
        int[] alg = sensor.algorithmVersion()                                                  // Algorithm version, () → int[] {major, minor, patch}
        System.out.printf("firmware %d.%d, algorithm %d.%d.%d%n", fw[0], fw[1], alg[0], alg[1], alg[2])
        println("unique ID " + HexFormat.of().formatHex(sensor.uniqueId()))         // Unique ID, () → byte[6]
        System.out.printf("moving average %d, fan %d %%, cleaning every %d s for %d s%n",
                sensor.getMovingAverage(), sensor.getFanSpeed(),                                // Moving average, () → int / Fan speed, () → int %
                sensor.getCleaningInterval(), sensor.getCleaningTime())                        // Cleaning interval, () → int s / Cleaning time, () → int s

        // --- Room air-quality station ---
        // A complete sample arrives every 3 s. The IAQ index follows the German UBA
        // scale; PM2.5 uses the KCl reference. Allow up to 48 h of conditioning on
        // first use before trusting TVOC and eCO2.
        long end = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < end) {
            def d = sensor.readAll()                                                           // Complete block, () → Data
            System.out.printf("IAQ %.2f (%s)  PM2.5 %.1f ug/m3  eCO2 %d ppm  TVOC %d ug/m3  %.1f C  %.1f %%RH%n",
                    d.iaq(), iaqLabel(d.iaq()), d.particles().pm2_5UgM3(), d.eco2Ppm(), d.tvoc(),
                    d.temperatureC(), d.humidityRh())
        }

        // --- Particle counts and status ---
        // Number concentrations count particles from the bin size up to 10 µm per cm³.
        def p = sensor.readParticles()                                                         // Particle values, () → Particles
        System.out.printf("particles/cm3  >0.3um %.1f  >0.5um %.1f  >1um %.1f  >2.5um %.1f  >4um %.1f%n",
                p.nc0_3(), p.nc0_5(), p.nc1(), p.nc2_5(), p.nc4())
        System.out.printf("PM2.5 KCl %.1f / smoke %.1f ug/m3%n", p.pm2_5UgM3(), p.pm2_5SmokeUgM3())
        println("status " + sensor.readStatus())                                    // Status flags, () → Status
        println("new data pending: " + sensor.dataReady())                          // New sample available, () → boolean

        // --- Quieter, smoother operation ---
        // 30 moving averages smooth the PM values (slower response); 70 % fan speed
        // is quieter.
        sensor.setMovingAverage(30)                                                            // Moving average, (count 1–60) → void
        sensor.setFanSpeed(70)                                                                 // Fan speed, (percent 60–100) → void
        System.out.printf("moving average %d, fan %d %%%n", sensor.getMovingAverage(), sensor.getFanSpeed())

        // --- Fan cleaning ---
        // A manual cleaning blows dust out of the air channel; the automatic
        // cleaning is shortened to every 3 days for 10 s.
        sensor.startCleaning()                                                                 // Start fan cleaning, () → void
        for (int i = 0; i < 30; i++) {
            if (sensor.cleaningDone()) {                                                        // Cleaning status, () → boolean
                println("cleaning done")
                break
            }
        }
        sensor.setCleaningInterval(3 * 24 * 3600)                                              // Cleaning interval, (seconds) → void
        sensor.setCleaningTime(10)                                                             // Cleaning time, (seconds) → void
        println("cleaning every " + sensor.getCleaningInterval() + " s")

        // --- Sleep, diagnostics, reset ---
        // Sleep stops the fan; after wake-up the first valid sample takes 3.3 s.
        sensor.sleep()                                                                         // Sleep, () → void
        Thread.sleep(5000)
        sensor.wake()                                                                          // Wake up, () → void
        println("MOX resistance " + sensor.readMoxResistance() + " ohm")            // MOX resistance, () → long Ω
        sensor.softReset()                                                                     // Reset, () → void
        println("after reset " + sensor.read())                                     // Main values, () → Measurement
} finally {
    connection.close()
}
