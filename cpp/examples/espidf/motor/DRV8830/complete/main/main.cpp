#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
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

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = DRV8830Minimal::I2C_ADDRESS,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    DRV8830Full motor(connection);                          // Create DRV8830 Full driver, (connection)

    motor.drive(2.5f);                                      // Drive at regulated voltage, (voltage V, + = forward) → void
                                                            // maps 2.5 V to the nearest VSET code and sets IN1=1, IN2=0
    vTaskDelay(pdMS_TO_TICKS(1000));
    DRV8830Full::Output out = motor.readOutput();           // Read back CONTROL, () → Output {float V, Direction}
                                                            // decodes VSET to volts and IN1/IN2 to a Direction
    printf("commanded %.2f V %s\n", (double)out.voltage, directionName(out.direction));

    motor.drive(-1.5f);                                     // Drive at regulated voltage, (voltage V, - = reverse) → void
                                                            // a negative voltage sets IN1=0, IN2=1
    vTaskDelay(pdMS_TO_TICKS(1000));

    bool ok = motor.setOutput(37, true, false);             // Write raw CONTROL fields, (vset 6–63, in1, in2) → bool
                                                            // VSET 37 is ~2.97 V forward; codes 0–5 are rejected (false)
    vTaskDelay(pdMS_TO_TICKS(1000));

    motor.brake();                                          // Short-brake, () → void
                                                            // IN1=IN2=1 drives both outputs high
    vTaskDelay(pdMS_TO_TICKS(500));
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
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
