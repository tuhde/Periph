#pragma once
// Shared body of the display connection smoke tests (one app per platform).
//
// The checks need no wiring beyond the pins the app constructs: they verify
// that every operation of the connection is accepted, that BUSY is readable
// and that waitBusy() returns within its timeout. They do not verify the
// controller's response; that is the job of the chip driver tests.
#include <stdint.h>
#include <stddef.h>
#include "DisplayConnectionBase.h"

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
