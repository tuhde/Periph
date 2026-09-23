#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "DRV8830.h"

int main() {
    I2CConnectionLinux connection(1, DRV8830Minimal::I2C_ADDRESS);
    DRV8830Minimal motor(connection);                       // Create DRV8830 driver, (connection)

    while (1) {
        motor.drive(3.0f);                                  // Drive at regulated voltage, (voltage V, + = forward) → void
        usleep(2000UL * 1000UL);
        motor.drive(-3.0f);                                 // Drive at regulated voltage, (voltage V, - = reverse) → void
        usleep(2000UL * 1000UL);
    }
    return 0;
}
