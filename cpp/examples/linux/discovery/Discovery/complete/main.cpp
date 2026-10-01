#include <cstdio>
#include <cstdlib>
#include "Discovery.h"

using namespace periph::discovery;

int main() {
    const char* bus_env = getenv("I2C_BUS");
    int bus = bus_env ? atoi(bus_env) : 1;

    std::vector<uint8_t> addresses = scan(bus);                              // Scan bus, (bus=1) → vector<uint8_t> 7-bit addresses
                                                                             // quick write per address, read byte on 0x30-0x37 / 0x50-0x5F; EBUSY counts as present
    for (uint8_t a : addresses) printf("0x%02X\n", a);

    BusLinux i2c(bus);                                                       // Open bus, (bus=1)
                                                                             // opens /dev/i2c-N once so several scans share one file descriptor
    std::map<uint8_t, bool> detail = scanDetailed(i2c, kFirstAddress, kLastAddress); // Scan with kernel-binding info, (bus, first=0x08, last=0x77) → map<uint8_t, bool>
                                                                             // value is true when a kernel driver owns the address (i2cdetect "UU")
    for (const auto& kv : detail) printf("0x%02X in_use=%d\n", kv.first, kv.second);

    std::vector<DiscoveredDevice> devices = discover(i2c, false);            // Discover chips, (bus, active=false) → vector<DiscoveredDevice>
                                                                             // identity reads confirm a chip only when exactly one candidate matches
    for (const auto& d : devices) {
        printf("0x%02X identified='%s' driver='%s' kernel=%d skipped=%d candidates=%zu aliases=%zu\n", d.address,
               d.identified.c_str(), d.driver.c_str(), d.inUseByKernel, (int)d.probeSkipped, d.candidates.size(),
               d.aliases.size());
    }

    devices = discover(i2c, true);                                           // Discover chips incl. write-sensitive addresses, (bus, active=true) → vector<DiscoveredDevice>
                                                                             // also probes addresses shared with PCF8574/PCF8591/MCP4725-style chips; may change their outputs
    printf("%zu\n", devices.size());
    return 0;
}
