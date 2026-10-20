#ifndef TEST_CS
#define TEST_CS 10
#endif
#ifndef TEST_DC
#define TEST_DC 9
#endif
#ifndef TEST_RES
#define TEST_RES 8
#endif

#include <SPI.h>
#include <Periph.h>

OutputPinArduino cs(TEST_CS);                       // CS# pin, (pin)
OutputPinArduino dc(TEST_DC);                       // D/C# pin, (pin)
OutputPinArduino res(TEST_RES);                     // RES# pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, nullptr, SPISettings(1000000, MSBFIRST, SPI_MODE0));  // Create display connection, (spi, cs, dc, res, busy)

UC1611sSpiFull<DisplayConnection> chip(connection);  // Create UC1611s full driver, (connection)

int passed = 0;
int failed = 0;

void report(const char* label, bool ok) {
    if (ok) { Serial.print("PASS "); Serial.println(label); passed++; }
    else    { Serial.print("FAIL "); Serial.println(label); failed++; }
}

// Same checks as cpp/tests/display/uc1611s_spi_smoke.h, inlined: the Arduino test runner copies
// each sketch on its own, so a relative include would not resolve.
/** @brief Run the UC1611s checks on an UC1611sFull instance.
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void uc1611sSpiChecks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());
    report("geometry_16x2", chip.columns() == 16 && chip.rows() == 2);

    chip.clear();
    chip.print_at(0, 0, "Periph UC1611s");
    chip.print_at(0, 1, "smoke test");
    report("valid_after_text", chip.valid());

    uint8_t glyph[8] = {0x1F, 0x11, 0x15, 0x11, 0x1F, 0x00, 0x1F, 0x00};
    chip.create_char(0, glyph);
    chip.set_cursor(15, 1);
    chip.write_char(0);
    chip.set_cursor(0, 5);   // out of range: clamped, must not break the driver
    report("valid_after_custom_char", chip.valid());

    chip.cursor(true, true);
    chip.cursor(false, false);
    chip.set_entry_mode(true, false);
    chip.shift_display(true);
    chip.shift_display(false);
    chip.shift_cursor(true);
    chip.return_home();
    chip.set_ddram_address(0x40);
    chip.set_contrast(40);
    chip.set_booster(true);
    chip.set_follower(true, 5);
    chip.set_bias(true);
    chip.set_contrast(24);
    chip.display(false);
    chip.display(true);
    chip.home();
    report("valid_after_commands", chip.valid());
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    uc1611sSpiChecks(chip, report);

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
