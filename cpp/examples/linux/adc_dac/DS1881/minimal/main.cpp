#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "DS1881.h"

int main() {
    I2CConnectionLinux connection(1, DS1881Minimal::I2C_ADDRESS);
    DS1881Minimal amp(connection);                      // Create DS1881 driver, (connection)
    amp.init();                                         // Apply defaults, () → void
    static const int dbs[] = {0, 10, 20, 40};
    for (int i = 0; i < 4; i++) {
        amp.setAttenuation(0, (float)dbs[i]);           // Set attenuation, (channel 0–1, db dB) → void
        amp.setAttenuation(1, (float)dbs[i]);           // Set attenuation, (channel 0–1, db dB) → void
        float a = amp.getAttenuation(0);                // Read attenuation, (channel 0–1) → float dB
        printf("attenuation %d dB, read back %d dB\n", dbs[i], (int)a);
        usleep(500UL * 1000UL);
    }
    amp.mute(0);                                        // Mute channel, (channel 0–1) → void
    return 0;
}
