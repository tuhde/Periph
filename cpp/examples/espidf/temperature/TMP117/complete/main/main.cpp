#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "TMP117.h"

static volatile uint8_t pendingStatus = 0;
static volatile bool pending = false;

static void onAlert(uint8_t status) {
    pendingStatus = status;
    pending = true;
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
    dev_cfg.device_address = TMP117Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    TMP117Full sensor(connection);                          // Create TMP117 Full driver, (connection)
                                                            // checks DEVICE_ID bits 11:0 == 0x117

    float t = sensor.readTemperature();                     // Read temperature, () → °C
                                                            // decodes TEMP_RESULT, 0.0078125 °C two's complement
    printf("temperature %.4f C\n", (double)t);

    sensor.configure(TMP117Full::Mode::Continuous, 32, 0.5f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
                                                            // writes MOD/AVG/CONV; cycle snaps to the nearest CONV step
    TMP117Full::Config cfg = sensor.getConfig();            // Read conversion config, () → Config {mode, averaging, cycleSeconds s}
                                                            // decodes MOD, AVG and CONV from CONFIGURATION
    printf("mode %d averaging %d cycle %.4f s\n", (int)cfg.mode, cfg.averaging, (double)cfg.cycleSeconds);

    sensor.configure(TMP117Full::Mode::Shutdown);           // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
                                                            // MOD=01 stops conversions; TEMP_RESULT keeps its last value
    printf("shutdown %d\n", sensor.isShutdown());           // Check Shutdown mode, () → bool
                                                            // reads MOD[1:0] == 01
    sensor.triggerOneShot();                                // Start one conversion, () → void
                                                            // MOD=11; returns to Shutdown when done
    while (!sensor.isDataReady()) {                         // Check for a fresh result, () → bool
        vTaskDelay(pdMS_TO_TICKS(10));                              // reading Data_Ready clears it
    }
    printf("one-shot %.4f C\n", (double)sensor.readTemperature());  // Read temperature, () → °C
                                                            // the one-shot result
    sensor.configure();                                     // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
                                                            // back to the POR default

    sensor.setHighLimit(30.0f);                             // Set THIGH_LIMIT, (celsius °C) → void
                                                            // rounded to the nearest 0.0078125 °C step
    sensor.setLowLimit(10.0f);                              // Set TLOW_LIMIT, (celsius °C) → void
                                                            // rounded to the nearest 0.0078125 °C step
    printf("high %.4f C\n", (double)sensor.getHighLimit()); // Read THIGH_LIMIT, () → °C
                                                            // same format as TEMP_RESULT
    printf("low %.4f C\n", (double)sensor.getLowLimit());   // Read TLOW_LIMIT, () → °C
                                                            // same format as TEMP_RESULT

    sensor.setTemperatureOffset(0.25f);                     // Set calibration offset, (celsius °C) → void
                                                            // added to every result after linearization
    printf("offset %.4f C\n", (double)sensor.getTemperatureOffset());  // Read calibration offset, () → °C
                                                            // decodes TEMP_OFFSET
    sensor.setTemperatureOffset(0.0f);                      // Set calibration offset, (celsius °C) → void
                                                            // remove the offset again

    sensor.unlockEeprom();                                  // Unlock EEPROM, () → void
                                                            // EUN=1: EEPROM-backed writes now persist
    printf("eeprom busy %d\n", sensor.isEepromBusy());      // Check EEPROM busy, () → bool
                                                            // reads EEPROM_UL.EEPROM_Busy
    sensor.lockEeprom();                                    // Lock EEPROM, () → void
                                                            // EUN=0: writes are volatile again
    uint16_t scratch = 0;
    sensor.readEepromScratch(1, scratch);                   // Read EEPROM scratch, (slot 1|2|3, value&) → bool
                                                            // slot 1 holds part of the factory unique ID
    printf("eeprom1 0x%04X\n", scratch);
    sensor.writeEepromScratch(2, 0x1234);                   // Write EEPROM scratch, (slot 2, value 16-bit) → bool
                                                            // only EEPROM2 is writable; volatile while locked
    sensor.readEepromScratch(2, scratch);                   // Read EEPROM scratch, (slot 1|2|3, value&) → bool
                                                            // reads back EEPROM2
    printf("eeprom2 0x%04X\n", scratch);

    sensor.configureAlert(TMP117Full::AlertMode::Alert,
                          TMP117Full::AlertPolarity::ActiveLow,
                          TMP117Full::AlertPinFunction::Alert);  // Configure ALERT, (mode=Alert, polarity=ActiveLow, pinFunction=Alert) → void
                                                            // sets T/nA, POL and DR/Alert together

    uint8_t status = sensor.pollInterrupt();                // Read alert flags, () → uint8_t mask
                                                            // HIGH_Alert/LOW_Alert; the read clears them in Alert mode
    printf("above high %d below low %d\n",
           (status & TMP117Full::SOURCE_HIGH) != 0, (status & TMP117Full::SOURCE_LOW) != 0);

    sensor.onInterrupt(onAlert);                            // Subscribe to ALERT, (callback, intPin=nullptr) → void
                                                            // callback receives the pollInterrupt() mask
    vTaskDelay(pdMS_TO_TICKS(5000));
    if (pending) printf("alert, status mask %u\n", pendingStatus);
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the pin handler

    sensor.reset();                                         // Software reset, () → void
                                                            // reloads CONFIGURATION/limits/offset from EEPROM, 2 ms
}
