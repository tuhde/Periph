#include <Wire.h>
#include <Periph.h>
#include <math.h>

I2CConnection connection(Wire, 0x77);
SPL06Full sensor(connection);                                      // Create SPL06 driver, (connection) → void

static void on_int(uint8_t status) {
    Serial.print("interrupt "); Serial.print("0x"); Serial.print((unsigned)(status), HEX); Serial.println();
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → bool
                                                                   // ID 0x10, waits for COEF_RDY, 16x pressure / 1x external temperature
    SPL06Coefficients co;
    sensor.coefficients(co);                                       // Decoded calibration, (coefficients&) → void
                                                                   // the nine factory coefficients used by every compensation
    Serial.print("c0 "); Serial.print((long)(co.c0)); Serial.print(" c1 "); Serial.print((long)(co.c1)); Serial.print(" c00 "); Serial.print((long)(co.c00)); Serial.print(" c10 "); Serial.print((long)(co.c10)); Serial.println();
    Serial.print("c01 "); Serial.print((long)(co.c01)); Serial.print(" c11 "); Serial.print((long)(co.c11)); Serial.print(" c20 "); Serial.print((long)(co.c20)); Serial.print(" c21 "); Serial.print((long)(co.c21)); Serial.print(" c30 "); Serial.print((long)(co.c30)); Serial.println();
    float t = 0, p = 0, alt = 0;
    bool ok = sensor.read(t, p);                                   // Temperature and pressure, (temperature_c&, pressure_pa&) → bool
                                                                   // command mode: one temperature, then one pressure measurement
    Serial.print("read "); Serial.print(t, 2); Serial.print(" C "); Serial.print(p, 2); Serial.print(" Pa"); Serial.println();
    ok = sensor.read_pressure(p);                                  // Pressure, (pressure_pa&) → bool
                                                                   // measures temperature first, because the compensation needs it
    ok = sensor.read_temperature(t);                               // Temperature, (temperature_c&) → bool
                                                                   // one temperature measurement only
    Serial.print("pressure "); Serial.print(p, 2); Serial.print(" Pa temperature "); Serial.print(t, 2); Serial.print(" C"); Serial.println();
    int32_t praw = 0, traw = 0;
    ok = sensor.read_raw(praw, traw);                              // Raw results, (praw&, traw&) → bool
                                                                   // signed 24-bit values from the result registers
    Serial.print("raw "); Serial.print((long)(praw)); Serial.print(" "); Serial.print((long)(traw)); Serial.println();
    ok = sensor.compensate(praw, traw, 16, 1, t, p);               // Compensate raw values, (praw, traw, p_oversampling, t_oversampling, temperature_c&, pressure_pa&) → bool
                                                                   // pure computation with the stored coefficients; no bus access
    Serial.print("compensated "); Serial.print(t, 2); Serial.print(" C "); Serial.print(p, 2); Serial.print(" Pa"); Serial.println();
    ok = sensor.altitude(alt, 101325.0f);                          // Altitude, (altitude_m&, sea_level_pa = 101325) → bool
                                                                   // international barometric formula against the given sea-level pressure
    Serial.print("altitude "); Serial.print(alt, 2); Serial.print(" m"); Serial.println();
    ok = sensor.set_pressure_config(64, 4);                        // Pressure oversampling and rate, (oversampling = 16, rate = 1) → bool
                                                                   // 64x oversampling, 4 background readings per second; sets P_SHIFT
    ok = sensor.set_temperature_config(2, 4);                      // Temperature oversampling and rate, (oversampling = 1, rate = 1) → bool
                                                                   // 2x oversampling, 4 per second; always keeps the external sensor
    ok = sensor.start_continuous(true, true);                      // Background mode, (pressure = true, temperature = true) → bool
                                                                   // MEAS_CTRL = 111: the chip measures both on its own
    ok = sensor.read_continuous(t, p);                             // Latest background result, (temperature_c&, pressure_pa&) → bool
                                                                   // waits for both ready flags; does not start a measurement
    Serial.print("continuous "); Serial.print(t, 2); Serial.print(" C "); Serial.print(p, 2); Serial.print(" Pa"); Serial.println();
    bool pr = false, tr = false;
    ok = sensor.data_ready(pr, tr);                                // Ready flags, (pressure_ready&, temperature_ready&) → bool
                                                                   // MEAS_CFG.PRS_RDY and TMP_RDY
    Serial.print("ready "); Serial.print((unsigned long)(pr)); Serial.print(" "); Serial.print((unsigned long)(tr)); Serial.println();
    ok = sensor.stop();                                            // Standby, () → bool
                                                                   // MEAS_CTRL = 000
    ok = sensor.enable_fifo();                                     // FIFO on, () → bool
                                                                   // background results now queue in the 32-entry FIFO
    ok = sensor.start_continuous();                                // Background mode, (pressure = true, temperature = true) → bool
                                                                   // fill the FIFO for a second
    delay(1000);
    ok = sensor.stop();                                            // Standby, () → bool
                                                                   // stop adding entries
    bool empty = false, full = false;
    ok = sensor.fifo_status(empty, full);                          // FIFO state, (empty&, full&) → bool
                                                                   // FIFO_STS flags
    Serial.print("fifo empty "); Serial.print((unsigned long)(empty)); Serial.print(" full "); Serial.print((unsigned long)(full)); Serial.println();
    SPL06Full::FifoEntry entries[32];
    uint8_t n = sensor.read_fifo(entries, 32);                     // Drain FIFO, (entries*, max_entries) → uint8_t
                                                                   // each entry is KIND_PRESSURE (Pa) or KIND_TEMPERATURE (C), in measured order
    Serial.print("fifo "); Serial.print((unsigned long)(n)); Serial.print(" entries"); Serial.println();
    ok = sensor.flush_fifo();                                      // Flush FIFO, () → bool
                                                                   // discards any remaining entries
    ok = sensor.disable_fifo();                                    // FIFO off, () → bool
                                                                   // results go to the result registers again
    ok = sensor.enable_interrupt(SPL06Full::SOURCE_PRESSURE);      // Enable interrupt source, (source) → bool
                                                                   // pressure-ready interrupt on SDO (active low)
    Serial.print("INT_STS "); Serial.print("0x"); Serial.print((unsigned)(sensor.poll_interrupt()), HEX); Serial.println();
    // poll_interrupt(): read and clear INT_STS, () → uint8_t
    sensor.on_interrupt(on_int);                                   // Subscribe, (callback, intPin = nullptr) → void
                                                                   // does nothing without an INT line wired; poll instead
    sensor.off_interrupt();                                        // Unsubscribe, () → void
                                                                   // removes the edge handler
    ok = sensor.disable_interrupt(SPL06Full::SOURCE_PRESSURE);     // Disable interrupt source, (source) → bool
                                                                   // clears CFG_REG.INT_PRS
    ok = sensor.reset();                                           // Soft reset, () → bool
                                                                   // RESET = 0x09, re-reads the coefficients and restores the init defaults
    Serial.print("ok "); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
}
