///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Spl06Full;
import java.util.Arrays;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Spl06Full(connection);                                                 // Create SPL06 driver, (connection) → Spl06Full
                                                                                                    // stores the connection only; no bus traffic yet
            sensor.init();                                                                          // Check ID, read coefficients, apply defaults, () → void
                                                                                                    // ID 0x10, waits for COEF_RDY, 16x pressure / 1x external temperature
            System.out.println(sensor.coefficients());                                              // Decoded calibration, () → Coefficients
                                                                                                    // the nine factory coefficients used by every compensation
            System.out.println(sensor.read());                                                      // Temperature and pressure, () → Measurement
                                                                                                    // command mode: one temperature, then one pressure measurement
            System.out.println(sensor.readPressure());                                              // Pressure, () → double Pa
                                                                                                    // measures temperature first, because the compensation needs it
            System.out.println(sensor.readTemperature());                                           // Temperature, () → double °C
                                                                                                    // one temperature measurement only
            var raw = sensor.readRaw();                                                             // Raw results, () → int[] {praw, traw}
                                                                                                    // signed 24-bit values from the result registers
            System.out.println(sensor.compensate(raw[0], raw[1], 16, 1));                           // Compensate raw values, (praw, traw, pOversampling, tOversampling) → Measurement
                                                                                                    // pure computation with the stored coefficients; no bus access
            System.out.println(sensor.altitude(101325.0));                                          // Altitude, (seaLevelPa) → double m
                                                                                                    // international barometric formula against the given sea-level pressure
            
            sensor.setPressureConfig(64, 4);                                                        // Pressure oversampling and rate, (oversampling, rate) → void
                                                                                                    // 64x oversampling, 4 background readings per second; sets P_SHIFT
            sensor.setTemperatureConfig(2, 4);                                                      // Temperature oversampling and rate, (oversampling, rate) → void
                                                                                                    // 2x oversampling, 4 per second; always keeps the external sensor
            sensor.startContinuous(true, true);                                                     // Background mode, (pressure, temperature) → void
                                                                                                    // MEAS_CTRL = 111: the chip measures both on its own
            System.out.println(sensor.readContinuous());                                            // Latest background result, () → Measurement
                                                                                                    // waits for both ready flags; does not start a measurement
            System.out.println(Arrays.toString(sensor.dataReady()));                                // Ready flags, () → boolean[] {pressureReady, temperatureReady}
                                                                                                    // MEAS_CFG.PRS_RDY and TMP_RDY
            sensor.stop();                                                                          // Standby, () → void
                                                                                                    // MEAS_CTRL = 000
            
            sensor.enableFifo();                                                                    // FIFO on, () → void
                                                                                                    // background results now queue in the 32-entry FIFO
            sensor.startContinuous();                                                               // Background mode, () → void
                                                                                                    // fill the FIFO for a second
            Thread.sleep(1000);
            sensor.stop();                                                                          // Standby, () → void
                                                                                                    // stop adding entries
            System.out.println(Arrays.toString(sensor.fifoStatus()));                               // FIFO state, () → boolean[] {empty, full}
                                                                                                    // FIFO_STS flags
            System.out.println(sensor.readFifo());                                                  // Drain FIFO, () → List<FifoEntry {kind, value}>
                                                                                                    // "pressure" in Pa or "temperature" in °C, in measured order
            sensor.flushFifo();                                                                     // Flush FIFO, () → void
                                                                                                    // discards any remaining entries
            sensor.disableFifo();                                                                   // FIFO off, () → void
                                                                                                    // results go to the result registers again
            
            sensor.enableInterrupt(Spl06Full.SOURCE_PRESSURE);                                      // Enable interrupt source, (source) → void
                                                                                                    // pressure-ready interrupt on SDO (active low)
            System.out.println(sensor.pollInterrupt());                                             // Read and clear INT_STS, () → int
                                                                                                    // bitmask of SOURCE_PRESSURE / SOURCE_TEMPERATURE / SOURCE_FIFO_FULL
            sensor.onInterrupt(status -> System.out.println("INT " + status));                      // Subscribe, (callback) → void
                                                                                                    // uses the connection's INT pin, or a 5 ms polling thread without one
            sensor.read();                                                                          // Trigger a measurement, () → Measurement
                                                                                                    // the pressure result raises the interrupt
            Thread.sleep(100);
            sensor.offInterrupt();                                                                  // Unsubscribe, () → void
                                                                                                    // removes this driver's handler only
            sensor.disableInterrupt(Spl06Full.SOURCE_PRESSURE);                                     // Disable interrupt source, (source) → void
                                                                                                    // clears CFG_REG.INT_PRS
            
            sensor.reset();                                                                         // Soft reset, () → void
                                                                                                    // RESET = 0x09, re-reads the coefficients and restores the init defaults
        }
    }
}
