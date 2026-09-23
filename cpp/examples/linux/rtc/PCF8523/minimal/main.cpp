#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8523.h"

int main() {
    I2CConnectionLinux connection(1, PCF8523Minimal::I2C_ADDRESS);

    PCF8523Minimal rtc(connection);                          // Create PCF8523 driver, (connection)

    for (int i = 0; i < 10; ++i) {
        PCF8523Minimal::DateTime dt;
        rtc.getDatetime(dt);                                 // Read calendar clock, () → DateTime
        printf("%04u-%02u-%02u %02u:%02u:%02u\n",
            dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
        usleep(1000 * 1000);
    }
    return 0;
}
