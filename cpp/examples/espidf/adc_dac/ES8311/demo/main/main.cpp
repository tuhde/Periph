// ESP-IDF example for ES8311 (Demo) using I2CConnectionESPIDF.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ES8311.h"

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
    dev_cfg.device_address = 0x18;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ES8311Full codec(connection);  // Create ES8311 driver, (connection, mclk_hz=12288000 Hz, sample_rate_hz=48000 Hz)

    // --- Bring up the codec for a voice assistant ---
    // The control port only configures the codec; the I2S stream (MCLK, BCLK,
    // LRCK, DSDIN, ASDOUT) is produced elsewhere. 16 kHz is the usual rate for
    // speech recognition.
    codec.configure_clock(12288000, 16000);                     // Program the clock tree, (mclk_hz Hz, sample_rate_hz Hz) → bool
    codec.configure_serial(ES8311Full::FORMAT_I2S, 16);         // Configure the digital audio port, (format, word_length=16) → bool

    // --- Configure the microphone path ---
    // 24 dB analog gain plus the ALC keeps quiet and loud talkers at a similar
    // level; the noise gate and automute silence the line between utterances.
    codec.set_mic_gain(24.0f);                                  // Set microphone PGA gain, (db 0–30 dB) → void
    float g = codec.get_mic_gain();                             // Read back PGA gain, () → float dB
    printf("mic gain dB %.1f\n", (double)g);
    codec.set_adc_volume(0.0f);                                 // Set ADC digital volume, (db −95.5–32 dB) → void
    codec.configure_alc(true, -12.0f, -18.1f, 0, true, 0, -60.0f); // Configure ALC, (enabled, max_level_db, min_level_db, window, automute, automute_window, noise_gate_db) → void
    codec.configure_adc(24, 64, false, true);                   // Configure ADC path, (scale_db=24 dB, osr=64, double_speed=false, hpf=true) → bool

    // --- Configure playback ---
    // The DRC compresses loud peaks so a small speaker does not clip; the soft
    // ramp avoids clicks while the volume changes.
    codec.configure_drc(true, -12.0f, -24.1f, 0);               // Configure DRC, (enabled, max_level_db, min_level_db, window) → void
    codec.set_dac_ramp(8);                                      // Set DAC soft-ramp rate, (rate 0–15) → void
    codec.set_mute(false);                                      // Unmute the DAC, (muted=false) → void

    // --- Ramp the playback volume ---
    // Step from -60 dB to -10 dB, reading the value back each time.
    for (int vol = -60; vol <= -10; vol += 10) {
        codec.set_volume((float)vol);                           // Set DAC volume, (db −95.5–32 dB) → void
        float v = codec.get_volume();                           // Read back DAC volume, () → float dB
        printf("volume dB %.1f\n", (double)v);
        vTaskDelay(pdMS_TO_TICKS(500));
    }

    // --- Echo-cancellation feedback and loopback ---
    // "dac_adc" puts the DAC data next to the microphone on ADCDAT so the host
    // can cancel the speaker echo; the loopback routes the microphone to the
    // speaker for a quick acoustic check.
    codec.set_adcdat_mode(ES8311Full::ADCDAT_DAC_ADC);          // Select ADCDAT content, (mode ADCDAT_*) → bool
    vTaskDelay(pdMS_TO_TICKS(2000));
    codec.set_adcdat_mode(ES8311Full::ADCDAT_ADC_ADC);          // Restore ADCDAT content, (mode ADCDAT_*) → bool
    codec.set_loopback(true);                                   // Route ADC output into the DAC, (enabled) → void
    vTaskDelay(pdMS_TO_TICKS(2000));
    codec.set_loopback(false);                                  // Disable loopback, (enabled=false) → void

    // --- Shut down ---
    // Mute first so power-down does not pop, then enter standby.
    codec.set_mute(true);                                       // Mute the DAC, (muted=true) → void
    codec.power_down();                                         // Enter standby, () → void
}
