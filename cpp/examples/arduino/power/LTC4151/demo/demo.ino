#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x6A);                                   // Create I²C connection, (Wire, addr=0x6A) → Connection
LTC4151Full ltc(connection, 0.02f);                                     // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- High-side supply monitor ---
    // Continuous scan: print every channel once per second for 20 s.
    for (int n = 0; n < 20; n++) {
        LTC4151Full::Readings r;
        ltc.read_all(r);                                                // Consistent read of all channels, (out Readings) → void
        Serial.print(r.vin, 2); Serial.print(" V  "); Serial.print(r.sense_v * 1000.0f, 2); Serial.print(" mV  "); Serial.print(r.current, 4); Serial.print(" A  "); Serial.print(r.power, 3); Serial.print(" W  adin "); Serial.print(r.adin, 3); Serial.println(" V");
        delay(1000);
    }

    // --- Compare snapshot conversion times ---
    // SENSE needs about 67 ms, VIN and ADIN about 33 ms.
    static const char* const names[3] = { "SENSE", "VIN", "ADIN" };
    for (uint8_t ch = 0; ch < 3; ch++) {
        float f = 0;
        uint32_t t0 = millis();
        bool ok = ltc.snapshot(ch, f);                                  // One snapshot conversion, (channel 0–2, out V) → bool
        uint32_t dt = millis() - t0;
        Serial.print(names[ch]); Serial.print(" snapshot "); Serial.print((unsigned)ok); Serial.print(", "); Serial.print(f, 4); Serial.print(" V in "); Serial.print((unsigned)dt); Serial.println(" ms");
    }
    Serial.print("snapshot mode afterwards "); Serial.print((unsigned)ltc.is_snapshot_mode()); Serial.println();  // Snapshot mode active, () → bool

    // --- Toggle the stuck-bus timer and page read/write ---
    // The CONTROL register shows both bits cleared, then restored.
    ltc.set_stuck_bus_timer(false);                                     // Stuck-bus reset timer, (enabled) → void
    ltc.set_page_rw(false);                                             // I²C page read/write, (enabled) → void
    Serial.print("CONTROL (timer off, page off) 0x"); Serial.print((unsigned)ltc.read_control(), HEX); Serial.println();  // Read CONTROL, () → uint8_t
    ltc.set_stuck_bus_timer(true);                                      // Stuck-bus reset timer, (enabled) → void
    ltc.set_page_rw(true);                                              // I²C page read/write, (enabled) → void
    Serial.print("CONTROL (restored) 0x"); Serial.print((unsigned)ltc.read_control(), HEX); Serial.println();  // Read CONTROL, () → uint8_t

    // --- NTC temperature from ADIN ---
    // A made-up ADIN / VIN code pair run through the datasheet example formula.
    Serial.print("temperature "); Serial.print(LTC4151Full::temperature_from_adin(900, 1200), 1); Serial.println(" C");  // NTC example circuit, (adin_code, vin_code) → float °C

    // --- Power tally ---
    // Sample the power 25 times at 200 ms (5 s) and print min, max and average.
    float pmin = 1e9f, pmax = 0, psum = 0;
    for (int n = 0; n < 25; n++) {
        float p = 0;
        p = ltc.power();
        if (p < pmin) pmin = p;
        if (p > pmax) pmax = p;
        psum += p;
        delay(200);
    }
    Serial.print("power min "); Serial.print(pmin, 3); Serial.print(" W, max "); Serial.print(pmax, 3); Serial.print(" W, avg "); Serial.print(psum / 25.0f, 3); Serial.println(" W");
}

void loop() {
    delay(1000);
}
