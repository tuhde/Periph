#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "CS43L22.h"

int main() {
    I2CConnectionLinux connection(1, CS43L22Minimal::I2C_ADDRESS);
    CS43L22Minimal dac(connection);                  // Create CS43L22 driver, (connection, resPin=nullptr)
    dac.init();                                      // Reset, verify ID, load defaults, () → bool
    dac.start();                                     // Power up the DAC once MCLK is running, () → void
    dac.setVolume(-20.0f);                           // Set master volume, (db -102–12 dB) → void
    dac.mute(false);                                 // Unmute the headphone outputs, (on=true) → void
    for (int i = 0; i < 5; i++) {
        float v = dac.getVolume();                   // Read back master volume, () → float dB
        printf("volume %d dB\n", (int)v);
        usleep((1000) * 1000UL);
    }
    dac.stop();                                      // Power down the DAC, () → void
    return 0;
}
