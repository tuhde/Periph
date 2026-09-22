#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "DS3231.h"

int main() {
    I2CConnectionLinux connection(1, DS3231Minimal::I2C_ADDRESS);
    DS3231Minimal rtc(connection);                          // Create DS3231 driver, (connection)

    while (true) {
        DS3231Minimal::DateTime dt;
        rtc.getDatetime(dt);                                // Read calendar clock, () → DateTime
        printf("%04u-%02u-%02u %02u:%02u:%02u  %.2f C\n",
               dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second,
               (double)rtc.readTemperature());               // Read on-chip temperature, () → float C
        sleep(1);
    }
    return 0;
}
