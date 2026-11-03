// UC8159 complete example: Waveshare 5.65inch e-Paper (F), Raspberry Pi Pico SDK (Pico / Pico W).
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18, CS=GP17, DC=GP20, RST=GP21, BUSY=GP22, DOUT=GP16 (MISO)
// Each refresh blocks for about 30 s. The read_* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO.
#include <stdio.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "DisplayConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "UC8159.h"

#define PRINT printf
#define NOW_MS() to_ms_since_boot(get_absolute_time())

static uint8_t frameBuffer[100801];

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
    InputPinPicoSDK  busy(22);                // Create BUSY pin, (gpio)

    DisplayConnectionPicoSDK connection(spi0, &cs, &dc, &res, &busy, DisplayWireMode::kHalfDuplexPin);   // Create display connection, (spi, cs, dc, res, busy, wireMode=kSimplex)
    UC8159Full<DisplayConnectionPicoSDK> epd(connection, frameBuffer, sizeof(frameBuffer));   // Create UC8159 driver, (connection, buffer, buffer_len, busy_timeout_ms=60000)
    if (!epd.valid()) {
        PRINT("UC8159 init failed: BUSY never went high\n");
        return 1;
    }
    typedef UC8159Full<DisplayConnectionPicoSDK> Epd;
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
