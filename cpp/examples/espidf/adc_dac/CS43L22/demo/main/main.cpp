#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "CS43L22.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = CS43L22Full::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    CS43L22Full dac(connection);                     // Create CS43L22 Full driver, (connection, resPin=nullptr)

    // --- Bring up the headphone amplifier ---
    // The control port only configures the codec; MCLK = 12.288 MHz and the 48 kHz
    // I2S stream come from the board's I2S master. init() loads the I2S-slave
    // defaults while the chip is powered down; start() powers it up once MCLK runs.
    dac.init();                                      // Reset, verify ID, load defaults, () → bool
    dac.setVolume(-60.0f);                           // Set master volume, (db -102–12 dB) → void
    dac.start();                                     // Power up the DAC once MCLK is running, () → void

    // --- Wait for the serial port to lock ---
    // SPCLKERR reads 1 until LRCK is valid and the MCLK/LRCK ratio is measured;
    // reading the status register clears the flags.
    for (int i = 0; i < 50; i++) {
        CS43L22Status st = dac.readStatus();         // Read status flags (clears them), () → CS43L22Status
        if (!st.clockError) break;
        vTaskDelay(pdMS_TO_TICKS(100));
    }

    // --- Ramp the volume up ---
    // 2 dB steps every 100 ms avoid an abrupt start; the digital soft ramp
    // smooths each step further.
    for (int vol = -60; vol <= 0; vol += 2) {
        dac.setVolume((float)vol);                   // Set master volume, (db -102–12 dB) → void
        vTaskDelay(pdMS_TO_TICKS(100));
    }

    // --- Shape the tone and protect the headphones ---
    // +6 dB bass and treble shelves add presence; the limiter at -6 dB stops
    // loud peaks from clipping the amplifier.
    dac.setTone(6.0f, 6.0f);                         // Configure treble / bass shelves, (trebleDb, bassDb, trebleCorner=0, bassCorner=0, enabled=true) → bool
    dac.setLimiter(true, -6, -12);                   // Configure the peak limiter, (enabled, maxDb=0, cushionDb=0, release=0x3F, attack=0, limitAll=true, softRampDisable=false, zeroCrossDisable=false) → bool

    // --- Play a single beep ---
    // The beep generator needs no audio data; it mixes into the output once MCLK
    // and LRCK are running. Frequency code 0x1 is 521.74 Hz.
    dac.setBeep(0x1, 2, 0, -6.0f, CS43L22Full::BEEP_SINGLE); // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDb, mode, mix=true) → bool
    vTaskDelay(pdMS_TO_TICKS(1500));
    dac.stopBeep();                                  // Stop the beep generator, () → void

    // --- Power down safely ---
    // stop() mutes, disables ramps and waits 100 us after POWER_CTL_1 = 0x9F;
    // only then may the application remove MCLK.
    dac.stop();                                      // Power down the DAC, () → void
    printf("MCLK may now be removed\n");
}
