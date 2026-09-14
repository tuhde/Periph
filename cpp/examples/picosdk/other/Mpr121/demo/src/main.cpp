#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/i2c.h"
#include "I2CConnectionPicoSDK.h"
#include "Mpr121.h"

static const char* NOTES[12] = {
    "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4"
};

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x5A);                                   // Create I2C connection, (i2c0, addr=0x5A) → I2CConnectionPicoSDK
    MPR121Full mpr(connection);                                                   // Create MPR121 Full, (connection) → MPR121Full
    uint16_t previous = 0;

    stdio_init_all();
    while (true) {
        uint16_t mask = mpr.touched();                                            // Read 12-bit touch bitmask, () → uint16_t bitmask
        uint16_t newly_pressed = mask & ~previous;
        uint16_t newly_released = (~mask) & previous;
        for (uint8_t n = 0; n < 12; n++) {
            if (newly_pressed & (1u << n)) {
                printf("NOTE ON:  %s\n", NOTES[n]);
            }
            if (newly_released & (1u << n)) {
                printf("NOTE OFF: %s\n", NOTES[n]);
            }
        }
        previous = mask;
        sleep_ms(50);
    }
}
