#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "INA3221.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x40);
INA3221Full ina(transport, /*r_shunt=*/0.1f);

int main(void) {

    stdio_init_all();


    printf("0x%X\n", (unsigned)ina.manufacturer_id());       // Read Manufacturer ID, () → int 0x5449
                                                       // Texas Instruments ID
    printf("0x%X\n", (unsigned)ina.die_id());                // Read Die ID, () → int 0x3220
                                                       // INA3221 die revision

    for (uint8_t ch = 1; ch <= 3; ch++) {
        printf("%d\n", ina.voltage(ch));             // Read bus voltage, (channel) → float V
                                                       // left-aligned 12-bit bus register, 8 mV LSB
        printf("%d\n", ina.shunt_voltage(ch));        // Read shunt voltage, (channel) → float V
                                                       // left-aligned 13-bit signed shunt, 40 µV LSB
        printf("%d\n", ina.current(ch));              // Read load current, (channel) → float A
                                                       // computed from shunt voltage / r_shunt
        printf("%d\n", ina.power(ch));                // Read power, (channel) → float W
                                                       // computed from voltage × current
    }

    printf("%d\n", ina.conversion_ready());          // Check conversion done, () → bool
                                                       // reads CVRF bit from Mask/Enable register

    ina.configure(4, 4, 4, 7);                         // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → None
                                                       // sets averaging count, conversion time, and operating mode

    ina.enable_channel(1, true);                     // Enable channel, (channel, enabled) → None
                                                       // modifies CH1en bit in Configuration register
    bool ena = ina.channel_enabled(1);               // Read channel enabled, (channel) → bool
                                                       // reads CH1en bit

    ina.set_critical_alert(1, 0.1f);                 // Set critical alert, (channel, limit_v, latch=False) → None
                                                       // per-conversion threshold on shunt voltage
    ina.set_warning_alert(2, 0.05f);                 // Set warning alert, (channel, limit_v, latch=False) → None
                                                       // per-average threshold on shunt voltage

    uint16_t flags = ina.alert_flags();               // Read alert flags, () → int
                                                       // reads Mask/Enable register, clears latched flags

    uint8_t channels[] = {1, 2};
    ina.set_summation_channels(channels, 2, 0.2f);   // Set summation channels, (channels, n, limit_v) → None
                                                       // enables SCC bits and sets sum limit register
    float sv_sum = ina.summation_value();             // Read summation value, () → float V
                                                       // reads Shunt-Voltage Sum register

    ina.set_power_valid_limits(5.5f, 4.5f);           // Set PV limits, (upper_v, lower_v) → None
                                                       // sets PV Upper/Lower Limit registers
    bool pv = ina.power_valid();                      // Read power valid, () → bool
                                                       // reads PVF bit from Mask/Enable

    ina.shutdown();                                   // Put chip into power-down mode, () → None
                                                       // saves current mode for wake()
    sleep_ms(1);
    ina.wake();                                       // Restore operating mode, () → None
                                                       // restores the mode saved by shutdown()

    ina.reset();                                     // Reset all registers, () → None
                                                       // sets RST bit, chip re-initializes to defaults
    return 0;
}
