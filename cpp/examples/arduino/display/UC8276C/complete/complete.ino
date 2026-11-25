// UC8276C complete example: Waveshare 4.2inch e-Paper (B), Arduino (full 400 x 300 frame on boards with >= 32 KB RAM; AVR boards run a 64 x 64 window).
// Wiring: DIN=MOSI, CLK=SCK, CS=10, DC=9, RST=8, BUSY=7, DOUT=MISO
// Each refresh blocks for about 17 s. The read_* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO.
// AVR boards have 8 KB of RAM: run a 64 x 64 window there (TRES), the full 400 x 300 elsewhere.
#if defined(__AVR__)
#define EPD_WIDTH  64
#define EPD_HEIGHT 64
#else
#define EPD_WIDTH  400
#define EPD_HEIGHT 300
#endif

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

static uint8_t bwBuffer[(EPD_WIDTH / 8) * EPD_HEIGHT];
static uint8_t redBuffer[(EPD_WIDTH / 8) * EPD_HEIGHT];

OutputPinArduino cs(10);                            // Create CS# pin, (pin)
OutputPinArduino dc(9);                             // Create D/C# pin, (pin)
OutputPinArduino res(8);                            // Create RES# pin, (pin)
InputPinArduino  busy(7);                           // Create BUSY_N pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, &busy, SPISettings(1000000, MSBFIRST, SPI_MODE0), DisplayWireMode::kHalfDuplexPin);  // Create display connection, (spi, cs, dc, res, busy, settings, wireMode=kSimplex)
UC8276CFull<DisplayConnection> epd(connection, bwBuffer, redBuffer, EPD_WIDTH, EPD_HEIGHT);  // Create UC8276C driver, (connection, bw, red, width, height, busy_timeout_ms=30000)

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!epd.valid()) {
        PRINT("UC8276C init failed: BUSY_N never went high\n");
        return;
    }
    typedef UC8276CFull<DisplayConnection> Epd;
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
}

void loop() {}
