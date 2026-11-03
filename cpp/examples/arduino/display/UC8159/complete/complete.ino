#if defined(__AVR__)
// The 100 801-byte framebuffer does not fit an AVR board.
void setup() {}
void loop() {}
#else
// UC8159 complete example: Waveshare 5.65inch e-Paper (F), Arduino (600 x 448, 3 bpp, 100 801 bytes: needs a board with >= 128 KB RAM).
// Wiring: DIN=MOSI, CLK=SCK, CS=10, DC=9, RST=8, BUSY=7, DOUT=MISO
// Each refresh blocks for about 30 s. The read_* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO.

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

static uint8_t frameBuffer[100801];

OutputPinArduino cs(10);                            // Create CS# pin, (pin)
OutputPinArduino dc(9);                             // Create D/C# pin, (pin)
OutputPinArduino res(8);                            // Create RES# pin, (pin)
InputPinArduino  busy(7);                           // Create BUSY pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, &busy, SPISettings(1000000, MSBFIRST, SPI_MODE0), DisplayWireMode::kHalfDuplexPin);  // Create display connection, (spi, cs, dc, res, busy, settings, wireMode=kSimplex)
UC8159Full<DisplayConnection> epd(connection, frameBuffer, sizeof(frameBuffer));  // Create UC8159 driver, (connection, buffer, width, height, busy_timeout_ms=30000)

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    if (!epd.valid()) {
        PRINT("UC8159 init failed: BUSY never went high\n");
        return;
    }
    typedef UC8159Full<DisplayConnection> Epd;
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

}

void loop() {}

#endif
