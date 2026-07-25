#include <cstdio>
#include <unistd.h>
#include "DHTxxTransportLinux.h"
#include "DHT11.h"

int main() {
    const char* chip_path = getenv("GPIO_CHIP") ? getenv("GPIO_CHIP") : "/dev/gpiochip0";
    int line_num = getenv("GPIO_LINE") ? atoi(getenv("GPIO_LINE")) : 4;
    DHTxxTransportLinux transport(chip_path, (unsigned)line_num);

    DHT11Full dht(transport, 3);                                           // Create DHT11 driver, (transport, max_retries=3)

    float t = dht.read_temperature();                                      // Read temperature, () → float °C
    float h = dht.read_humidity();                                         // Read humidity, () → float %RH
    printf("t=%.1f h=%.1f\n", t, h);
    float t2, h2;
    bool ok = dht.read_retry(5, t2, h2);                                   // Read with retries, (max_retries 1..255, t out, h out) → bool ok
    uint8_t raw[5];
    dht.read_raw_with_retry(raw);                                          // Read raw frame, (out[5]) → bool ok
    printf("retry_ok=%d raw[0]=0x%02X\n", ok, raw[0]);
    return 0;
}
