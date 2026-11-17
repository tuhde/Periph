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

    char id[16], mdl[16], rev[16];
    hotswap.manufacturer_id(id, sizeof id); hotswap.model(mdl, sizeof mdl); hotswap.revision(rev, sizeof rev); // Identify the chip, (buf, len)
    printf("%s %s %s\n", id, mdl, rev);
    hotswap.clear_faults();                                  // Release SMBA after power-up, ()
    // Hot-swap rail monitor: one line per second for a minute
    for (int i = 0; i < 60; i++) {
        if (i == 30) {
            hotswap.set_averaging(6);                        // 64-sample averaging, (exponent)
            hotswap.set_vin_uv_warn(10.0f);                  // VIN under-voltage warning at 10 V, (volts)
            hotswap.set_iin_warn(20.0f);                     // Input over-current warning at 20 A, (amps)
            hotswap.enable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN | LM25066Full::SOURCE_IIN_WARN); // Unmask both alerts, (source)
        }
        printf("VIN %.2f V  VOUT %.2f V  IIN %.3f A (avg %.3f)  PIN %.2f W  T %.1f C\n",
               (double)hotswap.voltage_in(), (double)hotswap.voltage_out(), (double)hotswap.current(),
               (double)hotswap.current_avg(), (double)hotswap.power(), (double)hotswap.temperature());
        sleep_ms(1000);
    }
    printf("peak power %.2f W\n", (double)hotswap.power_peak()); // Peak input power, () → float W
    printf("diagnostic 0x%04X\n", (unsigned)hotswap.pollInterrupt()); // Alert flags, () → uint16_t
    hotswap.clear_faults();                                  // Finish servicing, ()
    // hotswap.operation_off();   // CUTS THE LOAD RAIL - only with explicit opt-in
    // hotswap.operation_on();
    return 0;
}
