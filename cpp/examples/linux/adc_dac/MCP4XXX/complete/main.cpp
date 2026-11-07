#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP4XXX.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x28;
    I2CConnectionLinux connection(bus, addr);

    MCP4XXXFull pot(connection, MCP4XXXPart::MCP4661, 10000.0f, 75.0f); // Create MCP4XXX driver, (connection, part, r_ab=10000 Ω, r_wiper=75 Ω, hvc=nullptr, hvc_settle_ms=1)
    pot.init();                                              // Bus check, () → void


    // --- Part properties ---
    printf("%d\n", (int)(pot.channels()));                   // Number of networks, () → uint8_t 1-2
                                                             // property of the selected part
    printf("%d\n", (int)(pot.steps()));                      // Number of wiper positions, () → uint16_t 129 or 257
                                                             // max code + 1
    printf("%s\n", (pot.is_potentiometer()) ? "true" : "false"); // Terminal A on a pin, () → bool
                                                             // true for the X1 parts
    printf("%s\n", (pot.is_nonvolatile()) ? "true" : "false"); // Has EEPROM, () → bool
                                                             // true for 454X / 456X / 464X / 466X

    // --- Wiper position and resistance ---
    pot.set_wiper(100, 0);                                   // Set wiper, (code 0-256, channel=0) → void
                                                             // writes [(0 << 4) | D8, code & 0xFF]; code clamped
    printf("%d\n", (int)(pot.get_wiper(0)));                 // Read wiper, (channel=0) → uint16_t 0-256
                                                             // two-byte read of the wiper register
    pot.set_both(10, 20);                                    // Set both wipers, (code0, code1) → bool
                                                             // dual parts only; two transactions
    pot.set_resistance(2200.0f, 1);                          // Set W-B resistance, (ohms, channel=0) → void
                                                             // nearest code of R = N/max*RAB + RW, clamped
    printf("%.2f\n", (double)(pot.get_resistance(1)));       // Read W-B resistance, (channel=0) → float Ω
                                                             // N/max*RAB + RW
    printf("%.2f\n", (double)(pot.get_resistance_step()));   // Resistance of one step, () → float Ω
                                                             // RAB / max code

    // --- Ratio and W-A resistance ---
    pot.set_ratio(0.5f, 0);                                  // Set wiper ratio, (ratio 0.0-1.0, channel=0) → void
                                                             // code = round(ratio * max code)
    printf("%.2f\n", (double)(pot.get_ratio(0)));            // Read wiper ratio, (channel=0) → float 0.0-1.0
                                                             // code / max code
    pot.set_resistance_wa(3300.0f, 0);                       // Set W-A resistance, (ohms, channel=0) → bool
                                                             // potentiometer parts only
    printf("%.2f\n", (double)(pot.get_resistance_wa(0)));    // Read W-A resistance, (channel=0) → float Ω
                                                             // (max code - N)/max*RAB + RW

    // --- Increment / decrement ---
    printf("%d\n", (int)(pot.increment(8, 0)));              // Step wiper up, (steps=1, channel=0) → uint16_t
                                                             // continuous transaction of increment commands, saturating
    printf("%d\n", (int)(pot.decrement(4, 0)));              // Step wiper down, (steps=1, channel=0) → uint16_t
                                                             // same with decrement commands, saturating at 0

    // --- Shutdown and terminals ---
    pot.shutdown(1);                                         // Shut network down, (channel=0) → void
                                                             // TCON.RxHW = 0: A open, W tied to B; wiper kept
    printf("%s\n", (pot.is_shutdown(1)) ? "true" : "false"); // Read shutdown state, (channel=0) → bool
                                                             // from TCON
    pot.resume(1);                                           // Leave shutdown, (channel=0) → void
                                                             // TCON.RxHW = 1
    pot.set_terminals(true, false, true, 0);                 // Connect terminals, (a, w, b, channel=0) → void
                                                             // TCON.RxA / RxW / RxB, other bits preserved
    bool a, w, b;
    pot.get_terminals(0, a, w, b);                           // Read terminals, (channel, &a, &w, &b) → void
                                                             // from TCON
    printf("a=%s w=%s b=%s\n", (a) ? "true" : "false", (w) ? "true" : "false", (b) ? "true" : "false");
    pot.set_terminals(true, true, true, 0);                  // Reconnect all terminals, (a, w, b, channel=0) → void
                                                             // restores the wiper terminal
    printf("%d\n", (int)(pot.get_tcon()));                   // Read TCON, () → uint16_t 0-0x1FF
                                                             // raw terminal control register
    pot.set_general_call(true);                              // Set General Call, (enabled) → void
                                                             // TCON.GCEN; chip answers address 0x00 commands

    // --- Raw access and model ---
    printf("%d\n", (int)(pot.read_register(0x04)));          // Raw read, (address 0-15) → int 0-0x1FF or -1
                                                             // nine-bit value of any address the part has
    pot.write_register(0x04, 0x1FF);                         // Raw write, (address, value) → bool
                                                             // volatile wipers, TCON, EEPROM addresses on NV parts
    pot.set_rab(10000.0f, 75.0f);                            // Set resistance model, (r_ab Ω, r_wiper Ω; negative keeps RW) → void
                                                             // no I²C traffic; affects conversions only

    // --- Non-volatile parts (MCP454X / 456X / 464X / 466X) ---
    MCP4XXXStatus status;
    pot.read_status(status);                                 // Read STATUS, (&status) → bool
                                                             // EEPROM busy, WiperLock per network, write protect
    printf("busy=%s wp=%s\n", (status.eeprom_busy) ? "true" : "false", (status.write_protect) ? "true" : "false");
    pot.wait_ready(20);                                      // Wait for EEPROM write cycle, (timeout_ms=20 ms) → bool
                                                             // polls STATUS.EEWA
    printf("%d\n", (int)(pot.get_nv_wiper(0)));              // Read EEPROM wiper, (channel=0) → int or -1
                                                             // power-up value
    pot.set_nv_wiper(64, 0);                                 // Write EEPROM wiper, (code, channel=0) → bool
                                                             // volatile wiper unchanged; waits for the write cycle
    printf("%d\n", (int)(pot.store_wiper(0)));               // Store current wiper, (channel=0) → int or -1
                                                             // reads the volatile wiper, writes it to EEPROM
    printf("%d\n", (int)(pot.read_data(0)));                 // Read EEPROM register, (index 0-9) → int 0-0x1FF or -1
                                                             // general-purpose 9-bit register
    pot.write_data(0, 0x155);                                // Write EEPROM register, (index 0-9, value 0-0x1FF) → bool
                                                             // uses one of 1 000 000 write cycles
    printf("%s\n", (pot.is_wiper_locked(0)) ? "true" : "false"); // Read WiperLock, (channel=0) → bool
                                                             // STATUS.WLx
    printf("%s\n", (pot.is_write_protected()) ? "true" : "false"); // Read write protect, () → bool
                                                             // STATUS.WP
    pot.reset();                                             // Emulate power-on reset, () → void
                                                             // EEPROM wiper (or mid-scale) in every wiper, TCON 0x1FF
    return 0;
}
