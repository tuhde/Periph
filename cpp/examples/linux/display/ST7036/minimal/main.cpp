// ST7036 minimal example: EA DOGM162W-A (2 x 16), Raspberry Pi (Linux GCC), 3.3 V module
// Wiring: SI=GPIO10 (SPI0 MOSI), SCL=GPIO11, CSB=GPIO8 (CE0), RS=GPIO25, RESET=GPIO17; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Minimal use: two lines of text.
#include <cstdio>
#include <cstdlib>
#include "DisplayConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
#include "ST7036.h"

#include <unistd.h>
#define PRINT std::printf
#define DELAY_MS(ms) usleep((ms) * 1000u)

static void run(ST7036Minimal<DisplayConnectionLinux>& lcd) {
    lcd.print_at(0, 0, "Hello, Periph!");              // Write text, (col, row, text)
    lcd.print_at(0, 1, "ST7036 DOGM162");              // Write text, (col, row, text)
    PRINT("done\n");
}

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";


    OutputPinLinux dc(chip_path, 25);                                                  // Create D/C# pin, (chip_path, offset=25)
    OutputPinLinux res(chip_path, 17, true);                                           // Create RES# pin, (chip_path, offset=17, initial_high=false)
    DisplayConnectionLinux connection(0, 0, &dc, &res, nullptr, DisplayWireMode::kSimplex, 1000000);                         // Create display connection, (bus=0, device=0, dc, res, busy, wireMode=kSimplex, max_speed_hz=1000000)
    ST7036Minimal<DisplayConnectionLinux> lcd(connection);  // Create ST7036 driver, (connection)
    if (!lcd.valid()) {
        PRINT("ST7036 init failed (check wiring)\n");
        return 1;
    }
    run(lcd);
    return 0;
}
