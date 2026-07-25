#include <cstdio>
#include <unistd.h>
#include "DHTxxTransportLinux.h"
#include "DHT11.h"

int main() {
    const char* chip_path = getenv("GPIO_CHIP") ? getenv("GPIO_CHIP") : "/dev/gpiochip0";
    int line_num = getenv("GPIO_LINE") ? atoi(getenv("GPIO_LINE")) : 4;
    DHTxxTransportLinux transport(chip_path, (unsigned)line_num);

    DHT11Minimal dht(transport);                                           // Create DHT11 driver, (transport)

    while (true) {
        float t, h;
        dht.read(t, h);                                                    // Read temperature & humidity, (t°C out, h%RH out) → bool ok
        printf("%.1f C  %.1f %%RH\n", t, h);
        usleep(2000000);
    }
    return 0;
}
