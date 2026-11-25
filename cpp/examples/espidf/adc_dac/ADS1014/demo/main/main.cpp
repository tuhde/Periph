#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS1014.h"

// Shunt-current monitor: a 0.1 ohm shunt in the load supply line is read on
// the ±0.256 V range; the traditional comparator watches for more than 1 A.

static const float SHUNT_OHMS = 0.1f;
static const int SAMPLES = 20;

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
    dev_cfg.device_address = ADS1014Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS1014Full adc(connection);                        // Create ADS1014 Full driver, (connection)

    // --- Pick the range and speed for a small shunt voltage ---
    // ±0.256 V gives 0.125 mV per code; 250 SPS keeps noise low and is plenty
    // for a once-per-second report.
    adc.setGain(ADS1014Full::PGA_0_256);                // Set PGA, (pga 0–5) → bool
    adc.setDataRate(ADS1014Full::DR_250);               // Set data rate, (dr 0–6) → bool

    // --- Arm the over-current comparator ---
    // 0.1 V across 0.1 ohm is 1 A. Traditional comparator with hysteresis
    // (clears again below 0.08 V), latched so a brief spike is not missed,
    // and 4 consecutive conversions are needed to assert.
    adc.setThresholds(0.08f, 0.1f);                     // Set both thresholds, (low V, high V) → bool
    adc.configureComparator(ADS1014Full::COMP_TRADITIONAL, ADS1014Full::ACTIVE_LOW, true, ADS1014Full::QUEUE_4);  // Configure comparator, (mode, polarity, latch, queue) → bool
    adc.enableInterrupt(ADS1014Full::SOURCE_COMPARATOR);  // Route comparator to ALERT/RDY, (source) → bool

    // --- Monitor the current ---
    // The pin level is not wired in this demo, so the alert state is inferred
    // from the measured current; clearAlert() releases the latch afterwards.
    for (int i = 0; i < SAMPLES; i++) {
        float v = adc.readVoltage();                    // Read voltage, () → V
        float amps = v / SHUNT_OHMS;
        printf("%.3f V -> %.2f A\n", (double)v, (double)amps);
        if (amps > 1.0f) {
            printf("over-current!\n");
            adc.clearAlert();                           // Release latched alert, () → void
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    // --- Release the alert pin when finished ---
    adc.disableInterrupt(ADS1014Full::SOURCE_COMPARATOR);  // Release ALERT/RDY, (source) → void
}
