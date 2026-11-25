#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <stdio.h>
#include "I2CConnectionZephyr.h"
#include "ES8311.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define ES8311_ADDR 0x18

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ES8311_ADDR);
    ES8311Minimal codec(connection);  // Create ES8311 driver, (connection, mclk_hz=12288000 Hz, sample_rate_hz=48000 Hz)
    codec.set_mic_gain(24.0f);                                  // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → void
    codec.set_mute(false);                                      // Unmute the DAC, (muted=false) → void
    codec.set_volume(-20.0f);                                   // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → void
    k_msleep(5000);
    codec.set_mute(true);                                       // Mute the DAC, (muted=true) → void
    return 0;
}
