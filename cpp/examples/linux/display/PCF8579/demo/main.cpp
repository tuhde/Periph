#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8579.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x3C;
    I2CConnectionLinux connection(bus, addr);

    // --- Initialise a 16 x 40 column driver ---
    // 1:16 multiplex gives 16 rows and 40 columns; the constructor clears the RAM
    // before switching the display on so no random start-up pattern is shown.
    PCF8579Full lcd(connection, 0, 16);                       // Create PCF8579 driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false)

    // --- Draw a border and both diagonals ---
    // Lines and rectangles are rendered in the shadow buffer and sent as one
    // batch of changed bytes per run of columns.
    lcd.draw_rect(0, 0, 40, 16);                              // Draw rectangle outline, (x, y, w, h, on=true, fill=false) → void
    lcd.draw_line(0, 0, 39, 15);                              // Draw line, (x0, y0, x1, y1, on=true) → void
    lcd.draw_line(0, 15, 39, 0);                              // Draw line, (x0, y0, x1, y1, on=true) → void

    // --- Flash the display twice with inverse video ---
    // Inverse video only changes how the RAM is shown, so the picture is kept.
    for (int i = 0; i < 2; i++) {
        lcd.set_display(PCF8579Full::DISPLAY_INVERSE);        // Set display status, (status 0–3) → bool
        usleep((400) * 1000);
        lcd.set_display(PCF8579Full::DISPLAY_NORMAL);         // Set display status, (status 0–3) → bool
        usleep((400) * 1000);
    }

    // --- Fill all four RAM banks with distinct patterns ---
    // At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
    // buffers that the start bank can scroll into view.
    static const uint8_t patterns[4] = {0x55, 0x33, 0x0F, 0xFF};
    for (int bank = 0; bank < 4; bank++) {
        uint8_t col[40];
        for (int i = 0; i < 40; i++) col[i] = patterns[bank];
        lcd.write_ram(0, bank, col, sizeof(col));             // Write raw RAM, (x 0–39, bank 0–3, data, len, mode=0) → bool
    }

    // --- Scroll through the banks with the hardware start bank ---
    // Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
    for (int bank = 0; bank < 4; bank++) {
        lcd.set_start_bank(bank);                             // Set start bank, (bank 0–3) → bool
        uint8_t first = 0;
        lcd.read_ram(0, bank, &first, 1);                     // Read raw RAM, (x 0–39, bank 0–3, buf, n, mode=0) → bool
        printf("first column byte 0x%02X\n", (unsigned)(first));
        usleep((1000) * 1000);
    }

    // --- Read back the first 8 RAM columns of bank 0 ---
    // Writing a counting pattern and reading it back verifies the bus path.
    lcd.set_start_bank(0);                                    // Set start bank, (bank 0–3) → bool
    static const uint8_t scratch[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    lcd.write_ram(0, 0, scratch, sizeof(scratch));            // Write raw RAM, (x 0–39, bank 0–3, data, len, mode=0) → bool
    uint8_t back[8];
    lcd.read_ram(0, 0, back, sizeof(back));                   // Read raw RAM, (x 0–39, bank 0–3, buf, n, mode=0) → bool
    for (int i = 0; i < 8; i++) {
        printf("scratch byte 0x%02X\n", (unsigned)(back[i]));
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
