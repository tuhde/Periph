#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "PCF8591.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define PCF8591_ADDR 0x48

const float VREF  = 3.3f;
const float VAGND = 0.0f;

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, PCF8591_ADDR);
    PCF8591Full adc(transport);

    // --- Wire a potentiometer across VAGND–VREF with the wiper to AIN0 ---
    // Connect an LED (with series resistor) to AOUT. In a loop, read AIN0, map
    // the 0–255 value to a DAC output value, and write it to AOUT — the LED
    // brightness tracks the potentiometer. This demonstrates the ADC→DAC
    // feedback path inside a single chip.
    adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, true);       // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                           // single-ended mode with DAC output enabled
    while (1) {
        for (int n = 0; n < 20; n++) {
            uint8_t raw = adc.read_channel(0);                                // Read single channel, (channel=0–3) → uint8_t
            float vin  = VAGND + (float)raw * (VREF - VAGND) / 256.0f;
            adc.set_dac(raw);                                                 // Enable DAC and set raw value, (value=0–255) → None
            float vout = VAGND + (float)raw * (VREF - VAGND) / 256.0f;
            printk("n=%d raw=%u vin=%.3fV  vout=%.3fV\n", n, raw, (double)vin, (double)vout);
            k_sleep(K_MSEC(200));
        }
    }
    return 0;
}
