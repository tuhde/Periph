#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "DS3502.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, DS3502Minimal::I2C_ADDRESS);
    DS3502Full pot(connection, 15.0f, 0.0f);            // Create DS3502 Full driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    // --- Power-up position ---
    // The constructor leaves the wiper where the IVR put it (factory default 0x40).
    uint8_t up = pot.getWiper();                        // Read wiper position, () → uint8_t 0–127
    printf("power-up wiper %d -> %.3f V\n", (int)up, (double)pot.getVoltage());

    // --- Set 6.0 V ---
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    float ratio = pot.getRatio();                       // Read divider ratio, () → float
    printf("code %d ratio %.3f voltage %.3f V\n", (int)pot.getWiper(), (double)ratio, (double)pot.getVoltage());

    // --- Sweep in 16 steps ---
    // The step size is (V_RH − V_RL)/127, about 118 mV at a 15 V span.
    for (int code = 0; code <= 127; code += 8) {
        pot.setWiper(code);                             // Set wiper position, (code 0–127) → void
        printf("code %d -> %.3f V\n", code, (double)pot.getVoltage());
        sleep_ms(200);
    }
    pot.setWiper(127);

    // --- Step up and down ---
    pot.setVoltage(6.0f);
    uint8_t up4 = pot.increment(4);                     // Step the wiper, (steps=1) → uint8_t 0–127
    uint8_t dn4 = pot.increment(-4);
    printf("+4 -> %d, -4 -> %d\n", (int)up4, (int)dn4);

    // --- MODE ---
    // MODE = 1 (the working mode) keeps wiper writes in SRAM; storeWiper switches
    // to MODE = 0 only for the duration of the EEPROM write.
    bool eeprom = pot.getMode();                        // Read MODE, () → bool
    printf("eeprom mode %d\n", (int)eeprom);
    pot.setMode(false);                                 // Select SRAM-only writes, (eeprom bool) → void

    // --- Persist the chosen code ---
    // This consumes one of the EEPROM's limited write cycles.
    int code = pot.getWiper();
    bool stored = pot.storeWiper(code);                 // Store power-up position, (code=-1) → bool
    bool ready = pot.waitReady();                       // Acknowledge polling, (timeoutMs=30) → bool
    printf("stored %d, ready %d, read back %d\n", (int)stored, (int)ready, (int)pot.getWiper());
    return 0;
}
