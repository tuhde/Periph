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

static void checkFault(DRV8830Full& motor) {
    // --- Recover from a fault instead of leaving the bridge latched off ---
    // OCP and ILIMIT disable the H-bridge until CLEAR is written; stop first
    // so the motor does not lurch back to the old command on clear.
    DRV8830Full::Fault f = motor.readFault();               // Read fault status, () → Fault
    if (f.fault) {
        printf("fault:%s%s%s%s\n", f.ocp ? " OCP" : "", f.uvlo ? " UVLO" : "",
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
        vTaskDelay(pdMS_TO_TICKS(1000));
        DRV8830Full::Output out = motor.readOutput();       // Read back CONTROL, () → Output {float V, Direction}
        printf("%-7s %.2f V\n", directionName(out.direction), (double)out.voltage);
    }
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = DRV8830Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    DRV8830Full motor(connection);                          // Create DRV8830 Full driver, (connection)

    // --- Battery-powered toy: constant speed forward, then reverse ---
    run(motor, 3.0f, 5);
    run(motor, -2.0f, 5);

    // --- Stop quickly, then release ---
    // Braking shorts the winding for a fast stop; coasting afterwards removes
    // the load so the motor does not sit shorted indefinitely.
    motor.brake();                                          // Short-brake, () → void
    vTaskDelay(pdMS_TO_TICKS(500));
    motor.stop();                                           // Coast to standby, () → void

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
