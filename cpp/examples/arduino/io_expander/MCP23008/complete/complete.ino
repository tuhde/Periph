#include <Wire.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x20);
MCP23008Full chip(connection);                                // Create MCP23008 driver, (connection, addr=0x20)

static MCP23008Full* g_chip = nullptr;

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint8_t status) {
    { char b[64]; snprintf(b, sizeof b, "interrupt, INTF=0x%02X\n", status); Serial.print(b); }
}
static void onPin(MCP23008Full::IOExpanderPin*) {
    { char b[64]; snprintf(b, sizeof b, "GP4 falling\n"); Serial.print(b); }
}

void setup() {
    Serial.begin(115200);
    Wire.begin();

    g_chip = &chip;

    chip.configure_pullup(0xF0);                              // Enable pull-ups on GP4–GP7, (mask=0xF0) → void
                                                              // writes GPPU; only effective on inputs
    chip.configure_polarity(0x00);                            // Normal input polarity, (mask=0x00) → void
                                                              // writes IPOL; 1 bits invert the GPIO read
    chip.set_default_value(0x00);                             // Set DEFVAL compare value, (mask=0x00) → void
                                                              // reference for default-compare interrupts
    chip.configure_int_pin(false, false);                     // Configure INT output, (openDrain=false, activeHigh=false) → void
                                                              // push-pull, active-low (IOCON ODR/INTPOL)
    chip.set_sequential(true);                                // Keep auto-increment on, (enabled) → void
                                                              // IOCON SEQOP = 0 (driver default)

    for (uint8_t n = 0; n < 4; n++) {
        auto p = chip.pin(n);                                 // Get LED pin proxy, (n) → IOExpanderPin
                                                              // Full proxy with pull-up and watch support
        p.mode(OUTPUT);                                       // Set pin direction, (m INPUT/OUTPUT/INPUT_PULLUP) → void
                                                              // latches OLAT, then clears the IODIR bit
        p.high();                                             // Drive pin high, () → void
                                                              // sets the OLAT bit via the shadow
        p.toggle();                                           // Invert the output latch, () → void
                                                              // one OLAT write, no read-modify-write
    }

    chip.write_port(0, 0x05);                                 // Write the output latch, (port=0, mask) → void
                                                              // whole-port OLAT write, updates the shadow
    uint8_t port_val = chip.read_port();                      // Read all 8 pins, (port=0) → uint8_t bitmask
                                                              // reads GPIO (actual pin levels)
    { char b[64]; snprintf(b, sizeof b, "GPIO=0x%02X\n", port_val); Serial.print(b); }
    uint8_t iodir = chip.read_register(0x00);                 // Read raw register, (reg) → uint8_t
                                                              // escape hatch for debugging
    { char b[64]; snprintf(b, sizeof b, "IODIR=0x%02X\n", iodir); Serial.print(b); }
    chip.write_register(0x02, 0x10);                          // Write raw register, (reg, value) → void
                                                              // GPINTEN: arm GP4

    chip.enable_pin_interrupt(0xF0);                          // Arm GP4–GP7, (mask) → void
                                                              // writes GPINTEN
    chip.onInterrupt(onChange, nullptr, MCP23x08Full::INT_VS_DEFAULT);// Subscribe to INT, (callback, intPin=nullptr, mode) → bool
                                                              // uses connection.intPin() if wired; otherwise call service()
    chip.pin(4).watch(onPin, InputPin::kFalling);             // Watch GP4 falling edges, (handler, trigger) → bool
                                                              // arms GP4 in GPINTEN, filters the edge in software
    chip.service();                                           // Poll once and dispatch callbacks, () → void
                                                              // reads INTF/INTCAP; use when no INT pin is wired
    uint8_t flags = chip.pollInterrupt();                     // Read INTF and clear, () → uint8_t
                                                              // INTCAP is read and discarded
    uint8_t cap = chip.read_capture();                        // Read captured port state, () → uint8_t
                                                              // port snapshot at the interrupt
    { char b[64]; snprintf(b, sizeof b, "INTF=0x%02X INTCAP=0x%02X\n", flags, cap); Serial.print(b); }
    chip.pin(4).unwatch();                                    // Stop watching GP4, () → void
                                                              // clears its GPINTEN bit
    chip.offInterrupt();                                      // Unsubscribe, () → void
                                                              // stops delivery
    chip.reset();                                             // Restore reset values, () → void
                                                              // rewrites all registers, clears shadows
}

void loop() {}
