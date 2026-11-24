#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x60);
MPL3115A2Full sensor(connection);                                  // Create MPL3115A2 driver, (connection) → void

static void on_interrupt(uint8_t status) {
    Serial.print("INT_SOURCE "); Serial.print("0x"); Serial.print((unsigned)(status), HEX); Serial.println();
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    float p = 0, t = 0, v = 0, dv = 0, dt = 0, alt = 0;
    sensor.init();                                                 // Reset and apply defaults, () → bool
                                                                   // 0xC4 identity check, PT_DATA_CFG = 0x07, barometer, OSR 128
    Serial.print("who_am_i "); Serial.print("0x"); Serial.print((unsigned)(sensor.who_am_i()), HEX); Serial.println();
    bool ok = sensor.read_pressure(p);                             // One-shot pressure, (pressure_pa&) → bool
    ok = sensor.read_temperature(t);                               // One-shot temperature, (temperature_c&) → bool
    ok = sensor.read(p, t);                                        // Both together, (pressure_pa&, temperature_c&) → bool
    Serial.print("pressure "); Serial.print(p, 2); Serial.print(" Pa temperature "); Serial.print(t, 2); Serial.print(" C"); Serial.println();
    ok = sensor.set_oversample(32);                                // Oversample ratio, (ratio 1/2/4/…/128) → bool
                                                                   // ratio 128 gives 0.25 Pa resolution at 512 ms per sample
    Serial.print("oversample "); Serial.print((unsigned long)(sensor.get_oversample())); Serial.println();
    ok = sensor.set_sea_level_pressure(101325.0f);                 // Sea-level reference, (pa) → bool
                                                                   // BAR_IN in 2 Pa units
    ok = sensor.set_offsets(0.0f, 0.0f, 0);                        // Offset trims, (pressure_pa, temperature_c, altitude_m) → bool
    ok = sensor.read_altitude(alt);                                // Altimeter reading, (altitude_m&) → bool
                                                                   // switches to altimeter mode
    Serial.print("altitude "); Serial.print(alt, 2); Serial.print(" m"); Serial.println();
    ok = sensor.set_mode(false);                                   // Barometer mode, (altimeter) → bool
    ok = sensor.start_continuous(0);                               // Periodic acquisition, (step_exp 0-15) → bool
                                                                   // one sample every 2^n seconds
    Serial.print("active "); Serial.print((unsigned long)(sensor.is_active())); Serial.println();
    ok = sensor.read_continuous(v, t);                             // Wait for PTDR and read, (value&, temperature_c&) → bool
    Serial.print("continuous "); Serial.print(v, 2); Serial.print(" "); Serial.print(t, 2); Serial.println();
    ok = sensor.data_ready();                                      // Data-ready flag, () → bool
    ok = sensor.read_delta(dv, dt);                                // Change since previous sample, (delta_value&, delta_temperature_c&) → bool
    ok = sensor.stop_continuous();                                 // Back to STANDBY, () → bool
    ok = sensor.set_target(100000.0f, 200.0f, -5, 3);              // Threshold targets, (value, window, temperature_c, temperature_window) → bool
    ok = sensor.load_current_as_target();                          // Current output as target, () → bool
    float pmin = 0, pmax = 0, tmin = 0, tmax = 0;
    ok = sensor.read_min_max(pmin, pmax, tmin, tmax);              // Captured extremes, (p_min&, p_max&, t_min&, t_max&) → bool
    Serial.print("min "); Serial.print(pmin, 2); Serial.print(" max "); Serial.print(pmax, 2); Serial.println();
    ok = sensor.clear_min_max();                                   // Clear extremes, () → bool
    ok = sensor.fifo_setup(MPL3115A2Full::FIFO_CIRCULAR, 16);      // Configure the FIFO, (mode 0/1/2, watermark 0-63) → bool
    bool overflow = false, watermark = false;
    uint8_t count = 0;
    ok = sensor.fifo_status(overflow, watermark, count);           // FIFO state, (overflow&, watermark&, count&) → bool
    float fv[32], ft[32];
    uint8_t n = sensor.fifo_read(fv, ft, 32);                      // Drain the FIFO, (values*, temperatures*, max) → uint8_t
    Serial.print("fifo "); Serial.print((unsigned long)(n)); Serial.print(" samples"); Serial.println();
    ok = sensor.fifo_setup(MPL3115A2Full::FIFO_OFF);               // FIFO off, (mode, watermark = 0) → bool
    ok = sensor.set_interrupt_pins(false, false, false, false);    // INT polarity / drive, (polarity1, open_drain1, polarity2, open_drain2) → bool
    ok = sensor.enable_interrupt(MPL3115A2Full::SOURCE_DRDY, 1);   // Route a source to INT1, (source, pin 1/2) → bool
    Serial.print("INT_SOURCE "); Serial.print("0x"); Serial.print((unsigned)(sensor.poll_interrupt()), HEX); Serial.println();
    sensor.on_interrupt(on_interrupt, 1);                          // Subscribe to INT1 edges, (callback, pin = 1, intPin = nullptr) → void
                                                                   // does nothing without an INT line wired
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    ok = sensor.disable_interrupt(MPL3115A2Full::SOURCE_DRDY);     // Disable the source, (source) → bool
    ok = sensor.reset();                                           // Software reset and defaults, () → bool
    Serial.print("ok "); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
}
