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

#define TEST_WIDTH  104
#define TEST_HEIGHT 212

#include <SPI.h>
#include <Periph.h>

OutputPinArduino cs(TEST_CS);                       // CS# pin, (pin)
OutputPinArduino dc(TEST_DC);                       // D/C# pin, (pin)
OutputPinArduino res(TEST_RES);                     // RES# pin, (pin)
InputPinArduino  busy(TEST_BUSY);                   // BUSY pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, &busy);  // Create display connection, (spi, cs, dc, res, busy)

static uint8_t frameBuffer[(TEST_WIDTH / 8) * TEST_HEIGHT];

IL0373Full<DisplayConnection> chip(connection, frameBuffer, TEST_WIDTH, TEST_HEIGHT);  // Create IL0373 full driver, (connection, buffer, width, height)

int passed = 0;
int failed = 0;

void report(const char* label, bool ok) {
    if (ok) { Serial.print("PASS "); Serial.println(label); passed++; }
    else    { Serial.print("FAIL "); Serial.println(label); failed++; }
}

// Same checks as cpp/tests/display/il0373_smoke.h, inlined: the Arduino test runner copies
// each sketch on its own, so a relative include would not resolve.
/** @brief Run the IL0373 checks on a IL0373Full instance.
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void il0373Checks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());

    chip.clear();
    chip.set_pixel(1, 1, CHIP::BLACK);
    report("pixel_black_readback", chip.get_pixel(1, 1) == CHIP::BLACK);
    chip.set_pixel(3, 1, CHIP::WHITE);
    report("pixel_white_readback", chip.get_pixel(3, 1) == CHIP::WHITE);
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

    il0373Checks(chip, report);

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
