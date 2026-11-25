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
    pot.setWiper(100);                                  // Set wiper position, (code 0–127) → void
    // writes WR only; the EEPROM is not touched
    uint8_t w = pot.getWiper();                         // Read wiper position, () → uint8_t 0–127
    // reads WR; always the live wiper, never the IVR
    printf("wiper %d\n", (int)w);
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    // nearest code of V = V_RL + (V_RH − V_RL)·code/127
    float v = pot.getVoltage();                         // Read unloaded RW voltage, () → float V
    // evaluates the divider formula for the current code
    printf("voltage %.3f V\n", (double)v);
    pot.setRatio(0.25f);                                // Set divider ratio, (ratio 0.0–1.0) → void
    // code = round(ratio × 127)
    float ratio = pot.getRatio();                       // Read divider ratio, () → float
    // code / 127
    printf("ratio %.4f\n", (double)ratio);
    float rl = pot.getResistance('L');                  // Read wiper-to-RL resistance, (terminal='L') → float Ω
    // nominal, RTOTAL = 10 kΩ ±20 %
    float rh = pot.getResistance('H');                  // Read wiper-to-RH resistance, (terminal='L') → float Ω
    printf("R_WL %.1f ohm, R_WH %.1f ohm\n", (double)rl, (double)rh);
    uint8_t n = pot.increment(5);                       // Step the wiper, (steps=1) → uint8_t 0–127
    // read, add, clamp, write; returns the new code
    printf("incremented to %d\n", (int)n);
    bool eeprom = pot.getMode();                        // Read MODE, () → bool
    // true if writes to 0x00 also program the EEPROM
    printf("eeprom mode %d\n", (int)eeprom);
    pot.setMode(false);                                 // Select SRAM-only writes, (eeprom bool) → void
    // CR = 0x80; true would write CR = 0x00
    bool stored = pot.storeWiper(64);                   // Store power-up position, (code=-1) → bool
    // MODE = 0, write WR+IVR, wait for the EEPROM, MODE = 1
    printf("stored %d\n", (int)stored);
    bool ready = pot.waitReady();                       // Acknowledge polling, (timeoutMs=30) → bool
    // false if the chip keeps NACKing
    printf("ready %d\n", (int)ready);
    pot.setEndpoints(12.0f, 2.0f);                      // Change RH / RL voltages, (vHigh V, vLow=0.0 V) → void
    // affects the conversions only; no bus traffic
    return 0;
}
