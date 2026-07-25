#include <cstdio>
#include <unistd.h>
#include "DHTxxTransportLinux.h"
#include "DHT11.h"

int main() {
    const char* chip_path = getenv("GPIO_CHIP") ? getenv("GPIO_CHIP") : "/dev/gpiochip0";
    int line_num = getenv("GPIO_LINE") ? atoi(getenv("GPIO_LINE")) : 4;
    DHTxxTransportLinux transport(chip_path, (unsigned)line_num);

    DHT11Full dht(transport, 3);                                           // Create DHT11 driver, (transport, max_retries=3)

    // --- Indoor comfort monitor ---
    // Reads every 5 s and classifies comfort level.
    while (true) {
        float t, h;
        bool ok = dht.read_retry(3, t, h);                                 // Read with retries, (max_retries 1..255, t out, h out) → bool ok
        if (!ok) { printf("WARN: read failed\n"); usleep(5000000); continue; }
        const char* comfort = h > 60 ? "humid" : h < 30 ? "dry" : "comfortable";
        printf("%.1f C  %.1f %%RH  [%s]\n", t, h, comfort);
        usleep(5000000);
    }
    return 0;
}
