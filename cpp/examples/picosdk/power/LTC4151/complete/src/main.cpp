#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LTC4151.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x6A);
    LTC4151Full ltc(connection, 0.02f);                                 // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    float f = 0;
    bool ok = false;
    LTC4151Full::Readings r;

    printf("voltage %.3f V\n", ltc.voltage());                          // Read supply voltage, () → float V
                                                                        // VIN code × 25 mV
    printf("current %.4f A\n", ltc.current());                          // Read load current, () → float A
                                                                        // sense voltage / r_sense
    printf("power %.3f W\n", ltc.power());                              // Read load power, () → float W
                                                                        // one block read of SENSE and VIN
    printf("sense voltage %.5f V\n", ltc.sense_voltage());              // Read SENSE+ − SENSE−, () → float V
                                                                        // code × 20 µV
    printf("adin %.4f V\n", ltc.adin_voltage());                        // Read ADIN pin voltage, () → float V
                                                                        // code × 0.5 mV
    ltc.read_all(r);                                                    // Consistent read of all channels, (out Readings) → void
                                                                        // one block read of registers 0x00–0x05
    printf("vin %.2f V, sense %.5f V, current %.4f A, power %.3f W, adin %.4f V\n", r.vin, r.sense_v, r.current, r.power, r.adin);
    printf("raw VIN code %u\n", (unsigned)ltc.read_raw(LTC4151Full::VIN));  // Read raw 12-bit code, (channel 0–2) → uint16_t
                                                                        // SENSE = 0, VIN = 1, ADIN = 2

    ltc.set_sense_resistor(0.01f);                                      // Change sense resistor, (ohms) → bool
                                                                        // false (and no change) for ohms <= 0
    printf("sense resistor %.4f ohm\n", ltc.get_sense_resistor());      // Read sense resistor, () → float Ω
    ltc.set_sense_resistor(0.02f);                                      // Restore 20 mΩ

    ok = ltc.snapshot(LTC4151Full::SENSE, f);                           // One snapshot conversion, (channel 0–2, out V) → bool
                                                                        // returns to continuous scan afterwards; false on timeout
    printf("snapshot ok %u, sense %.5f V\n", (unsigned)ok, f);
    printf("snapshot mode %u\n", (unsigned)ltc.is_snapshot_mode());     // Snapshot mode active, () → bool
    printf("VIN busy %u\n", (unsigned)ltc.is_busy(LTC4151Full::VIN));   // Channel busy, (channel 0–2) → bool
                                                                        // BUSY bit of the channel low register
    ltc.set_continuous();                                               // Back to continuous scan, () → void

    ltc.set_stuck_bus_timer(true);                                      // Stuck-bus reset timer, (enabled) → void
                                                                        // CONTROL.G2 read-modify-write
    ltc.set_page_rw(true);                                              // I²C page read/write, (enabled) → void
                                                                        // CONTROL.G3 read-modify-write
    printf("control 0x%02X\n", (unsigned)ltc.read_control());           // Read CONTROL, () → uint8_t
    ltc.write_control(0x0C);                                            // Write CONTROL, (value) → void
                                                                        // test mode bit 4 is always masked to 0
    printf("temperature %.2f C\n", LTC4151Full::temperature_from_adin(900, 1200));  // NTC example circuit, (adin_code, vin_code) → float °C
    printf("done\n");

    return 0;
}
