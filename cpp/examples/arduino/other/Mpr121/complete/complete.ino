#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x5A);                                    // Create I2C connection, (Wire, addr=0x5A) → I2CConnection
MPR121Full mpr(connection);                                              // Create MPR121 Full, (connection) → MPR121Full
                                                                        // runs Minimal init then exposes Full configuration methods

void setup() {
    Serial.begin(115200);
    Wire.begin();

    mpr.stop();                                                         // Enter Stop Mode, () → void
                                                                        // required before writing most config registers
    mpr.configure_thresholds(0, 15, 8);                                 // Set thresholds, (electrode=0, touch=15, release=8) → void
                                                                        // ELE0: touch at 15 LSBs below baseline, release at 8 LSBs
    mpr.configure_all_thresholds(12, 6);                                // Apply thresholds to all, (touch=12, release=6) → void
                                                                        // ELE1..ELE11: same touch/release values
    mpr.configure_proximity_thresholds(8, 4);                           // Set ELEPROX thresholds, (touch=8, release=4) → void
                                                                        // ELEPROX touch at 8 LSBs, release at 4
    mpr.configure_baseline_filter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0);     // Set baseline filter, (mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) → void
                                                                        // baseline filter defaults; tracks capacitance drift only
    mpr.configure_sampling(16, 1, 0, 0, 4);                             // Set AFE config, (cdc=16, cdt=1, ffi=0, sfi=0, esi=4) → void
                                                                        // 16 µA global CDC, 0.5 µs charge time, 16 ms sample interval
    mpr.configure_debounce(1, 1);                                       // Set debounce, (touch=1, release=1) → void
                                                                        // one consecutive measurement to detect touch/release
    mpr.configure_autoconfig(3300, 0, false, true, true);               // Configure autoconfig, (vdd_mv=3300, retry=0, scts=false, are=true, ace=true) → void
                                                                        // recompute USL/TL/LSL for 3.3 V supply
    mpr.start(12, 2, 0);                                                // Enter Run Mode, (n_electrodes=12, cl=2, eleprox_en=0) → void
                                                                        // all 12 electrodes, baseline init from first measurement, proximity off
}

void loop() {
    uint16_t t = mpr.touched();                                         // Read 12-bit touch bitmask, () → uint16_t bitmask
                                                                        // bit n=1 means ELEn is currently touched
    uint16_t f0 = mpr.filtered(0);                                      // Read ELE0 filtered, (electrode=0) → uint16_t 0..1023
                                                                        // raw 10-bit value, inversely proportional to capacitance
    uint16_t b0 = mpr.baseline(0);                                      // Read ELE0 baseline, (electrode=0) → uint16_t 0..1023
                                                                        // 8 MSBs from baseline register, shifted left 2
    uint16_t oor = mpr.oor_status();                                    // Read OOR bitmask, () → uint16_t bitmask
                                                                        // bits 0..11 = ELE0..11 out-of-range
    bool pt = mpr.proximity_touched();                                  // Read proximity touched, () → bool
                                                                        // False: ELEPROX not enabled in this example
    Serial.print("t=0x"); Serial.print(t, HEX);
    Serial.print(" f0="); Serial.print(f0);
    Serial.print(" b0="); Serial.print(b0);
    Serial.print(" oor=0x"); Serial.print(oor, HEX);
    Serial.print(" pt="); Serial.println(pt);
    mpr.enable_interrupt(MPR121Full::SOURCE_OOR);                       // Enable interrupt source, (source=SOURCE_OOR) → void
                                                                        // OOR will now assert INT as well as touch/release
    mpr.disable_interrupt(MPR121Full::SOURCE_OOR);                      // Disable interrupt source, (source=SOURCE_OOR) → void
                                                                        // OOR no longer asserts INT
    mpr.clear_overcurrent();                                            // Clear OVCF, () → void
                                                                        // safe no-op when overcurrent has not occurred
    mpr.reset();                                                        // Soft reset, () → void
                                                                        // reapplies Minimal defaults; device ready for fresh use
    delay(1000);
}
