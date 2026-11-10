'use strict';

// Complete example for the AXP2101 — exercise every method in the public API.
// Rail setters are called with the values just read so the board keeps running;
// destructive commands are guarded by ENABLE_DANGEROUS.

const { I2CConnection } = require('periph/src/connection/i2c');
const { AXP2101Full }   = require('periph/src/chips/power/axp2101');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x34', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const chip = new AXP2101Full(connection);                  // Create AXP2101 Full driver, (connection)

(async () => {
    await chip.ready;                                         // Wait for init, () → Promise
                                                              // runs the init sequence: bus check, ADC channels on, fuel gauge on
    const onSrc = await chip.powerOnSource();                 // Power on source, () → number
                                                              // PWRON_STATUS flags: key, IRQ pin, VBUS, battery
    const offSrc = await chip.powerOffSource();               // Power off source, () → number
                                                              // PWROFF_STATUS flags: why the PMIC last powered off
    const vbus = await chip.vbusVoltage();                    // Vbus voltage, () → number V
                                                              // VBUS ADC channel, 1 mV per LSB
    const vsys = await chip.systemVoltage();                  // System voltage, () → number V
                                                              // VSYS ADC channel, 1 mV per LSB
    const ts = await chip.tsVoltage();                        // Ts voltage, () → number V
                                                              // TS pin ADC channel, 0.5 mV per LSB
    const tdie = await chip.dieTemperature();                 // Die temperature, () → number °C
                                                              // die-temperature channel, vendor formula (verify on hardware)
    const snapshot = await chip.readAll();                    // Read all, () → object
                                                              // one call returns every measurement plus charge state and VBUS flag
    const flags = await chip.status();                        // Status, () → object
                                                              // decodes STATUS1 / STATUS2 into named flags
    await chip.setAdcChannels(true, true, true, true, true, false); // Set adc channels, (vbat=True, ts=True, vbus=True, vsys=True, tdie=True, gpadc=False) →
                                                              // ADC_ENABLE channel mask; GPADC stays off
    await chip.setAdcLowFrequency(false);                     // Set adc low frequency, (enabled) →
                                                              // TS_H bit 7: slow 64 s sampling only when powered off on battery
    await chip.setCharging(true);                             // Set charging, (enabled) →
                                                              // MODULE_ENABLE bit 1: main-cell charging
    await chip.setChargeVoltage(4.2);                         // Set charge voltage, (volts 4.0/4.1/4.2/4.35/4.4) →
                                                              // CV register; 4.35 / 4.4 V are for high-voltage cells only
    await chip.setChargeCurrent(0.5);                         // Set charge current, (amps 0–1.5) →
                                                              // ICC: 25 mA steps up to 0.2 A, 100 mA steps above; capped by the charge voltage
    await chip.setPrechargeCurrent(0.125);                    // Set precharge current, (amps 0–0.2) →
                                                              // IPRECHG in 25 mA steps
    await chip.setTermination(0.125, true);                   // Set termination, (amps 0–0.2, enabled=True) →
                                                              // ITERM current in 25 mA steps plus the enable bit
    await chip.setInputCurrentLimit(1.5);                     // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) →
                                                              // IINLIM: input current limit
    await chip.setInputVoltageLimit(4.36);                    // Set input voltage limit, (volts 3.88–5.08) →
                                                              // VINDPM in 80 mV steps
    await chip.setMinSystemVoltage(3.7);                      // Set min system voltage, (volts 3.2–3.9) →
                                                              // MIN_VSYS in 100 mV steps
    await chip.setChargeTimers(60, 12, true, true, true);     // Set charge timers, (precharge_min=60, done_hours=12, precharge_enabled=True, done_enabled=True, slow_in_dpm=True) →
                                                              // CHG_TIMER: pre-charge 40–70 min, charge-done 5–20 h
    await chip.setThermalRegulation(100);                     // Set thermal regulation, (celsius 60/80/100/120) →
                                                              // THERMAL_REG: charger thermal-regulation threshold
    await chip.setDieProtection(125, true);                   // Set die protection, (level1_celsius=125, detect=True) →
                                                              // DIE_TEMP_CTRL: die over-temperature level 1 and detection
    await chip.setBatteryDetection(true);                     // Set battery detection, (enabled) →
                                                              // BAT_DETECT bit 0
    await chip.setBackupCharging(false, 2.9);                 // Set backup charging, (enabled, volts=2.9 V) →
                                                              // MODULE_ENABLE bit 2 and BTN_BAT_VTERM for the coin cell
    await chip.setChgled('type_a', 'hiz');                    // Set chgled, (mode='type_a', output='hiz') →
                                                              // CHGLED_CTRL: charge indicator behaviour
    await chip.configureTs(true, 50, 'when_adc');             // Configure ts, (battery_sensor=True, current_ua=50 µA, source='when_adc') →
                                                              // TS_CTRL: thermistor mode, injected current and source mode
    await chip.setTsLimits(1.312, 0.176, 1.984, 0.152);       // Set ts limits, (charge_cold, charge_hot, work_cold, work_hot) → V
                                                              // VLTF/VHTF thresholds as TS voltages (32 mV / 2 mV steps)
    await chip.setTsHysteresis(0.032, 0.004);                 // Set ts hysteresis, (low_to_normal=0.032 V, high_to_normal=0.004 V) →
                                                              // TS_HYS_L2H (16 mV steps) and TS_HYS_H2L (4 mV steps)
    await chip.configureJeita(false, null, null, true, false, 0, 1); // Configure jeita, (enabled, cool_voltage=None, warm_voltage=None, cool_half_current=True, warm_half_current=False, cool_voltage_drop=0, warm_voltage_drop=1) →
                                                              // JEITA profile; thresholds are left unchanged when None
    const [enabled, volts] = await chip.getRail('aldo1');     // Get rail, (name) → [boolean, number V]
                                                              // enable bit and programmed voltage of one rail
    await chip.setRailVoltage('aldo1', volts);                // Set rail voltage, (name, volts) → V
                                                              // rounds to the nearest step inside the rail range; raises outside it
    await chip.setRailEnabled('aldo1', enabled);              // Set rail enabled, (name, enabled) →
                                                              // enable bit in DCDC_EN / LDO_EN0 / LDO_EN1
    await chip.configureDcdc('dcdc1', false, null);           // Configure dcdc, (name, always_pwm=False, dvm=None) →
                                                              // forced-PWM bit per DCDC; DVM only for DCDC2 / DCDC3
    await chip.configureDcdcGlobal(false, false, false, false, 60); // Configure dcdc global, (force_ccm=False, slow_ramp=False, frequency_spread=False, spread_100khz=False, uvp_debounce_us=60 µs) →
                                                              // DCDC_EN bits 6 / 5 and DCDC_PWM bits 7:6, 1:0
    await chip.setOffDischarge(true);                         // Set off discharge, (enabled) →
                                                              // COMMON_CONFIG bit 5: discharge outputs when off
    await chip.configureSleep(false, true, false);            // Configure sleep, (wake_on_irq=False, pwrok_low=True, restore_voltages=False) →
                                                              // SLEEP_CTRL bits 4, 3, 2
    await chip.configurePowerKey(512, 6, 1.5, true, false, false); // Configure power key, (on_ms=512, off_s=6, irq_s=1.5, long_press_off=True, restart_instead=False, force_off_16s=False) →
                                                              // power-key timing and the actions of a long press
    await chip.configurePowerOff(true, 2.6, true, 0x0F);      // Configure power off, (die_over_temp=True, off_voltage=2.6 V, dcdc_ovp=True, dcdc_uvp=0x0F) →
                                                              // power-off sources: die over-temperature, VOFF threshold, DCDC OVP / UVP
    await chip.configurePwrok(true, 8, true, false, false);   // Configure pwrok, (check=True, delay_ms=8 ms, off_delay=True, reverse_off_sequence=False, restart_on_low=False) →
                                                              // PWROK_CTRL delay and sequencing
    await chip.setBatfetOffEnable(false);                     // Set batfet off enable, (enabled) →
                                                              // BATFET_CTRL bit 3: BATFET on when powered off on battery only
    await chip.configureFastPoweron(false, false);            // Configure fast poweron, (poweron, wakeup, sequence=None) →
                                                              // FAST_PWRON enables; the per-rail start sequence is left untouched
    await chip.configureWatchdog(false, 64, 'irq');           // Configure watchdog, (enabled, timeout_s=64 s, action='irq') →
                                                              // watchdog disabled here; timeout and action are still programmed
    await chip.feedWatchdog();                                // Feed watchdog, () →
                                                              // WATCHDOG_CTRL bit 3: clears the watchdog counter
    await chip.configureBatteryWarning(10, 1);                // Configure battery warning, (level2=10 %, level1=1 %) →
                                                              // LOWBAT_WARN: warning levels 2 (5–20 %) and 1 (0–15 %)
    await chip.setGaugeEnabled(true);                         // Set gauge enabled, (enabled) →
                                                              // MODULE_ENABLE bit 3: fuel gauge
    await chip.resetGauge(false);                             // Reset gauge, (full=False) →
                                                              // GAUGE_RESET bit 3 (bit 2 for the deeper reset)
    await chip.setGpio1(false);                               // Set gpio1, (low) →
                                                              // GPIO1 open-drain output: False = Hi-Z
    await chip.writeDataBuffer(0, 0x5A);                      // Write data buffer, (index 0–3, value 0–255) →
                                                              // stores a byte that survives soft restarts
    const stored = await chip.readDataBuffer(0);              // Read data buffer, (index 0–3) → number
                                                              // reads the byte back
    await chip.enableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT); // Enable interrupt, (source) →
                                                              // sets the source bits in IRQ_EN0..2
    await chip.disableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT); // Disable interrupt, (source) →
                                                              // clears the source bits in IRQ_EN0..2
    const irq = await chip.pollInterrupt();                   // Poll interrupt, () → number
                                                              // reads IRQ_STATUS0..2, clears the bits seen and returns the 24-bit status

    // Destructive commands stay disabled; set ENABLE_DANGEROUS only on a bench supply.
    const ENABLE_DANGEROUS = false;
    if (ENABLE_DANGEROUS) {
        await chip.powerOff();                                // Power off, () → None
                                                              // COMMON_CONFIG bit 0: soft power-off, cuts every output except RTCLDO
        await chip.restart();                                 // Restart, () → None
                                                              // COMMON_CONFIG bit 1: restarts the system
        await chip.sleep();                                   // Sleep, () → None
                                                              // SLEEP_CTRL bit 0: enter sleep
        await chip.wakeup();                                  // Wakeup, () → None
                                                              // SLEEP_CTRL bit 1: wake up
    }
    await connection.close();
})();
