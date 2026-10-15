// IL0373 complete example: Waveshare 2.13inch e-Paper (D) on a Raspberry Pi (Linux GCC).
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
// The read_* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO (GPIO9).
#include <cstdio>
#include <cstdlib>
#include "DisplayConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
#include "IL0373.h"

static uint8_t frameBuffer[2756];

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";


    OutputPinLinux dc(chip_path, 25);                                                  // Create D/C# pin, (chip_path, offset=25)
    OutputPinLinux res(chip_path, 17, true);                                           // Create RES# pin, (chip_path, offset=17, initial_high=false)
    InputPinLinux  busy(chip_path, 24);                                                // Create BUSY pin, (chip_path, offset=24)
    DisplayConnectionLinux connection(0, 0, &dc, &res, &busy, DisplayWireMode::kHalfDuplexPin, 1000000); // Create display connection, (bus=0, device=0, dc, res, busy, wireMode=kSimplex, max_speed_hz=4000000)
    IL0373Full<DisplayConnectionLinux> epd(connection, frameBuffer);          // Create IL0373 driver, (connection, buffer, width=104, height=212, busy_timeout_ms=30000)
    if (!epd.valid()) {
        std::printf("IL0373 init failed: BUSY never went high\n");
        return 1;
    }
    typedef IL0373Full<DisplayConnectionLinux> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
                                                                                          // every pixel set to white
    epd.set_pixel(5, 5, Epd::BLACK);                                                   // Set pixel, (x, y, color=BLACK)
                                                                                          // clears the bit for that pixel
    epd.draw_rect(20, 20, 60, 40, Epd::BLACK, true);                                     // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
                                                                                          // filled black block
    epd.draw_line(0, 0, 103, 211, Epd::BLACK);                                         // Draw line, (x0, y0, x1, y1, color=BLACK)
                                                                                          // Bresenham diagonal
    static const uint8_t box[4] = {0xFF, 0x81, 0x81, 0xFF};
    epd.blit(70, 20, box, 8, 4);                                                      // Copy 1-bpp bitmap, (x, y, data, w, h, color=BLACK)
                                                                                          // 8 x 4 box, MSB first
    uint8_t colour = epd.get_pixel(30, 30);                                            // Read framebuffer pixel, (x, y) → color
                                                                                          // BLACK here
    epd.set_flip(false, false);                                                        // Mirror output, (horizontal, vertical)
                                                                                          // PSR.SHL / UD at power-on values
    epd.set_power(14.0f, 14.0f, 20.0f);                                        // Set source/gate power, (vdh V, vdl V, vgh=20 V)
                                                                                          // PWR: +-14 V sources, +-20 V gates (the power-on values)
    epd.set_frame_rate(50);                                                            // Set frame rate, (hz 4–200)
                                                                                          // nearest PLL M/N entry (M7 N4)
    epd.set_soft_start(0x17, 0x17, 0x17);                                              // Set booster soft start, (a, b, c)
                                                                                          // vendor BTST phases
    epd.set_interval(7);                                                               // Set VCOM/data interval, (cdi 0–15, border=2, polarity=1)
                                                                                          // keeps DDX = 01 so the framebuffer mapping holds
    epd.set_temperature_sensor(false, 0);                                              // Select temperature sensor, (external, offset -8..7 °C)
                                                                                          // internal sensor, no offset
    uint8_t status = 0;
    int8_t temperature = 0;
    uint8_t revision = 0;
    bool haveStatus = epd.read_status(&status);                                        // Read FLG, (status*) → bool
                                                                                          // needs DOUT wired (half-duplex)
    bool haveTemp = epd.read_temperature(&temperature);                                // Read sensor, (celsius*) → bool
                                                                                          // signed internal temperature
    bool haveRev = epd.read_revision(&revision);                                        // Read REV, (revision*) → bool
                                                                                          // LUT_REV, one byte
    epd.show();                                                                        // Update display, () → bool
                                                                                          // OLD/NEW planes, DRF, border floating, POF
    epd.draw_rect(16, 100, 64, 40, Epd::BLACK, true);                                  // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    epd.show_region(16, 100, 64, 40);                                                  // Refresh a window, (x, y, w, h) → bool
                                                                                          // sends only the window's rows (RAM saving)
    bool ready = epd.wait_busy(1000);                                                  // Wait for BUSY high, (timeout_ms) → bool
                                                                                          // true when the controller is idle
    const uint8_t cdi = 0x97;
    epd.send_command(0x50, &cdi, 1);                                                   // Send raw command, (command, data*, len)
                                                                                          // rewrites CDI with the initialisation value
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    epd.wake();                                                                        // Reset and re-initialise, () → bool
                                                                                          // framebuffer is kept
    std::printf("colour=%u status=%s%02X temperature=%s%d revision=%02X ready=%d\n", (unsigned)colour,
                haveStatus ? "0x" : "n/a ", status, haveTemp ? "" : "n/a ", temperature, revision, ready);
    (void)haveRev;
    std::printf("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
