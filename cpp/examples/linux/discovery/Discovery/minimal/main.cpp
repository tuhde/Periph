#include <cstdio>
#include <cstdlib>
#include "Discovery.h"

int main() {
    const char* bus_env = getenv("I2C_BUS");
    int bus = bus_env ? atoi(bus_env) : 1;

    for (const auto& d : periph::discovery::discover(bus)) {                 // Discover chips, (bus=1, active=false) → vector<DiscoveredDevice>
        if (!d.identified.empty()) printf("0x%02X %s\n", d.address, d.identified.c_str());
        else printf("0x%02X (%zu candidates)\n", d.address, d.candidates.size());
    }
    return 0;
}
