#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCA9541.h"

int main() {
    I2CConnectionLinux connection(1, PCA9541Minimal::I2C_ADDRESS);
    PCA9541Minimal selector(connection);                // Create PCA9541 driver, (connection)
    bool before = selector.hasControl();                // Report control, () → bool
    printf("has control %d\n", (int)before);
    bool ok = selector.acquire();                       // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    printf("acquire %d\n", (int)ok);
    usleep(1000UL * 1000UL);
    selector.release();                                 // Give the bus to the other master, () → void
    printf("has control %d\n", (int)selector.hasControl());
    return 0;
}
