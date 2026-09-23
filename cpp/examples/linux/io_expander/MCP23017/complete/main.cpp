#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP23017.h"

static void on_port_a(uint8_t status) {
    printf("port A interrupt, INTF=0x%02X\n", status);
}

static void on_pin(MCP23017Full::IOExpanderPin*) {
    printf("pin changed\n");
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CConnectionLinux connection(bus, addr);

    MCP23017Full mcp(connection);                                           // Create MCP23017 driver, (connection, addr=0x20)

    for (uint8_t n = 0; n < 8; n++)
        mcp.pin(n).mode(INPUT);                                            // Set pin direction, (m INPUT/OUTPUT/INPUT_PULLUP) → void
                                                                           // port A = inputs
    for (uint8_t n = 8; n < 16; n++)
        mcp.pin(n).mode(OUTPUT);                                           // port B = outputs
    mcp.configure_pullup(0, 0xFF);                                         // Enable 100 kΩ pull-ups, (port 0/1, mask) → void
                                                                           // same effect as INPUT_PULLUP per pin
    mcp.configure_polarity(0, 0x01);                                       // Invert input polarity, (port 0/1, mask) → void
                                                                           // GPA0 now reads 1 when the button pulls it low

    printf("GPIOA=0x%02X\n", mcp.read_port(0));                          // Read port A, (port 0/1) → uint8_t bitmask
    mcp.write_port(1, 0xAA);                                               // Write port B, (port 0/1, mask) → void

    auto led = mcp.pin(11);                                                // Pin proxy, (n 0–15) → IOExpanderPin
                                                                           // pin 11 = GPB3
    led.high();                                                            // Drive pin high, () → void
    led.toggle();                                                          // Invert pin, () → void
    led.write(HIGH);                                                       // Set pin level, (v HIGH/LOW) → void
    printf("GPB3=%u GPA0=%u\n", led.read(), mcp.pin(0).read());           // Read pin level, () → uint8_t HIGH/LOW
    led.low();                                                             // Drive pin low, () → void

    mcp.set_default_value(0, 0x00);                                        // Set DEFVAL compare value, (port 0/1, mask) → void
                                                                           // used when interrupt-on-change compares against DEFVAL
    mcp.onInterrupt(0, on_port_a);                                         // Subscribe to port A interrupts, (port, callback, intPin=nullptr) → void
                                                                           // needs INTA wired to an InputPin on the connection
    uint8_t flags = mcp.pollInterrupt(0);                                  // Read and clear INTF, (port 0/1) → uint8_t
                                                                           // bit set per pin that caused the interrupt
    uint8_t captured = mcp.read_capture(0);                                // Read INTCAP, (port 0/1) → uint8_t
                                                                           // port state latched at interrupt time
    printf("INTF=0x%02X INTCAP=0x%02X\n", flags, captured);
    mcp.offInterrupt(0);                                                   // Unsubscribe port A, (port) → void

    auto button = mcp.pin(1);
    button.watch(on_pin);                                                  // Per-pin change callback, (handler, trigger=kChange) → void
    button.unwatch();                                                      // Remove the per-pin callback, () → void
    mcp.offInterrupt();                                                    // Unsubscribe all ports, () → void
    return 0;
}
