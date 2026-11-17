#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LM25066.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    LM25066Full hotswap(connection, 1.0f, false);      // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)

    printf("%.3f\n", (double)hotswap.voltage_in());          // Input voltage, () → float V
    printf("%.3f\n", (double)hotswap.voltage_out());         // Output voltage, () → float V
    printf("%.3f\n", (double)hotswap.current());             // Input current, () → float A
    printf("%.3f\n", (double)hotswap.power());               // Input power, () → float W
    printf("%.2f\n", (double)hotswap.temperature());         // MOSFET temperature, () → float °C
    printf("%.3f\n", (double)hotswap.voltage_aux());         // VAUX pin voltage, () → float V (0 ... 1.16)
    LM25066Block blk = hotswap.read_block();                 // Time-aligned BLOCK_READ, () → LM25066Block
    printf("0x%04X %.3f A %.3f W\n", (unsigned)blk.diagnostic, (double)blk.current, (double)blk.power); // diagnostic / current / voltage_out / voltage_in / power / temperature
    blk = hotswap.read_average();                            // AVG_BLOCK_READ, () → LM25066Block
    blk = hotswap.black_box();                               // Snapshot at first SMBA, () → LM25066Block
    printf("%.3f %.3f\n", (double)hotswap.voltage_in_avg(), (double)hotswap.voltage_out_avg()); // Averaged VIN / VOUT, () → float V each
    printf("%.3f %.3f\n", (double)hotswap.current_avg(), (double)hotswap.power_avg()); // Averaged IIN / PIN, () → float each
    printf("%.3f\n", (double)hotswap.power_peak());          // Peak input power, () → float W
    hotswap.clear_power_peak();                              // Reset the peak register, ()
    hotswap.set_averaging(6);                                // Average 2^6 samples, (exponent 0-12) → bool
    printf("%u\n", (unsigned)hotswap.get_averaging());       // Averaging exponent, () → uint8_t
    hotswap.set_vin_uv_warn(10.0f);                          // VIN under-voltage warning, (volts, NAN = off)
    hotswap.set_vin_ov_warn(15.0f);                          // VIN over-voltage warning, (volts, NAN = off)
    hotswap.set_vout_uv_warn(9.0f);                          // VOUT under-voltage warning, (volts, NAN = off)
    hotswap.set_ot_warn(100.0f);                             // Over-temperature warning, (celsius, NAN = off)
    hotswap.set_ot_fault(125.0f);                            // Over-temperature fault, (celsius, NAN = off)
    hotswap.set_iin_warn(20.0f);                             // Input over-current warning, (amps, NAN = off)
    hotswap.set_pin_warn(200.0f);                            // Input over-power warning, (watts, NAN = off)
    LM25066Limits lim = hotswap.get_limits();                // All thresholds, () → LM25066Limits (NAN = disabled)
    printf("%.2f %.2f\n", (double)lim.vin_uv_warn, (double)lim.iin_warn); // Read back converted limits
    printf("0x%02X 0x%04X\n", (unsigned)hotswap.status_byte(), (unsigned)hotswap.status_word()); // STATUS_BYTE / STATUS_WORD, () → uint8_t / uint16_t
    printf("0x%02X 0x%02X\n", (unsigned)hotswap.status_vout(), (unsigned)hotswap.status_input()); // STATUS_VOUT / STATUS_INPUT, () → uint8_t each
    printf("0x%02X 0x%02X 0x%02X\n", (unsigned)hotswap.status_temperature(), (unsigned)hotswap.status_cml(), (unsigned)hotswap.status_mfr()); // STATUS_TEMPERATURE / _CML / _MFR_SPECIFIC
    printf("0x%04X\n", (unsigned)hotswap.diagnostic_word()); // READ_DIAGNOSTIC_WORD, () → uint16_t
    hotswap.clear_faults();                                  // CLEAR_FAULTS (does not turn the MOSFET on), ()
    printf("%d\n", (int)hotswap.is_on());                    // MOSFET on, () → bool
    // hotswap.operation_off();                              // CUTS THE LOAD RAIL, ()
    // hotswap.operation_on();                               // Switch the MOSFET on, ()
    printf("0x%02X\n", (unsigned)hotswap.device_setup());    // DEVICE_SETUP, () → uint8_t
    // hotswap.configure_device(7, 0, -1);                   // Override RETRY/CL/CB pins, (retryCode, currentLimitHigh, cbRatioHigh) -1 = pin
    // hotswap.set_gate_mask(0x04);                          // DANGEROUS: disable fault shut-off, (mask bits 5/4/2) → bool
    printf("0x%02X 0x%02X\n", (unsigned)hotswap.get_gate_mask(), (unsigned)hotswap.capability()); // GATE_MASK / CAPABILITY, () → uint8_t each
    char id[16], mdl[16], rev[16];
    hotswap.manufacturer_id(id, sizeof id); hotswap.model(mdl, sizeof mdl); hotswap.revision(rev, sizeof rev); // MFR_ID / MFR_MODEL / MFR_REVISION, (buf, len)
    printf("%s %s %s\n", id, mdl, rev);
    hotswap.enable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN); // Unmask an alert source, (source)
    hotswap.disable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN); // Mask an alert source, (source)
    printf("0x%04X\n", (unsigned)hotswap.pollInterrupt());   // Diagnostic word, () → uint16_t
    hotswap.onInterrupt([](uint16_t diag) { printf("alert 0x%04X\n", (unsigned)diag); }); // Subscribe to SMBA, (callback, intPin = nullptr)
    hotswap.offInterrupt();                                  // Unsubscribe, ()
    return 0;
}
