#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Rrh46410.h"

static void print_result(const RRH46410Result& r) {
    unsigned long tvoc = (unsigned long)(r.tvoc_mg_m3 * 1000.0f + 0.5f);
    unsigned long etoh = (unsigned long)(r.etoh_ppm * 1000.0f + 0.5f);
    unsigned iaq = (unsigned)(r.iaq * 10.0f + 0.5f);
    printf("sample %u  IAQ %u.%u  TVOC %lu.%03lu mg/m3  EtOH %lu.%03lu ppm  eCO2 %u ppm  rel %u  %s\n",
          (unsigned)r.sample, iaq / 10, iaq % 10, tvoc / 1000, tvoc % 1000, etoh / 1000, etoh % 1000,
          (unsigned)r.eco2_ppm, (unsigned)r.relative_iaq, r.warmed_up ? "ok" : "warming up");
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x38;
    I2CConnectionLinux connection(bus, addr);

    RRH46410Minimal sensor(connection);                            // Create RRH46410 driver, (connection) → void
    sensor.init();                                                 // Check product ID, start IAQ 2nd Gen mode, () → bool
    while (true) {
        RRH46410Result r;
        if (sensor.read(r)) print_result(r);                       // Wait for the next sample, (result&) → bool
        else printf("read failed (error %d)\n", sensor.last_error());
    }
    return 0;
}
