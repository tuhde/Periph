#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "PCF8591.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x48);
    PCF8591Full adc(transport);

    const float VREF  = 3.3f;
    const float VAGND = 0.0f;

    stdio_init_all();

    adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, true);       // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                           // single-ended mode with DAC output enabled
    while (true) {

    // --- Wire a potentiometer across VAGND–VREF with the wiper to AIN0 ---
    // Connect an LED (with series resistor) to AOUT. In a loop, read AIN0, map
    // the 0–255 value to a DAC output value, and write it to AOUT — the LED
    // brightness tracks the potentiometer. This demonstrates the ADC→DAC
    // feedback path inside a single chip.
    for (int n = 0; n < 20; n++) {
        uint8_t raw = adc.read_channel(0);                                // Read single channel, (channel=0–3) → uint8_t
        float vin  = VAGND + (float)raw * (VREF - VAGND) / 256.0f;
        adc.set_dac(raw);                                                 // Enable DAC and set raw value, (value=0–255) → None
        float vout = VAGND + (float)raw * (VREF - VAGND) / 256.0f;
        printf("n=");     printf("%d", n);
        printf(" raw=");  printf("%d", raw);
        printf(" vin=");  printf("%.3f", vin);
        printf("V  vout="); printf("%.3f", vout);
        printf("V\n");
        sleep_ms(200);
    }
        sleep_ms(10);
    }

    return 0;
}
