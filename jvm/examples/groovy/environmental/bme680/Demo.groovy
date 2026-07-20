///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Bme680Full

/**
 * Room air quality probe: 5-minute run at 0.2 Hz with gas sensor for VOC detection.
 *
 * Polls all four BME680 channels (temperature, pressure, humidity, gas resistance)
 * every 5 seconds for 60 ticks. At tick 30 the user is prompted to expose the
 * sensor to a VOC source (e.g. a marker pen or breath). After the run, prints
 * min/max/mean for all channels and the VOC response ratio (baseline vs exposed
 * gas resistance).
 */
final int    SAMPLES     = 60
final long   INTERVAL_MS = 5000
final int    VOC_TICK    = 30

def bus  = (System.getenv("I2C_BUS")  ?: "1").toInteger()
def addr = Integer.decode(System.getenv("I2C_ADDR") ?: "0x76")
def transport = new I2CTransport(bus, addr)          // open I²C bus, (bus, address=0x76) → I2CTransport
try {
    def sensor = new Bme680Full(transport)                  // construct driver, verifies chip ID, reads calibration, (transport) → Bme680Full

    // --- Configure for room air quality monitoring ---
    // ×4 oversampling on temperature and pressure gives good SNR for
    // environmental logging. ×2 humidity oversampling balances accuracy
    // with conversion time. IIR filter ×3 smooths pressure steps from
    // doors and windows without lagging behind real weather changes.
    sensor.configure(                                       // configure oversampling, mode, filter, (osrsT, osrsP, osrsH, mode, filter) → void
        Bme680Full.OSRS_X4,
        Bme680Full.OSRS_X4,
        Bme680Full.OSRS_X2,
        Bme680Full.MODE_FORCED,
        Bme680Full.FILTER_3)

    // --- Configure heater for gas sensing ---
    // 320 °C for 150 ms is the Bosch-recommended default for indoor
    // air quality. The heater burns off residual contaminants on the
    // MOx surface so the resistance reading reflects current VOC levels.
    sensor.setHeater(320, 150)                              // configure heater profile 0, (tempC=320 °C, durationMs=150 ms) → void

    double[] temps      = new double[SAMPLES]
    double[] pressures  = new double[SAMPLES]
    double[] humidities = new double[SAMPLES]
    double[] gasValues  = new double[SAMPLES]

    double baselineGas  = 0.0d
    double exposedGas   = 0.0d
    int baselineCount   = 0
    int exposedCount    = 0

    // --- 60-tick sampling loop at 5-second intervals ---
    // Each iteration triggers a single TPHG cycle and records all four
    // values. At tick 30 the user is prompted to introduce a VOC source
    // so the gas resistance drop can be observed in the second half.
    for (int i = 0; i < SAMPLES; i++) {
        if (i == VOC_TICK) {
            println("\n>>> Expose sensor to VOC source now (e.g. marker pen, breath) <<<\n")
        }

        double[] all = sensor.readAll()                     // read all four values from one TPHG cycle, () → double[4]
        double t = all[0]
        double p = all[1]
        double h = all[2]
        double g = all[3]

        temps[i]      = t
        pressures[i]  = p
        humidities[i] = h
        gasValues[i]  = g

        if (!Double.isNaN(g)) {
            if (i < VOC_TICK) {
                baselineGas += g
                baselineCount++
            } else {
                exposedGas += g
                exposedCount++
            }
        }

        String gasStr = Double.isNaN(g) ? "invalid" : String.format("%.0f Ω", g)
        printf("[%2d] temperature=%.2f °C  pressure=%.2f hPa  humidity=%.1f %%RH  gas=%s%n",
            i + 1, t, p, h, gasStr)

        Thread.sleep(INTERVAL_MS)
    }

    // --- Print summary statistics ---
    // After the run, report the full range and mean for each channel so
    // the user can see how the environment changed during the session.
    double minT = temps[0], maxT = temps[0], sumT = 0
    double minP = pressures[0], maxP = pressures[0], sumP = 0
    double minH = humidities[0], maxH = humidities[0], sumH = 0
    for (int i = 0; i < SAMPLES; i++) {
        minT = Math.min(minT, temps[i]);       maxT = Math.max(maxT, temps[i]);       sumT += temps[i]
        minP = Math.min(minP, pressures[i]);   maxP = Math.max(maxP, pressures[i]);   sumP += pressures[i]
        minH = Math.min(minH, humidities[i]);  maxH = Math.max(maxH, humidities[i]);  sumH += humidities[i]
    }
    printf("%n--- Summary (%d samples) ---%n", SAMPLES)
    printf("Temperature: min=%.2f °C  max=%.2f °C  mean=%.2f °C%n", minT, maxT, sumT / SAMPLES)
    printf("Pressure:    min=%.2f hPa  max=%.2f hPa  mean=%.2f hPa%n", minP, maxP, sumP / SAMPLES)
    printf("Humidity:    min=%.1f %%RH  max=%.1f %%RH  mean=%.1f %%RH%n", minH, maxH, sumH / SAMPLES)

    // --- VOC response analysis ---
    // Compare the mean gas resistance before and after VOC exposure.
    // A significant drop indicates the MOx sensor detected VOCs; the
    // ratio quantifies the sensor's response magnitude.
    if (baselineCount > 0 && exposedCount > 0) {
        double meanBaseline = baselineGas / baselineCount
        double meanExposed  = exposedGas / exposedCount
        double ratio        = meanBaseline / meanExposed
        printf("%n--- VOC Response ---%n")
        printf("Baseline gas resistance: %.0f Ω (mean of %d readings)%n", meanBaseline, baselineCount)
        printf("Exposed gas resistance:  %.0f Ω (mean of %d readings)%n", meanExposed, exposedCount)
        printf("Response ratio:          %.2f× (>1 = VOC detected)%n", ratio)
    } else {
        printf("%n--- VOC Response ---%n")
        println("Insufficient valid gas readings for VOC analysis.")
    }

} finally {
    transport.close()
}
