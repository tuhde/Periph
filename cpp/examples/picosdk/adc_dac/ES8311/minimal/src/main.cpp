#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ES8311.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x18);
    ES8311Minimal codec(connection);  // Create ES8311 driver, (connection, mclk_hz=12288000 Hz, sample_rate_hz=48000 Hz)

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    codec.set_mic_gain(24.0f);                                  // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → void
    codec.set_mute(false);                                      // Unmute the DAC, (muted=false) → void
    codec.set_volume(-20.0f);                                   // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → void
    sleep_ms(5000);
    codec.set_mute(true);                                       // Mute the DAC, (muted=true) → void

    while (true) {
        sleep_ms(1000);
    }
    return 0;
}
