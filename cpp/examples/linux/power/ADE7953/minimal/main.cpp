#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADE7953.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

static const float VOLTAGE_GAIN = 251.0f;
static const float CURRENT_GAIN = 30.0f;

int main() {
    I2CConnectionLinux conn(TEST_I2C_BUS, TEST_ADDR);
    ADE7953Minimal ade(conn, VOLTAGE_GAIN, CURRENT_GAIN);            // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    while (true) {
        float v   = ade.voltage();                                    // Read bus voltage, () → V
        float i   = ade.current();                                    // Read load current, () → A
        float p   = ade.activePower();                                // Read active power, () → W
        float e   = ade.activeEnergy();                               // Read active energy, () → Wh
        std::printf("V=%.2f  I=%.3f  P=%.2f  E=%.4f\n", v, i, p, e);
        usleep(1000000);
    }
    return 0;
}