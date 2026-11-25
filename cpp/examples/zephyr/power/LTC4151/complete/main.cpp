#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LTC4151.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define LTC4151_ADDR 0x6A

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, LTC4151_ADDR);
    LTC4151Full ltc(connection, 0.02f);                                 // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void
    float f = 0;
    bool ok = false;
    LTC4151Full::Readings r;

    printk("voltage %.3f V\n", (double)(ltc.voltage()));                // Read supply voltage, () → float V
                                                                        // VIN code × 25 mV
    printk("current %.4f A\n", (double)(ltc.current()));                // Read load current, () → float A
                                                                        // sense voltage / r_sense
    printk("power %.3f W\n", (double)(ltc.power()));                    // Read load power, () → float W
                                                                        // one block read of SENSE and VIN
    printk("sense voltage %.5f V\n", (double)(ltc.sense_voltage()));    // Read SENSE+ − SENSE−, () → float V
                                                                        // code × 20 µV
    printk("adin %.4f V\n", (double)(ltc.adin_voltage()));              // Read ADIN pin voltage, () → float V
                                                                        // code × 0.5 mV
    ltc.read_all(r);                                                    // Consistent read of all channels, (out Readings) → void
                                                                        // one block read of registers 0x00–0x05
    printk("vin %.2f V, sense %.5f V, current %.4f A, power %.3f W, adin %.4f V\n", (double)(r.vin), (double)(r.sense_v), (double)(r.current), (double)(r.power), (double)(r.adin));
    printk("raw VIN code %u\n", (unsigned)ltc.read_raw(LTC4151Full::VIN));  // Read raw 12-bit code, (channel 0–2) → uint16_t
                                                                        // SENSE = 0, VIN = 1, ADIN = 2

    ltc.set_sense_resistor(0.01f);                                      // Change sense resistor, (ohms) → bool
                                                                        // false (and no change) for ohms <= 0
    printk("sense resistor %.4f ohm\n", (double)(ltc.get_sense_resistor()));  // Read sense resistor, () → float Ω
    ltc.set_sense_resistor(0.02f);                                      // Restore 20 mΩ

    ok = ltc.snapshot(LTC4151Full::SENSE, f);                           // One snapshot conversion, (channel 0–2, out V) → bool
                                                                        // returns to continuous scan afterwards; false on timeout
    printk("snapshot ok %u, sense %.5f V\n", (unsigned)ok, (double)(f));
    printk("snapshot mode %u\n", (unsigned)ltc.is_snapshot_mode());     // Snapshot mode active, () → bool
    printk("VIN busy %u\n", (unsigned)ltc.is_busy(LTC4151Full::VIN));   // Channel busy, (channel 0–2) → bool
                                                                        // BUSY bit of the channel low register
    ltc.set_continuous();                                               // Back to continuous scan, () → void

    ltc.set_stuck_bus_timer(true);                                      // Stuck-bus reset timer, (enabled) → void
                                                                        // CONTROL.G2 read-modify-write
    ltc.set_page_rw(true);                                              // I²C page read/write, (enabled) → void
                                                                        // CONTROL.G3 read-modify-write
    printk("control 0x%02X\n", (unsigned)ltc.read_control());           // Read CONTROL, () → uint8_t
    ltc.write_control(0x0C);                                            // Write CONTROL, (value) → void
                                                                        // test mode bit 4 is always masked to 0
    printk("temperature %.2f C\n", (double)(LTC4151Full::temperature_from_adin(900, 1200)));  // NTC example circuit, (adin_code, vin_code) → float °C
    printk("done\n");

    return 0;
}
