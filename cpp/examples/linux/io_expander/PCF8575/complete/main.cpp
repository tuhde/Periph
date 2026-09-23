#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8575.h"

static void on_change(uint16_t changed) {
    printf("inputs changed: 0x%X\n", (unsigned)changed);
}

static void on_pin(PCF8575Full::IOExpanderPin*) {
    printf("pin changed\n");
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CConnectionLinux connection(bus, addr);

    PCF8575Full pcf(connection);                                            // Create PCF8575 driver, (connection)

    printf("port0=0x%02X port1=0x%02X\n", pcf.read_port(0), pcf.read_port(1));  // Read port, (port) → uint8_t bitmask
                                                                           // a pin reads its external level only while its latch is high
    pcf.write_port(0, 0xAA);                                               // Write port, (port 0/1, mask) → void
    pcf.write_port(1, 0x55);

    auto out = pcf.pin(15);                                              // Pin proxy, (n 0–15) → IOExpanderPin
    out.mode(OUTPUT);                                                      // Set pin mode, (m INPUT/OUTPUT) → void
                                                                           // INPUT just writes the latch high (weak pull-up)
    out.high();                                                            // Drive pin high, () → void
    out.low();                                                             // Drive pin low, () → void
    out.toggle();                                                          // Invert pin, () → void
    out.write(LOW);                                                        // Set pin level, (v HIGH/LOW) → void
    auto in = pcf.pin(0);
    in.mode(INPUT);
    printf("P0=%u P15=%u\n", in.read(), out.read());                   // Read pin level, () → uint8_t HIGH/LOW

    pcf.onInterrupt(on_change);                                            // Subscribe to INT, (callback) → void
                                                                           // INT pulses low on any input change; needs an InputPin on the connection
    uint16_t changed = pcf.pollInterrupt();  // Read port and diff against last state, () → uint16_t bitmask
                                                                           // bit set per pin that changed since the previous poll
    printf("changed=0x%X\n", (unsigned)changed);
    pcf.offInterrupt();                                                    // Unsubscribe, () → void

    in.watch(on_pin);  // Per-pin change callback, (handler, trigger=kChange) → void
    in.unwatch();                                                          // Remove the per-pin callback, () → void
    return 0;
}
