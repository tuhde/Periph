// DHT11 hardware test — Linux (libgpiod v2).
// Wiring: DATA on GPIO_LINE of GPIO_CHIP (defaults /dev/gpiochip0, line 4)
// with a 4.7 kΩ pull-up to 3V3. Decoding is covered by dht11_test_unit.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "DHTxxConnectionLinux.h"
#include "DHT11.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    const char* chip_path = getenv("GPIO_CHIP") ? getenv("GPIO_CHIP") : "/dev/gpiochip0";
    unsigned line_num = getenv("GPIO_LINE") ? (unsigned)atoi(getenv("GPIO_LINE")) : 4;
    DHTxxConnectionLinux connection(chip_path, line_num);

    // The sensor needs ~1 s after power-up and >= 1 s between reads.
    usleep(1500000);
    DHT11Minimal<DHTxxConnectionLinux> dht_min(connection);
    float temperature = NAN, humidity = NAN;
    bool ok = false;
    for (int attempt = 0; attempt < 3 && !ok; ++attempt) {
        ok = dht_min.read(temperature, humidity);
        if (!ok) usleep(2000000);
    }
    check_true(ok, "read_ok");
    check_true(ok && temperature >= 0.0f && temperature <= 50.0f, "temperature_in_range");
    check_true(ok && humidity >= 20.0f && humidity <= 90.0f, "humidity_in_range");

    usleep(2000000);
    DHT11Full<DHTxxConnectionLinux> dht_full(connection, 3);
    uint8_t raw[5];
    ok = dht_full.read_raw(raw);
    check_true(ok && (uint8_t)(raw[0] + raw[1] + raw[2] + raw[3]) == raw[4], "read_raw_checksum");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
