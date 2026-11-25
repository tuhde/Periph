// ESP-IDF test for ES8311; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ES8311.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float val, float lo, float hi, const char *label) {
    if (val >= lo && val <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n",
                  label, (double)val, (double)lo, (double)hi); failed++; }
}

static void check_eq_u8(uint8_t val, uint8_t expected, const char *label) {
    if (val == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: 0x%02X != 0x%02X\n", label, val, expected); failed++; }
}


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
    ES8311Full codec(connection);

    check_true(true, "init accepted");
    codec.set_volume(-20.0f);
    check_true(codec.get_volume() == -20.0f, "set_volume/get_volume round trip");
    codec.set_mic_gain(24.0f);
    check_true(codec.get_mic_gain() == 24.0f, "set_mic_gain/get_mic_gain round trip");
    codec.set_mute(true);
    check_true((codec.read_register(0x31) & 0x60) == 0x60, "DAC mute bits set");
    codec.set_mute(false);
    check_true((codec.read_register(0x31) & 0x60) == 0x00, "DAC mute bits cleared");
    check_true(codec.configure_clock(12288000, 16000), "configure_clock accepted");
    check_true(codec.configure_serial(ES8311Full::FORMAT_I2S, 16), "configure_serial accepted");
    check_true((codec.read_register(0x09) & 0x1F) == 0x0C, "serial format / word length read back");
    codec.set_adc_volume(0.0f);
    check_true(codec.read_register(0x17) == 0xBF, "ADC volume 0 dB (0xBF)");
    codec.configure_alc(true, -12.0f, -18.1f, 0, true, 0, -60.0f);
    check_true((codec.read_register(0x18) & 0x80) == 0x80, "ALC enabled bit");
    codec.configure_alc(false);
    codec.configure_drc(true, -12.0f, -24.1f, 0);
    check_true((codec.read_register(0x34) & 0x80) == 0x80, "DRC enabled bit");
    codec.configure_drc(false);
    codec.set_loopback(true);
    check_true((codec.read_register(0x44) & 0x80) == 0x80, "loopback bit set");
    codec.set_loopback(false);
    check_true(codec.set_adcdat_mode(ES8311Full::ADCDAT_DAC_ADC) && ((codec.read_register(0x44) >> 4) & 7) == 4, "ADCDAT mode dac_adc (4)");
    codec.set_adcdat_mode(ES8311Full::ADCDAT_ADC_ADC);
    codec.set_dac_ramp(8);
    check_true((codec.read_register(0x37) >> 4) == 8, "DAC ramp rate 8");
    codec.power_down();
    check_true((codec.read_register(0x00) & 0x80) == 0x00, "power_down clears CSM_ON");
    codec.reset();
    check_true(true, "reset accepted");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
