#ifndef TEST_CS
#define TEST_CS 10
#endif
#ifndef TEST_DC
#define TEST_DC 9
#endif
#ifndef TEST_RES
#define TEST_RES 8
#endif
#ifndef TEST_BUSY
#define TEST_BUSY 7
#endif

// AVR boards have 8 KB of RAM: run a 64 x 64 window there (TRES), the full 400 x 300 elsewhere.
#if defined(__AVR__)
#define TEST_WIDTH  64
#define TEST_HEIGHT 64
#else
#define TEST_WIDTH  400
#define TEST_HEIGHT 300
#endif

#include <SPI.h>
#include <Periph.h>

OutputPinArduino cs(TEST_CS);                       // CS# pin, (pin)
OutputPinArduino dc(TEST_DC);                       // D/C# pin, (pin)
OutputPinArduino res(TEST_RES);                     // RES# pin, (pin)
InputPinArduino  busy(TEST_BUSY);                   // BUSY_N pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, &busy);  // Create display connection, (spi, cs, dc, res, busy)

static uint8_t bwBuffer[(TEST_WIDTH / 8) * TEST_HEIGHT];
static uint8_t redBuffer[(TEST_WIDTH / 8) * TEST_HEIGHT];

UC8276CFull<DisplayConnection> chip(connection, bwBuffer, redBuffer, TEST_WIDTH, TEST_HEIGHT);  // Create UC8276C full driver, (connection, bw, red, width, height)

int passed = 0;
int failed = 0;

void report(const char* label, bool ok) {
    if (ok) { Serial.print("PASS "); Serial.println(label); passed++; }
    else    { Serial.print("FAIL "); Serial.println(label); failed++; }
}

// Same checks as cpp/tests/display/uc8276c_smoke.h, inlined: the Arduino test runner copies
// each sketch on its own, so a relative include would not resolve.
/** @brief Run the UC8276C checks on a UC8276CFull instance.
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void uc8276cChecks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());

    chip.clear();
    chip.set_pixel(1, 1, CHIP::BLACK);
    report("pixel_black_readback", chip.get_pixel(1, 1) == CHIP::BLACK);
    chip.set_pixel(3, 1, CHIP::RED);
    report("pixel_red_readback", chip.get_pixel(3, 1) == (chip.has_red() ? CHIP::RED : CHIP::BLACK));
    chip.draw_rect(0, 0, chip.width(), chip.height(), CHIP::BLACK);
    chip.draw_line(0, 0, chip.width() - 1, chip.height() - 1, CHIP::BLACK);
    report("drawing_accepted", chip.get_pixel(0, 0) == CHIP::BLACK);

    report("show_returns_true", chip.show());
    report("valid_after_show", chip.valid());
    report("sleep_accepted", chip.sleep());
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    uc8276cChecks(chip, report);

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
