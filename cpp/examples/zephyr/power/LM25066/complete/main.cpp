#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LM25066.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define LM25066_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, LM25066_ADDR);
    LM25066Full hotswap(connection, 1.0f, false);      // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)

    printk("%.3f\n", (double)hotswap.voltage_in());          // Input voltage, () → float V
    printk("%.3f\n", (double)hotswap.voltage_out());         // Output voltage, () → float V
    printk("%.3f\n", (double)hotswap.current());             // Input current, () → float A
    printk("%.3f\n", (double)hotswap.power());               // Input power, () → float W
    printk("%.2f\n", (double)hotswap.temperature());         // MOSFET temperature, () → float °C
    printk("%.3f\n", (double)hotswap.voltage_aux());         // VAUX pin voltage, () → float V (0 ... 1.16)
    LM25066Block blk = hotswap.read_block();                 // Time-aligned BLOCK_READ, () → LM25066Block
    printk("0x%04X %.3f A %.3f W\n", (unsigned)blk.diagnostic, (double)blk.current, (double)blk.power); // diagnostic / current / voltage_out / voltage_in / power / temperature
    blk = hotswap.read_average();                            // AVG_BLOCK_READ, () → LM25066Block
    blk = hotswap.black_box();                               // Snapshot at first SMBA, () → LM25066Block
    printk("%.3f %.3f\n", (double)hotswap.voltage_in_avg(), (double)hotswap.voltage_out_avg()); // Averaged VIN / VOUT, () → float V each
    printk("%.3f %.3f\n", (double)hotswap.current_avg(), (double)hotswap.power_avg()); // Averaged IIN / PIN, () → float each
    printk("%.3f\n", (double)hotswap.power_peak());          // Peak input power, () → float W
    hotswap.clear_power_peak();                              // Reset the peak register, ()
    hotswap.set_averaging(6);                                // Average 2^6 samples, (exponent 0-12) → bool
    printk("%u\n", (unsigned)hotswap.get_averaging());       // Averaging exponent, () → uint8_t
    hotswap.set_vin_uv_warn(10.0f);                          // VIN under-voltage warning, (volts, NAN = off)
    hotswap.set_vin_ov_warn(15.0f);                          // VIN over-voltage warning, (volts, NAN = off)
    hotswap.set_vout_uv_warn(9.0f);                          // VOUT under-voltage warning, (volts, NAN = off)
    hotswap.set_ot_warn(100.0f);                             // Over-temperature warning, (celsius, NAN = off)
    hotswap.set_ot_fault(125.0f);                            // Over-temperature fault, (celsius, NAN = off)
    hotswap.set_iin_warn(20.0f);                             // Input over-current warning, (amps, NAN = off)
    hotswap.set_pin_warn(200.0f);                            // Input over-power warning, (watts, NAN = off)
    LM25066Limits lim = hotswap.get_limits();                // All thresholds, () → LM25066Limits (NAN = disabled)
    printk("%.2f %.2f\n", (double)lim.vin_uv_warn, (double)lim.iin_warn); // Read back converted limits
    printk("0x%02X 0x%04X\n", (unsigned)hotswap.status_byte(), (unsigned)hotswap.status_word()); // STATUS_BYTE / STATUS_WORD, () → uint8_t / uint16_t
    printk("0x%02X 0x%02X\n", (unsigned)hotswap.status_vout(), (unsigned)hotswap.status_input()); // STATUS_VOUT / STATUS_INPUT, () → uint8_t each
    printk("0x%02X 0x%02X 0x%02X\n", (unsigned)hotswap.status_temperature(), (unsigned)hotswap.status_cml(), (unsigned)hotswap.status_mfr()); // STATUS_TEMPERATURE / _CML / _MFR_SPECIFIC
    printk("0x%04X\n", (unsigned)hotswap.diagnostic_word()); // READ_DIAGNOSTIC_WORD, () → uint16_t
    hotswap.clear_faults();                                  // CLEAR_FAULTS (does not turn the MOSFET on), ()
    printk("%d\n", (int)hotswap.is_on());                    // MOSFET on, () → bool
    // hotswap.operation_off();                              // CUTS THE LOAD RAIL, ()
    // hotswap.operation_on();                               // Switch the MOSFET on, ()
    printk("0x%02X\n", (unsigned)hotswap.device_setup());    // DEVICE_SETUP, () → uint8_t
    // hotswap.configure_device(7, 0, -1);                   // Override RETRY/CL/CB pins, (retryCode, currentLimitHigh, cbRatioHigh) -1 = pin
    // hotswap.set_gate_mask(0x04);                          // DANGEROUS: disable fault shut-off, (mask bits 5/4/2) → bool
    printk("0x%02X 0x%02X\n", (unsigned)hotswap.get_gate_mask(), (unsigned)hotswap.capability()); // GATE_MASK / CAPABILITY, () → uint8_t each
    char id[16], mdl[16], rev[16];
    hotswap.manufacturer_id(id, sizeof id); hotswap.model(mdl, sizeof mdl); hotswap.revision(rev, sizeof rev); // MFR_ID / MFR_MODEL / MFR_REVISION, (buf, len)
    printk("%s %s %s\n", id, mdl, rev);
    hotswap.enable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN); // Unmask an alert source, (source)
    hotswap.disable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN); // Mask an alert source, (source)
    printk("0x%04X\n", (unsigned)hotswap.pollInterrupt());   // Diagnostic word, () → uint16_t
    hotswap.onInterrupt([](uint16_t diag) { printk("alert 0x%04X\n", (unsigned)diag); }); // Subscribe to SMBA, (callback, intPin = nullptr)
    hotswap.offInterrupt();                                  // Unsubscribe, ()
    return 0;
}
