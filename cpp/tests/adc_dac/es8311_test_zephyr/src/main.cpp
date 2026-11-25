#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ES8311.h"

#ifndef ES8311_I2C_NODE
#define ES8311_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef ES8311_ADDR
#define ES8311_ADDR 0x18
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(ES8311_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ES8311_ADDR);
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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
