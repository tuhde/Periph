// IL0373 complete example: Waveshare 2.13inch e-Paper (D), Arduino (104 x 212 frame, 2756 bytes).
// Wiring: DIN=MOSI, CLK=SCK, CS=10, DC=9, RST=8, BUSY=7, DOUT=MISO
// Each refresh blocks until BUSY goes high (the panel datasheet gives no update time). The read_* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO.
#define EPD_WIDTH  104
#define EPD_HEIGHT 212

#include <stdarg.h>
#include <stdio.h>
#include <SPI.h>
#include <Periph.h>

static void PRINT(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}
#define NOW_MS() ((uint32_t)millis())

static uint8_t frameBuffer[(EPD_WIDTH / 8) * EPD_HEIGHT];

OutputPinArduino cs(10);                            // Create CS# pin, (pin)
OutputPinArduino dc(9);                             // Create D/C# pin, (pin)
OutputPinArduino res(8);                            // Create RES# pin, (pin)
InputPinArduino  busy(7);                           // Create BUSY pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, &busy, SPISettings(1000000, MSBFIRST, SPI_MODE0), DisplayWireMode::kHalfDuplexPin);  // Create display connection, (spi, cs, dc, res, busy, settings, wireMode=kSimplex)
IL0373Full<DisplayConnection> epd(connection, frameBuffer, EPD_WIDTH, EPD_HEIGHT);  // Create IL0373 driver, (connection, buffer, width, height, busy_timeout_ms=30000)

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!epd.valid()) {
        PRINT("IL0373 init failed: BUSY never went high\n");
        return;
    }
    typedef IL0373Full<DisplayConnection> Epd;
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
    PRINT("colour=%u status=%s%02X temperature=%s%d revision=%02X ready=%d\n", (unsigned)colour,
                haveStatus ? "0x" : "n/a ", status, haveTemp ? "" : "n/a ", temperature, revision, ready);
    (void)haveRev;
    PRINT("===DONE: 0 passed, 0 failed===\n");
}

void loop() {}
