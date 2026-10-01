#include <cstdio>
#include <cstdlib>
#include "Discovery.h"

using namespace periph::discovery;

int main() {
    const char* bus_env = getenv("I2C_BUS");
    int bus = bus_env ? atoi(bus_env) : 1;

    // --- Take inventory of an unknown bench setup ---
    // Scan /dev/i2c-1 and name everything that answers. A chip is only named when
    // its identity register matches exactly one registry entry; shared addresses
    // without an ID register stay as candidate lists.
    std::vector<DiscoveredDevice> devices = discover(bus);                   // Discover chips, (bus=1, active=false) → vector<DiscoveredDevice>

    // --- Report what was found ---
    // identified: confirmed chip; candidates: could be any of these; empty means the
    // address answers but the registry does not know it.
    int skipped = 0;
    for (const auto& d : devices) {
        if (!d.identified.empty()) {
            printf("0x%02X  %s (driver: %s)\n", d.address, d.identified.c_str(), d.driver.c_str());
        } else if (d.inUseByKernel) {
            printf("0x%02X  claimed by a kernel driver, %zu candidate(s)\n", d.address, d.candidates.size());
        } else if (!d.candidates.empty()) {
            printf("0x%02X  one of %zu candidates, first: %s\n", d.address, d.candidates.size(), d.candidates[0].c_str());
        } else {
            printf("0x%02X  unknown device\n", d.address);
        }
        if (d.probeSkipped == ProbeSkipReason::WriteSensitiveCandidate) skipped++;
    }

    // --- Flag addresses we deliberately did not probe ---
    // Some candidates (port expanders, DACs) treat a stray write as data, so the
    // identity probe is skipped unless discover(bus, true) is used.
    printf("%d address(es) not probed because a candidate is write-sensitive\n", skipped);
    return 0;
}
