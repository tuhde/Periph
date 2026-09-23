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

static void onFault(const DRV8830Full::Fault& f) {
    printk("fault interrupt ocp=%d uvlo=%d ots=%d ilimit=%d\n", f.ocp, f.uvlo, f.ots, f.ilimit);
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DRV8830Minimal::I2C_ADDRESS);
    DRV8830Full motor(connection);                          // Create DRV8830 Full driver, (connection)

    motor.drive(2.5f);                                      // Drive at regulated voltage, (voltage V, + = forward) → void
                                                            // maps 2.5 V to the nearest VSET code and sets IN1=1, IN2=0
    k_sleep(K_MSEC(1000));
    DRV8830Full::Output out = motor.readOutput();           // Read back CONTROL, () → Output {float V, Direction}
                                                            // decodes VSET to volts and IN1/IN2 to a Direction
    printk("commanded %.2f V %s\n", (double)out.voltage, directionName(out.direction));

    motor.drive(-1.5f);                                     // Drive at regulated voltage, (voltage V, - = reverse) → void
                                                            // a negative voltage sets IN1=0, IN2=1
    k_sleep(K_MSEC(1000));

    bool ok = motor.setOutput(37, true, false);             // Write raw CONTROL fields, (vset 6–63, in1, in2) → bool
                                                            // VSET 37 is ~2.97 V forward; codes 0–5 are rejected (false)
    k_sleep(K_MSEC(1000));

    motor.brake();                                          // Short-brake, () → void
                                                            // IN1=IN2=1 drives both outputs high
    k_sleep(K_MSEC(500));
    motor.stop();                                           // Coast to standby, () → void
                                                            // IN1=IN2=0 leaves both outputs high-impedance

    DRV8830Full::Fault f = motor.readFault();               // Read fault status, () → Fault {fault, ocp, uvlo, ots, ilimit}
                                                            // does not clear — latched OCP/ILIMIT keep the bridge off
    printk("setOutput=%d fault=%d ocp=%d uvlo=%d ots=%d ilimit=%d\n", ok, f.fault, f.ocp, f.uvlo, f.ots, f.ilimit);
    motor.clearFault();                                     // Clear fault bits, () → void
                                                            // writes CLEAR=1; re-enables a latched-off bridge

    motor.onInterrupt(onFault);                             // Subscribe to FAULTn, (callback, intPin=nullptr) → void
                                                            // callback receives the readFault() result
    DRV8830Full::Fault p = motor.pollInterrupt();           // Poll fault status, () → Fault
                                                            // same as readFault(); never clears implicitly
    motor.offInterrupt();                                   // Unsubscribe, () → void
    printk("poll fault=%d\n", p.fault);

    while (1) {
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
