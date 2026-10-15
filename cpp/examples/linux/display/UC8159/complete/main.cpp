// UC8159 complete example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi (Linux GCC).
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Each refresh blocks for about 30 s.
// The read_* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO (GPIO9).
#include <cstdio>
#include <cstdlib>
#include "DisplayConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
#include "UC8159.h"
#include <time.h>
#define PRINT std::printf
static inline uint32_t NOW_MS() { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (uint32_t)(ts.tv_sec * 1000u + ts.tv_nsec / 1000000u); }

static uint8_t frameBuffer[100801];

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";


    OutputPinLinux dc(chip_path, 25);                                                  // Create D/C# pin, (chip_path, offset=25)
    OutputPinLinux res(chip_path, 17, true);                                           // Create RES# pin, (chip_path, offset=17, initial_high=false)
    InputPinLinux  busy(chip_path, 24);                                                // Create BUSY pin, (chip_path, offset=24)
    DisplayConnectionLinux connection(0, 0, &dc, &res, &busy, DisplayWireMode::kHalfDuplexPin, 1000000); // Create display connection, (bus=0, device=0, dc, res, busy, wireMode=kSimplex, max_speed_hz=4000000)
    UC8159Full<DisplayConnectionLinux> epd(connection, frameBuffer, sizeof(frameBuffer));          // Create UC8159 driver, (connection, buffer, buffer_len, busy_timeout_ms=60000)
    if (!epd.valid()) {
        std::printf("UC8159 init failed: BUSY never went high\n");
        return 1;
    }
    typedef UC8159Full<DisplayConnectionLinux> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
                                                                                          // every pixel set to white
    epd.set_pixel(5, 5, Epd::BLACK);                                                   // Set pixel, (x, y, color=BLACK)
                                                                                          // colour code 0..7
    epd.draw_rect(20, 20, 200, 120, Epd::RED, true);                                   // Draw rectangle, (x, y, w, h, color, fill=false)
                                                                                          // filled red block
    epd.draw_rect(240, 20, 200, 120, Epd::BLUE, false);                                // Draw rectangle, (x, y, w, h, color, fill=false)
                                                                                          // blue outline
    epd.draw_line(0, 0, 599, 447, Epd::GREEN);                                         // Draw line, (x0, y0, x1, y1, color)
                                                                                          // Bresenham diagonal
    static const uint8_t box[4] = {0xFF, 0x81, 0x81, 0xFF};
    epd.blit(460, 20, box, 8, 4, Epd::ORANGE);                                         // Copy 1-bpp bitmap, (x, y, data, w, h, color)
                                                                                          // 8 x 4 box, MSB first
    uint8_t colour = epd.get_pixel(30, 30);                                            // Read framebuffer pixel, (x, y) → color 0..7
                                                                                          // RED here
    epd.set_flip(false, false);                                                        // Mirror output, (horizontal, vertical)
                                                                                          // PSR.SHL / UD at power-on values
    epd.set_frame_rate(0x3C);                                                          // Set PLL frame rate code, (code)
                                                                                          // 50 Hz, the vendor value
    epd.set_interval(7, Epd::WHITE, 1);                                                // Set VCOM/data interval, (cdi 0–15, border=WHITE, polarity=1)
                                                                                          // white border, 10 hsync
    epd.set_temperature_sensor(false, 0);                                              // Select temperature sensor, (external, offset -8..7 °C)
                                                                                          // internal sensor, no offset
    uint8_t status = 0;
    int8_t temperature = 0;
    bool haveStatus = epd.read_status(&status);                                        // Read FLG, (status*) → bool
                                                                                          // needs DOUT wired (half-duplex)
    bool haveTemp = epd.read_temperature(&temperature);                                // Read sensor, (celsius*) → bool
                                                                                          // signed internal temperature
    epd.show();                                                                        // Update display, () → bool
                                                                                          // DTM1, PON, DRF, POF; takes about 30 s
    bool ready = epd.wait_busy(1000);                                                  // Wait for BUSY high, (timeout_ms) → bool
                                                                                          // true when the controller is idle
    const uint8_t cdi = 0x37;
    epd.send_command(0x50, &cdi, 1);                                                   // Send raw command, (command, data*, len)
                                                                                          // rewrites CDI with the vendor value
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    epd.wake();                                                                        // Reset and re-initialise, () → bool
                                                                                          // framebuffer is kept
    PRINT("colour=%u status=%s%02X temperature=%s%d ready=%d\n", (unsigned)colour,
          haveStatus ? "0x" : "n/a ", status, haveTemp ? "" : "n/a ", temperature, ready);
    return 0;
}
