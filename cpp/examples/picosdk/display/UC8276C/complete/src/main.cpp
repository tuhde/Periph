// UC8276C complete example: Waveshare 4.2inch e-Paper (B), Raspberry Pi Pico SDK (Pico / Pico W).
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18, CS=GP17, DC=GP20, RST=GP21, BUSY=GP22, DOUT=GP16 (MISO)
// Each refresh blocks for about 17 s. The read_* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO.
#include <stdio.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "DisplayConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "UC8276C.h"

#define PRINT printf
#define NOW_MS() to_ms_since_boot(get_absolute_time())

static uint8_t bwBuffer[15000];
static uint8_t redBuffer[15000];

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 1000 * 1000);
    gpio_set_function(18, GPIO_FUNC_SPI);   // SCK
    gpio_set_function(19, GPIO_FUNC_SPI);   // MOSI
    gpio_set_function(16, GPIO_FUNC_SPI);   // MISO (DOUT through the resistor)

    OutputPinPicoSDK cs(17);                  // Create CS# pin, (gpio)
    OutputPinPicoSDK dc(20);                  // Create D/C# pin, (gpio)
    OutputPinPicoSDK res(21);                 // Create RES# pin, (gpio)
    InputPinPicoSDK  busy(22);                // Create BUSY_N pin, (gpio)

    DisplayConnectionPicoSDK connection(spi0, &cs, &dc, &res, &busy, DisplayWireMode::kHalfDuplexPin);   // Create display connection, (spi, cs, dc, res, busy, wireMode=kSimplex)
    UC8276CFull<DisplayConnectionPicoSDK> epd(connection, bwBuffer, redBuffer);   // Create UC8276C driver, (connection, bw, red, width=400, height=300, busy_timeout_ms=30000)
    if (!epd.valid()) {
        PRINT("UC8276C init failed: BUSY_N never went high\n");
        return 1;
    }
    typedef UC8276CFull<DisplayConnectionPicoSDK> Epd;
    epd.clear(Epd::WHITE);                                                             // Fill framebuffer, (color=WHITE)
                                                                                          // both planes set to white
    epd.set_pixel(5, 5, Epd::BLACK);                                                   // Set pixel, (x, y, color=BLACK)
                                                                                          // clears the B/W bit for that pixel
    epd.draw_rect(20, 20, 100, 60, Epd::RED, true);                                    // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
                                                                                          // filled red block
    epd.draw_line(0, 0, 399, 299, Epd::BLACK);                                         // Draw line, (x0, y0, x1, y1, color=BLACK)
                                                                                          // Bresenham diagonal
    static const uint8_t box[4] = {0xFF, 0x81, 0x81, 0xFF};
    epd.blit(150, 20, box, 8, 4);                                                      // Copy 1-bpp bitmap, (x, y, data, w, h, color=BLACK)
                                                                                          // 8 x 4 box, MSB first
    uint8_t colour = epd.get_pixel(30, 30);                                            // Read framebuffer pixel, (x, y) → color
                                                                                          // RED here
    epd.set_mode(Epd::MODE_KWR);                                                       // Select pixel mode, (mode)
                                                                                          // PSR.KW/R cleared: black/white/red
    epd.set_flip(false, false);                                                        // Mirror output, (horizontal, vertical)
                                                                                          // PSR.SHL / UD at power-on values
    epd.set_power(10.0f, 10.0f, 10.0f, 16.0f);                                         // Set source/gate power, (vdh V, vdl V, vdhr V, vgh=16 V)
                                                                                          // panel-layout PWR: +-10 V sources, +-16 V gates
    epd.set_frame_rate(50);                                                            // Set frame rate, (hz 4–200)
                                                                                          // nearest PLL M/N entry (M7 N4)
    epd.set_soft_start(0x17, 0x17, 0x17);                                              // Set booster soft start, (a, b, c)
                                                                                          // vendor BTST phases
    epd.set_interval(7);                                                               // Set VCOM/data interval, (cdi 0–15, border=3, polarity=1)
                                                                                          // keeps DDX = 01 so the framebuffer mapping holds
    epd.set_temperature_sensor(false, 0);                                              // Select temperature sensor, (external, offset -8..7 °C)
                                                                                          // internal sensor, no offset
    uint8_t status = 0;
    int8_t temperature = 0;
    uint8_t revision[1] = {0};
    bool haveStatus = epd.read_status(&status);                                        // Read FLG, (status*) → bool
                                                                                          // needs DOUT wired (half-duplex)
    bool haveTemp = epd.read_temperature(&temperature);                                // Read sensor, (celsius*) → bool
                                                                                          // signed internal temperature
    bool haveRev = epd.read_revision(revision, 1);                                     // Read REV, (buf*, len) → bool
                                                                                          // 1 byte on the panel datasheet
    epd.show();                                                                        // Update display, () → bool
                                                                                          // DTM1, DTM2, PON, DRF, POF; takes ~17 s
    epd.draw_rect(40, 100, 80, 40, Epd::BLACK, true);                                  // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    epd.show_region(40, 100, 80, 40);                                                  // Refresh a window, (x, y, w, h) → bool
                                                                                          // sends only the window's rows (RAM saving)
    bool ready = epd.wait_busy(1000);                                                  // Wait for BUSY_N high, (timeout_ms) → bool
                                                                                          // true when the controller is idle
    const uint8_t cdi = 0xD7;
    epd.send_command(0x50, &cdi, 1);                                                   // Send raw command, (command, data*, len)
                                                                                          // rewrites CDI with its default
    epd.sleep();                                                                       // Enter deep sleep, () → bool
    epd.wake();                                                                        // Reset and re-initialise, () → bool
                                                                                          // framebuffer is kept
    PRINT("colour=%u status=%s%02X temperature=%s%d revision=%02X ready=%d\n", (unsigned)colour,
                haveStatus ? "0x" : "n/a ", status, haveTemp ? "" : "n/a ", temperature, revision[0], ready);
    (void)haveRev;
    PRINT("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
