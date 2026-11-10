#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF85063A.h"

int main() {
    I2CConnectionLinux connection(1, PCF85063AMinimal::I2C_ADDRESS);

    PCF85063AMinimal rtc(connection);                        // Create PCF85063A driver, (connection)

    for (int i = 0; i < 10; ++i) {
        PCF85063AMinimal::DateTime dt;
        rtc.getDatetime(dt);                                 // Read calendar clock, () → DateTime
        printf("%04u-%02u-%02u %02u:%02u:%02u\n",
            dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
        usleep(1000 * 1000);
    }
    return 0;
}
