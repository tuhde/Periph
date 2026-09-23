#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "DRV8830.h"

static const char* directionName(DRV8830Full::Direction d) {
    switch (d) {
        case DRV8830Full::Direction::Forward: return "forward";
        case DRV8830Full::Direction::Reverse: return "reverse";
        case DRV8830Full::Direction::Brake:   return "brake";
        default:                              return "coast";
    }
}

static void onFault(const DRV8830Full::Fault& f) {
    printf("fault interrupt ocp=%d uvlo=%d ots=%d ilimit=%d\n", f.ocp, f.uvlo, f.ots, f.ilimit);
}

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, DRV8830Minimal::I2C_ADDRESS);
    DRV8830Full motor(connection);                          // Create DRV8830 Full driver, (connection)

    motor.drive(2.5f);                                      // Drive at regulated voltage, (voltage V, + = forward) → void
                                                            // maps 2.5 V to the nearest VSET code and sets IN1=1, IN2=0
    sleep_ms(1000);
    DRV8830Full::Output out = motor.readOutput();           // Read back CONTROL, () → Output {float V, Direction}
                                                            // decodes VSET to volts and IN1/IN2 to a Direction
    printf("commanded %.2f V %s\n", (double)out.voltage, directionName(out.direction));

    motor.drive(-1.5f);                                     // Drive at regulated voltage, (voltage V, - = reverse) → void
                                                            // a negative voltage sets IN1=0, IN2=1
    sleep_ms(1000);

    bool ok = motor.setOutput(37, true, false);             // Write raw CONTROL fields, (vset 6–63, in1, in2) → bool
                                                            // VSET 37 is ~2.97 V forward; codes 0–5 are rejected (false)
    sleep_ms(1000);

    motor.brake();                                          // Short-brake, () → void
                                                            // IN1=IN2=1 drives both outputs high
    sleep_ms(500);
    motor.stop();                                           // Coast to standby, () → void
                                                            // IN1=IN2=0 leaves both outputs high-impedance

    DRV8830Full::Fault f = motor.readFault();               // Read fault status, () → Fault {fault, ocp, uvlo, ots, ilimit}
                                                            // does not clear — latched OCP/ILIMIT keep the bridge off
    printf("setOutput=%d fault=%d ocp=%d uvlo=%d ots=%d ilimit=%d\n", ok, f.fault, f.ocp, f.uvlo, f.ots, f.ilimit);
    motor.clearFault();                                     // Clear fault bits, () → void
                                                            // writes CLEAR=1; re-enables a latched-off bridge

    motor.onInterrupt(onFault);                             // Subscribe to FAULTn, (callback, intPin=nullptr) → void
                                                            // callback receives the readFault() result
    DRV8830Full::Fault p = motor.pollInterrupt();           // Poll fault status, () → Fault
                                                            // same as readFault(); never clears implicitly
    motor.offInterrupt();                                   // Unsubscribe, () → void
    printf("poll fault=%d\n", p.fault);

    while (1) {
        sleep_ms(1000);
    }
    return 0;
}
