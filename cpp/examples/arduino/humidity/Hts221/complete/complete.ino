#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x5F);
HTS221Full sensor(connection);                                      // Create HTS221 driver, (connection) → void

static void on_drdy(uint8_t status) {
    Serial.print("DRDY status 0x"); Serial.print((unsigned)(status), HEX); Serial.println();
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    bool ok = sensor.init();                                        // Read calibration and start one-shot mode, () → bool
                                                                    // decodes the per-device two-point coefficients
    float t = 0, rh = 0, hz = 0;
    int16_t raw = 0;
    uint16_t ts = 0, hs = 0;
    HTS221Calibration cal = {};
    Serial.print("who am i 0x"); Serial.print((unsigned)(sensor.who_am_i()), HEX); Serial.println();// Read WHO_AM_I, () → uint8_t (0xBC)
                                                                    // identifies the chip on the bus
    ok = sensor.read(t, rh);                                        // Read temperature and humidity, (temperature_c, humidity_pct) → bool
                                                                    // one-shot conversion, converted with the calibration
    Serial.print("temperature "); Serial.print(t, 2); Serial.print(" C humidity "); Serial.print(rh, 1); Serial.print(" %rH"); Serial.println();
    Serial.print("humidity "); Serial.print(sensor.read_humidity(), 1); Serial.println();// Read humidity only, () → float %rH
                                                                    // one-shot conversion, clamped to 0–100 (NAN on error)
    Serial.print("temperature "); Serial.print(sensor.read_temperature(), 2); Serial.println();// Read temperature only, () → float °C
                                                                    // one-shot conversion (NAN on error)
    ok = sensor.read_raw_humidity(raw);                             // Read raw humidity, (raw) → bool
                                                                    // signed 16-bit ADC output
    Serial.print("raw humidity "); Serial.print((long)(raw)); Serial.println();
    ok = sensor.read_raw_temperature(raw);                          // Read raw temperature, (raw) → bool
                                                                    // signed 16-bit ADC output
    Serial.print("raw temperature "); Serial.print((long)(raw)); Serial.println();
    sensor.read_calibration(cal);                                   // Read calibration, (cal) → void
                                                                    // decoded two-point coefficients
    Serial.print("H0_rH "); Serial.print(cal.h0_rh, 1); Serial.print(" H1_rH "); Serial.print(cal.h1_rh, 1); Serial.print(" T0 "); Serial.print(cal.t0_degc, 2); Serial.print(" C T1 "); Serial.print(cal.t1_degc, 2); Serial.print(" C"); Serial.println();
    Serial.print("dew point "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println();// Compute dew point, () → float °C
                                                                    // Magnus formula over one measurement (NAN on error)
    Serial.print("status 0x"); Serial.print((unsigned)(sensor.status()), HEX); Serial.println();// Read STATUS_REG, () → uint8_t
                                                                    // STATUS_H_DA = 0x02, STATUS_T_DA = 0x01
    Serial.print("data ready "); Serial.print((unsigned long)(sensor.data_ready())); Serial.println();// Check data available, () → bool
                                                                    // both H_DA and T_DA set

    ok = sensor.set_output_data_rate(1);                            // Set output data rate, (hz 0|1|7|12.5) → bool
                                                                    // continuous sampling at 1 Hz
    ok = sensor.get_output_data_rate(hz);                           // Get output data rate, (hz) → bool
                                                                    // decodes CTRL_REG1.ODR
    Serial.print("rate "); Serial.print(hz, 1); Serial.print(" Hz"); Serial.println();
    delay(1200);
    ok = sensor.read_continuous(t, rh);                             // Read latest sample, (temperature_c, humidity_pct) → bool
                                                                    // no conversion is started
    Serial.print("continuous "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %rH"); Serial.println();
    ok = sensor.set_output_data_rate(0);                            // Set output data rate, (hz 0|1|7|12.5) → bool
                                                                    // back to one-shot mode

    ok = sensor.set_averaging(32, 64);                              // Set averaging, (temperature_samples 2–256, humidity_samples 4–512) → bool
                                                                    // more samples lower the noise and lengthen the conversion
    ok = sensor.get_averaging(ts, hs);                              // Get averaging, (temperature_samples, humidity_samples) → bool
                                                                    // decodes AV_CONF
    Serial.print("averaging "); Serial.print((unsigned long)(ts)); Serial.print(" "); Serial.print((unsigned long)(hs)); Serial.println();
    ok = sensor.set_averaging(16, 32);
    ok = sensor.set_block_data_update(true);                        // Set block data update, (enabled) → bool
                                                                    // keeps LSB/MSB of one sample consistent

    ok = sensor.set_heater(true);                                   // Switch the heater, (on) → bool
                                                                    // drives off condensation; do not measure while on
    Serial.print("heater on "); Serial.print((unsigned long)(sensor.is_heater_on())); Serial.println();// Heater state, () → bool
                                                                    // reads CTRL_REG2.HEATER
    ok = sensor.set_heater(false);

    ok = sensor.configure_drdy(true, true, false);                  // Configure DRDY pin, (enabled, active_high=true, open_drain=false) → bool
                                                                    // routes data-ready to the DRDY pin
    Serial.print("poll 0x"); Serial.print((unsigned)(sensor.poll_interrupt()), HEX); Serial.println();// Poll interrupt status, () → uint8_t
                                                                    // STATUS_REG; flags clear when the data is read
    ok = sensor.configure_drdy(false);

    ok = sensor.power_down();                                       // Power down, () → bool
                                                                    // clears CTRL_REG1.PD
    ok = sensor.power_up();                                         // Power up, () → bool
                                                                    // sets CTRL_REG1.PD
    ok = sensor.on_interrupt(on_drdy);                              // Subscribe to data-ready, (callback, intPin=nullptr) → bool
                                                                    // needs a DRDY pin on the connection
    sensor.off_interrupt();                                         // Unsubscribe, () → void
                                                                    // disables the DRDY output
    ok = sensor.reboot();                                           // Reload trimming, () → bool
                                                                    // sets BOOT, waits, re-reads the calibration
    Serial.print("done ok="); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
    delay(1000);
}
