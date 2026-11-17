#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
LM25066Full* hotswapPtr = nullptr;  // constructed in setup(): keeps construction explicit

void setup() {
    Serial.begin(115200);
    Wire.begin();
    hotswapPtr = new LM25066Full(connection, 1.0f, false);   // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)
    LM25066Full& hotswap = *hotswapPtr;
    Serial.println(hotswap.voltage_in());                        // Input voltage, () → float V
    Serial.println(hotswap.voltage_out());                       // Output voltage, () → float V
    Serial.println(hotswap.current());                           // Input current, () → float A
    Serial.println(hotswap.power());                             // Input power, () → float W
    Serial.println(hotswap.temperature());                       // MOSFET temperature, () → float °C
    Serial.println(hotswap.voltage_aux());                       // VAUX pin voltage, () → float V (0 ... 1.16)
    LM25066Block blk = hotswap.read_block();                     // Time-aligned BLOCK_READ, () → LM25066Block
    Serial.println(blk.power);                                   // diagnostic / current / voltage_out / voltage_in / power / temperature
    blk = hotswap.read_average();                                // AVG_BLOCK_READ, () → LM25066Block
    blk = hotswap.black_box();                                   // Snapshot at first SMBA, () → LM25066Block
    Serial.println(hotswap.voltage_in_avg());                    // Averaged VIN, () → float V
    Serial.println(hotswap.voltage_out_avg());                   // Averaged VOUT, () → float V
    Serial.println(hotswap.current_avg());                       // Averaged IIN, () → float A
    Serial.println(hotswap.power_avg());                         // Averaged PIN, () → float W
    Serial.println(hotswap.power_peak());                        // Peak input power, () → float W
    hotswap.clear_power_peak();                                  // Reset the peak register, ()
    hotswap.set_averaging(6);                                    // Average 2^6 samples, (exponent 0-12) → bool
    Serial.println(hotswap.get_averaging());                     // Averaging exponent, () → uint8_t
    hotswap.set_vin_uv_warn(10.0f);                              // VIN under-voltage warning, (volts, NAN = off)
    hotswap.set_vin_ov_warn(15.0f);                              // VIN over-voltage warning, (volts, NAN = off)
    hotswap.set_vout_uv_warn(9.0f);                              // VOUT under-voltage warning, (volts, NAN = off)
    hotswap.set_ot_warn(100.0f);                                 // Over-temperature warning, (celsius, NAN = off)
    hotswap.set_ot_fault(125.0f);                                // Over-temperature fault, (celsius, NAN = off)
    hotswap.set_iin_warn(20.0f);                                 // Input over-current warning, (amps, NAN = off)
    hotswap.set_pin_warn(200.0f);                                // Input over-power warning, (watts, NAN = off)
    LM25066Limits lim = hotswap.get_limits();                    // All thresholds, () → LM25066Limits (NAN = disabled)
    Serial.println(lim.vin_uv_warn);                             // Read back converted limits
    Serial.println(hotswap.status_byte(), HEX);                  // STATUS_BYTE, () → uint8_t
    Serial.println(hotswap.status_word(), HEX);                  // STATUS_WORD, () → uint16_t
    Serial.println(hotswap.status_vout(), HEX);                  // STATUS_VOUT, () → uint8_t
    Serial.println(hotswap.status_input(), HEX);                 // STATUS_INPUT, () → uint8_t
    Serial.println(hotswap.status_temperature(), HEX);           // STATUS_TEMPERATURE, () → uint8_t
    Serial.println(hotswap.status_cml(), HEX);                   // STATUS_CML, () → uint8_t
    Serial.println(hotswap.status_mfr(), HEX);                   // STATUS_MFR_SPECIFIC, () → uint8_t
    Serial.println(hotswap.diagnostic_word(), HEX);              // READ_DIAGNOSTIC_WORD, () → uint16_t
    hotswap.clear_faults();                                      // CLEAR_FAULTS (does not turn the MOSFET on), ()
    Serial.println(hotswap.is_on());                             // MOSFET on, () → bool
    // hotswap.operation_off();                                  // CUTS THE LOAD RAIL, ()
    // hotswap.operation_on();                                   // Switch the MOSFET on, ()
    Serial.println(hotswap.device_setup(), HEX);                 // DEVICE_SETUP, () → uint8_t
    // hotswap.configure_device(7, 0, -1);                       // Override RETRY/CL/CB pins, (retryCode, currentLimitHigh, cbRatioHigh) -1 = pin
    // hotswap.set_gate_mask(0x04);                              // DANGEROUS: disable fault shut-off, (mask bits 5/4/2) → bool
    Serial.println(hotswap.get_gate_mask(), HEX);                // GATE_MASK, () → uint8_t
    Serial.println(hotswap.capability(), HEX);                   // CAPABILITY, () → uint8_t
    char id[16], mdl[16], rev[16];
    hotswap.manufacturer_id(id, sizeof id); hotswap.model(mdl, sizeof mdl); hotswap.revision(rev, sizeof rev); // MFR_ID / MFR_MODEL / MFR_REVISION, (buf, len)
    Serial.println(id); Serial.println(mdl); Serial.println(rev);
    hotswap.enable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN);   // Unmask an alert source, (source)
    hotswap.disable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN);  // Mask an alert source, (source)
    Serial.println(hotswap.pollInterrupt(), HEX);                // Diagnostic word, () → uint16_t
    hotswap.onInterrupt([](uint16_t diag) { Serial.println(diag, HEX); }); // Subscribe to SMBA, (callback, intPin = nullptr)
    hotswap.offInterrupt();                                      // Unsubscribe, ()
}

void loop() {
    delay(1000);
}
