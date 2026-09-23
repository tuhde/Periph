#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DRV8830.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static const char* directionName(DRV8830Full::Direction d) {
    switch (d) {
        case DRV8830Full::Direction::Forward: return "forward";
        case DRV8830Full::Direction::Reverse: return "reverse";
        case DRV8830Full::Direction::Brake:   return "brake";
        default:                              return "coast";
    }
}

static void checkFault(DRV8830Full& motor) {
    // --- Recover from a fault instead of leaving the bridge latched off ---
    // OCP and ILIMIT disable the H-bridge until CLEAR is written; stop first
    // so the motor does not lurch back to the old command on clear.
    DRV8830Full::Fault f = motor.readFault();               // Read fault status, () → Fault
    if (f.fault) {
        printk("fault:%s%s%s%s\n", f.ocp ? " OCP" : "", f.uvlo ? " UVLO" : "",
               f.ots ? " OTS" : "", f.ilimit ? " ILIMIT" : "");
        motor.stop();                                       // Coast to standby, () → void
        motor.clearFault();                                 // Clear fault bits, () → void
    }
}

static void run(DRV8830Full& motor, float voltage, int seconds) {
    // --- Hold a regulated voltage and watch it stay put ---
    // The chip PWM-regulates the bridge against VCC internally, so the
    // commanded voltage (and motor speed) holds while the battery discharges.
    motor.drive(voltage);                                   // Drive at regulated voltage, (voltage V, signed) → void
    checkFault(motor);
    for (int i = 0; i < seconds; ++i) {
        k_sleep(K_MSEC(1000));
        DRV8830Full::Output out = motor.readOutput();       // Read back CONTROL, () → Output {float V, Direction}
        printk("%-7s %.2f V\n", directionName(out.direction), (double)out.voltage);
    }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DRV8830Minimal::I2C_ADDRESS);
    DRV8830Full motor(connection);                          // Create DRV8830 Full driver, (connection)

    // --- Battery-powered toy: constant speed forward, then reverse ---
    run(motor, 3.0f, 5);
    run(motor, -2.0f, 5);

    // --- Stop quickly, then release ---
    // Braking shorts the winding for a fast stop; coasting afterwards removes
    // the load so the motor does not sit shorted indefinitely.
    motor.brake();                                          // Short-brake, () → void
    k_sleep(K_MSEC(500));
    motor.stop();                                           // Coast to standby, () → void

    while (1) {
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
