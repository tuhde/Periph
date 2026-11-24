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
    float t = 0, rh = 0, hz = 0;
    int16_t raw = 0;
    uint16_t ts = 0, hs = 0;
    HTS221Calibration cal = {};

    // --- Identify the chip and show the factory calibration ---
    // Every HTS221 stores its own two-point calibration; init() read it and
    // every conversion uses it.
    Serial.print("who am i 0x"); Serial.print((unsigned)(sensor.who_am_i()), HEX); Serial.println();// Read WHO_AM_I, () → uint8_t (0xBC)
    sensor.read_calibration(cal);                                   // Read calibration, (cal) → void
    Serial.print("H0_rH "); Serial.print(cal.h0_rh, 1); Serial.print(" H1_rH "); Serial.print(cal.h1_rh, 1); Serial.print(" T0 "); Serial.print(cal.t0_degc, 2); Serial.print(" C T1 "); Serial.print(cal.t1_degc, 2); Serial.print(" C"); Serial.println();

    // --- One-shot hygrometer: 10 readings, 2 s apart ---
    // Each read() starts a conversion; the dew point shows what the pair
    // means for condensation risk.
    for (int i = 0; i < 10; i++) {
        ok = sensor.read(t, rh);                                    // Read temperature and humidity, (temperature_c, humidity_pct) → bool
    Serial.print("    "); Serial.print(t, 2); Serial.print(" C  "); Serial.print(rh, 1); Serial.print(" %rH  dew "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println();// Compute dew point, () → float °C
        ok = sensor.read_raw_humidity(raw);                         // Read raw humidity, (raw) → bool
    Serial.print("    raw "); Serial.print((long)(raw)); Serial.print(" "); Serial.print((long)(sensor.read_humidity())); Serial.println();// Read humidity only, () → float %rH
    Serial.print("    "); Serial.print(sensor.read_temperature(), 2); Serial.print(" C"); Serial.println();// Read temperature only, () → float °C
        ok = sensor.read_raw_temperature(raw);                      // Read raw temperature, (raw) → bool
    delay(2000);
    }

    // --- Continuous sampling at 1 Hz and 12.5 Hz ---
    // data_ready() paces the loop; the sample count per second follows the rate.
    ok = sensor.set_block_data_update(true);                        // Set block data update, (enabled) → bool
    for (int r = 0; r < 2; r++) {
        ok = sensor.set_output_data_rate(r == 0 ? 1.0f : 12.5f);    // Set output data rate, (hz 0|1|7|12.5) → bool
        ok = sensor.get_output_data_rate(hz);                       // Get output data rate, (hz) → bool
    Serial.print("    rate "); Serial.print(hz, 1); Serial.print(" Hz"); Serial.println();
        int count = 0;
        for (int ms = 0; ms < 3000; ms += 5) {
            if (sensor.data_ready()) {                              // Check data available, () → bool
                ok = sensor.read_continuous(t, rh);                 // Read latest sample, (temperature_c, humidity_pct) → bool
                count++;
            }
            delay(5);
        }
    Serial.print("    "); Serial.print(count / 3.0f, 1); Serial.print(" samples/s"); Serial.println();
    }
    ok = sensor.set_output_data_rate(0);                            // Set output data rate, (hz 0|1|7|12.5) → bool

    // --- Noise at lowest and highest averaging ---
    // Ten one-shot samples each; the spread of the raw humidity shrinks as
    // the averaging grows.
    for (int a = 0; a < 2; a++) {
        ok = sensor.set_averaging(a == 0 ? 2 : 256, a == 0 ? 4 : 512);// Set averaging, (temperature_samples, humidity_samples) → bool
        ok = sensor.get_averaging(ts, hs);                          // Get averaging, (temperature_samples, humidity_samples) → bool
    Serial.print("    averaging "); Serial.print((unsigned long)(ts)); Serial.print(" "); Serial.print((unsigned long)(hs)); Serial.println();
        int lo = 32767, hi = -32768;
        for (int i = 0; i < 10; i++) {
            ok = sensor.read_raw_humidity(raw);                     // Read raw humidity, (raw) → bool
            if (raw < lo) lo = raw;
            if (raw > hi) hi = raw;
        }
    Serial.print("    raw humidity spread "); Serial.print((long)(hi - lo)); Serial.println();
    }
    ok = sensor.set_averaging(16, 32);                              // Set averaging, (temperature_samples, humidity_samples) → bool

    // --- Heater: drive off condensation for 10 s ---
    // Humidity falls and temperature rises while the heater runs; measure
    // before and after, never during, then let the sensor cool.
    float t0 = 0, rh0 = 0;
    ok = sensor.read(t0, rh0);                                      // Read temperature and humidity, (temperature_c, humidity_pct) → bool
    ok = sensor.set_heater(true);                                   // Switch the heater, (on) → bool
    Serial.print("heater on "); Serial.print((unsigned long)(sensor.is_heater_on())); Serial.println();// Heater state, () → bool
    delay(10000);
    ok = sensor.set_heater(false);                                  // Switch the heater, (on) → bool
    ok = sensor.read(t, rh);                                        // Read temperature and humidity, (temperature_c, humidity_pct) → bool
    Serial.print("before "); Serial.print(t0, 2); Serial.print(" C "); Serial.print(rh0, 1); Serial.print(" %rH, after "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %rH"); Serial.println();

    // --- Data-ready notification for 5 s ---
    // Uses the DRDY pin when the connection has one, otherwise polls the status.
    ok = sensor.configure_drdy(true, true, false);                  // Configure DRDY pin, (enabled, active_high=true, open_drain=false) → bool
    ok = sensor.set_output_data_rate(1);                            // Set output data rate, (hz 0|1|7|12.5) → bool
    if (!sensor.on_interrupt(on_drdy)) {                            // Subscribe to data-ready, (callback, intPin=nullptr) → bool
        for (int i = 0; i < 5; i++) {
    Serial.print("        DRDY status 0x"); Serial.print((unsigned)(sensor.poll_interrupt()), HEX); Serial.println();// Poll interrupt status, () → uint8_t
            delay(1000);
        }
    }
    else {
            delay(5000);
        sensor.off_interrupt();                                     // Unsubscribe, () → void
    }
    ok = sensor.configure_drdy(false);
    ok = sensor.set_output_data_rate(0);

    // --- Power cycle and reload ---
    ok = sensor.power_down();                                       // Power down, () → bool
    ok = sensor.power_up();                                         // Power up, () → bool
    ok = sensor.reboot();                                           // Reload trimming, () → bool
    ok = sensor.read(t, rh);
    Serial.print("done "); Serial.print(t, 2); Serial.print(" C ok="); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
    delay(1000);
}
