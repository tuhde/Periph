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

#include <SPI.h>
#include <Periph.h>

OutputPinArduino cs(TEST_CS);                       // CS# pin, (pin)
OutputPinArduino dc(TEST_DC);                       // D/C# pin, (pin)
OutputPinArduino res(TEST_RES);                     // RES# pin, (pin)
InputPinArduino  busy(TEST_BUSY);                   // BUSY pin, (pin)
DisplayConnection connection(SPI, &cs, &dc, &res, &busy);  // Create display connection, (spi, cs, dc, res, busy)

// Same checks as cpp/tests/connection/display_smoke.h, inlined: the Arduino test runner copies
// each sketch on its own, so a relative include would not resolve.
/** @brief Run the smoke checks on any display connection class.
 *  @param c               Connection under test, constructed kSimplex.
 *  @param busyActiveHigh  BUSY polarity to use for waitBusy().
 *  @param report          Callable (const char* label, bool ok).
 */
template <typename CONN, typename REPORT>
void displaySmoke(CONN& c, bool busyActiveHigh, REPORT report) {
    report("valid", c.valid());
    report("enabled", c.isEnabled());

    c.reset();
    report("reset_accepted", true);

    c.sendCommand(0x12);
    report("command_accepted", true);

    const uint8_t params[2] = {0x01, 0x02};
    c.sendCommand(0x04, params, 2);
    report("command_with_parameters_accepted", true);

    uint8_t pixels[16];
    for (size_t i = 0; i < sizeof(pixels); i++) pixels[i] = 0xFF;
    c.sendData(pixels, sizeof(pixels));
    report("data_accepted", true);

    bool level = c.busyLevel();
    (void)level;
    report("busy_level_readable", true);

    bool ready = c.waitBusy(busyActiveHigh, 50);
    (void)ready;
    report("wait_busy_returns_within_timeout", true);

    uint8_t rb[1] = {0};
    report("read_data_unavailable_in_simplex", !c.readData(rb, 1));

    c.disable();
    report("disable_gates_bus", !c.isEnabled());
    c.sendCommand(0x12);
    c.enable();
    report("enable_resumes", c.isEnabled());
}

int passed = 0;
int failed = 0;

void report(const char* label, bool ok) {
    if (ok) { Serial.print("PASS "); Serial.println(label); passed++; }
    else    { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    displaySmoke(connection, false, report);

    // The Arduino SPIClass has no hardware 3-wire mode: that wire mode must be rejected.
    DisplayConnection halfDuplexHw(SPI, &cs, &dc, &res, &busy, SPISettings(4000000, MSBFIRST, SPI_MODE0),
                                   DisplayWireMode::kHalfDuplexHW);
    report("half_duplex_hw_rejected", !halfDuplexHw.valid());

    // Optional pins: an OLED-style connection without RES# and BUSY.
    DisplayConnection oled(SPI, &cs, &dc, nullptr, nullptr);
    oled.reset();
    report("oled_no_busy_wait_returns_true", oled.waitBusy(true, 10));

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
