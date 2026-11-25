#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "AXP2101.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AXP2101_ADDR 0x34

static const char* const kRails[] = {"dcdc1", "dcdc2", "dcdc3", "dcdc4", "aldo1", "aldo2", "aldo3", "aldo4",
                                     "bldo1", "bldo2", "cpusldo", "dldo1", "dldo2"};
static const struct { uint32_t mask; const char* text; } kEvents[] = {
    {AXP2101Full::SOURCE_VBUS_INSERT, "VBUS inserted"},
    {AXP2101Full::SOURCE_VBUS_REMOVE, "VBUS removed"},
    {AXP2101Full::SOURCE_CHARGE_START, "charging started"},
    {AXP2101Full::SOURCE_CHARGE_DONE, "charging done"},
    {AXP2101Full::SOURCE_PWRON_SHORT, "power key short press"},
    {AXP2101Full::SOURCE_SOC_WARN_L2, "battery warning level 2"},
};

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AXP2101_ADDR);
    AXP2101Full chip(connection);                       // Create AXP2101 Full driver, (connection)

    bool railEnabled = false;
    float railVolts = 0.0f;
    // --- Report why the board started and what every rail is doing ---
    // eFuse defaults differ per product, so the rails are read, never assumed.
    uint8_t src = chip.powerOnSource();                 // Read power-on flags, () → uint8_t
    printk("power-on source: 0x%02X\n", (unsigned)src);
    for (size_t i = 0; i < sizeof(kRails) / sizeof(kRails[0]); i++) {
        chip.getRail(kRails[i], railEnabled, railVolts); // Read rail state, (name, enabled, volts V) → bool
        printk("%s %s %.2f V\n", kRails[i], (railEnabled) ? "true" : "false", (double)railVolts);
    }

    // --- Program a conservative 4.2 V / 500 mA charge profile ---
    // 4.2 V suits a standard Li-ion cell; 500 mA keeps heating low; the 1.5 A input
    // limit lets a USB charger feed both the system and the charger.
    chip.setChargeVoltage(4.2f);                        // Set charge target, (volts 4.0/4.1/4.2/4.35/4.4) → bool
    chip.setChargeCurrent(0.5f);                        // Set charge current, (amps 0–1.5) → bool
    chip.setInputCurrentLimit(1.5f);                    // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → bool
    chip.setTermination(0.05f, true);                   // Set termination current, (amps 0–0.2, enabled) → bool

    // --- Arm low-battery warnings and the power events of interest ---
    // The warning levels are 15 % and 5 %; the six sources below raise the IRQ pin.
    chip.configureBatteryWarning(15, 5);                // Set warning levels, (level2 %, level1 %) → bool
    for (size_t i = 0; i < sizeof(kEvents) / sizeof(kEvents[0]); i++) {
        chip.enableInterrupt(kEvents[i].mask);          // Enable interrupt source, (source) → bool
    }
    chip.pollInterrupt();                               // Clear stale status, () → uint32_t

    // --- Monitor for 120 s ---
    // One status line per second; pollInterrupt() returns and clears every pending event.
    for (int n = 0; n < 120; n++) {
        AXP2101Readings d;
        chip.readAll(d);                                // Read all measurements, (out) → void
        AXP2101Status st;
        chip.status(st);                                // Read status flags, (out) → void
        printk("%.3f V %d %%  vbus %s %.2f V  sys %.2f V  die %.1f C  state %d  %s\n", (double)d.batteryVoltage, (int)d.batteryPercent, (d.vbusPresent) ? "true" : "false", (double)d.vbusVoltage, (double)d.systemVoltage, (double)d.dieTemperature, (int)d.chargeState, st.batteryDirection);
        uint32_t got = chip.pollInterrupt();            // Read and clear events, () → uint32_t
        for (size_t i = 0; i < sizeof(kEvents) / sizeof(kEvents[0]); i++) {
            if (got & kEvents[i].mask) {
                printk("event: %s\n", kEvents[i].text);
            }
        }
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
