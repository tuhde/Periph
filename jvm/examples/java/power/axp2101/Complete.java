///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.AXP2101Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x34").replaceFirst("^0[xX]", ""), 16);
        try (var connection = new I2CConnection(bus, addr)) {
            var chip = new AXP2101Full(connection);                  // Create AXP2101 Full driver, (connection)
                                                                   // runs the init sequence: bus check, ADC channels on, fuel gauge on
            var onSrc = chip.powerOnSource();                           // Power on source, () → int
                                                                        // PWRON_STATUS flags: key, IRQ pin, VBUS, battery
            System.out.println("power on source: " + onSrc);
            var offSrc = chip.powerOffSource();                         // Power off source, () → int
                                                                        // PWROFF_STATUS flags: why the PMIC last powered off
            System.out.println("power off source: " + offSrc);
            var vbus = chip.vbusVoltage();                              // Vbus voltage, () → double V
                                                                        // VBUS ADC channel, 1 mV per LSB
            System.out.println("vbus voltage: " + vbus);
            var vsys = chip.systemVoltage();                            // System voltage, () → double V
                                                                        // VSYS ADC channel, 1 mV per LSB
            System.out.println("system voltage: " + vsys);
            var ts = chip.tsVoltage();                                  // Ts voltage, () → double V
                                                                        // TS pin ADC channel, 0.5 mV per LSB
            System.out.println("ts voltage: " + ts);
            var tdie = chip.dieTemperature();                           // Die temperature, () → double °C
                                                                        // die-temperature channel, vendor formula (verify on hardware)
            System.out.println("die temperature: " + tdie);
            var snapshot = chip.readAll();                              // Read all, () → record
                                                                        // one call returns every measurement plus charge state and VBUS flag
            System.out.println("read all: " + snapshot);
            var flags = chip.status();                                  // Status, () → record
                                                                        // decodes STATUS1 / STATUS2 into named flags
            System.out.println("status: " + flags);
            chip.setAdcChannels(true, true, true, true, true, false);   // Set adc channels, (vbat=True, ts=True, vbus=True, vsys=True, tdie=True, gpadc=False) → void
                                                                        // ADC_ENABLE channel mask; GPADC stays off
            chip.setAdcLowFrequency(false);                             // Set adc low frequency, (enabled) → void
                                                                        // TS_H bit 7: slow 64 s sampling only when powered off on battery
            chip.setCharging(true);                                     // Set charging, (enabled) → void
                                                                        // MODULE_ENABLE bit 1: main-cell charging
            chip.setChargeVoltage(4.2);                                 // Set charge voltage, (volts 4.0/4.1/4.2/4.35/4.4) → void
                                                                        // CV register; 4.35 / 4.4 V are for high-voltage cells only
            chip.setChargeCurrent(0.5);                                 // Set charge current, (amps 0–1.5) → void
                                                                        // ICC: 25 mA steps up to 0.2 A, 100 mA steps above; capped by the charge voltage
            chip.setPrechargeCurrent(0.125);                            // Set precharge current, (amps 0–0.2) → void
                                                                        // IPRECHG in 25 mA steps
            chip.setTermination(0.125, true);                           // Set termination, (amps 0–0.2, enabled=True) → void
                                                                        // ITERM current in 25 mA steps plus the enable bit
            chip.setInputCurrentLimit(1.5);                             // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → void
                                                                        // IINLIM: input current limit
            chip.setInputVoltageLimit(4.36);                            // Set input voltage limit, (volts 3.88–5.08) → void
                                                                        // VINDPM in 80 mV steps
            chip.setMinSystemVoltage(3.7);                              // Set min system voltage, (volts 3.2–3.9) → void
                                                                        // MIN_VSYS in 100 mV steps
            chip.setChargeTimers(60, 12, true, true, true);             // Set charge timers, (precharge_min=60, done_hours=12, precharge_enabled=True, done_enabled=True, slow_in_dpm=True) → void
                                                                        // CHG_TIMER: pre-charge 40–70 min, charge-done 5–20 h
            chip.setThermalRegulation(100);                             // Set thermal regulation, (celsius 60/80/100/120) → void
                                                                        // THERMAL_REG: charger thermal-regulation threshold
            chip.setDieProtection(125, true);                           // Set die protection, (level1_celsius=125, detect=True) → void
                                                                        // DIE_TEMP_CTRL: die over-temperature level 1 and detection
            chip.setBatteryDetection(true);                             // Set battery detection, (enabled) → void
                                                                        // BAT_DETECT bit 0
            chip.setBackupCharging(false, 2.9);                         // Set backup charging, (enabled, volts=2.9 V) → void
                                                                        // MODULE_ENABLE bit 2 and BTN_BAT_VTERM for the coin cell
            chip.setChgled("type_a", "hiz");                            // Set chgled, (mode='type_a', output='hiz') → void
                                                                        // CHGLED_CTRL: charge indicator behaviour
            chip.configureTs(true, 50, "when_adc");                     // Configure ts, (battery_sensor=True, current_ua=50 µA, source='when_adc') → void
                                                                        // TS_CTRL: thermistor mode, injected current and source mode
            chip.setTsLimits(1.312, 0.176, 1.984, 0.152);               // Set ts limits, (charge_cold, charge_hot, work_cold, work_hot) → void V
                                                                        // VLTF/VHTF thresholds as TS voltages (32 mV / 2 mV steps)
            chip.setTsHysteresis(0.032, 0.004);                         // Set ts hysteresis, (low_to_normal=0.032 V, high_to_normal=0.004 V) → void
                                                                        // TS_HYS_L2H (16 mV steps) and TS_HYS_H2L (4 mV steps)
            chip.configureJeita(false, null, null, true, false, 0, 1);  // Configure jeita, (enabled, cool_voltage=None, warm_voltage=None, cool_half_current=True, warm_half_current=False, cool_voltage_drop=0, warm_voltage_drop=1) → void
                                                                        // JEITA profile; thresholds are left unchanged when None
            var rail = chip.getRail("aldo1");                           // Get rail, (name) → Rail(enabled, volts V)
                                                                        // enable bit and programmed voltage of one rail
            System.out.println("get rail: " + rail);
            chip.setRailVoltage("aldo1", rail.volts());                 // Set rail voltage, (name, volts) → void V
                                                                        // rounds to the nearest step inside the rail range; raises outside it
            chip.setRailEnabled("aldo1", rail.enabled());               // Set rail enabled, (name, enabled) → void
                                                                        // enable bit in DCDC_EN / LDO_EN0 / LDO_EN1
            chip.configureDcdc("dcdc1", false, null);                   // Configure dcdc, (name, always_pwm=False, dvm=None) → void
                                                                        // forced-PWM bit per DCDC; DVM only for DCDC2 / DCDC3
            chip.configureDcdcGlobal(false, false, false, false, 60);   // Configure dcdc global, (force_ccm=False, slow_ramp=False, frequency_spread=False, spread_100khz=False, uvp_debounce_us=60 µs) → void
                                                                        // DCDC_EN bits 6 / 5 and DCDC_PWM bits 7:6, 1:0
            chip.setOffDischarge(true);                                 // Set off discharge, (enabled) → void
                                                                        // COMMON_CONFIG bit 5: discharge outputs when off
            chip.configureSleep(false, true, false);                    // Configure sleep, (wake_on_irq=False, pwrok_low=True, restore_voltages=False) → void
                                                                        // SLEEP_CTRL bits 4, 3, 2
            chip.configurePowerKey(512, 6.0, 1.5, true, false, false);  // Configure power key, (on_ms=512, off_s=6, irq_s=1.5, long_press_off=True, restart_instead=False, force_off_16s=False) → void
                                                                        // power-key timing and the actions of a long press
            chip.configurePowerOff(true, 2.6, true, 0x0F);              // Configure power off, (die_over_temp=True, off_voltage=2.6 V, dcdc_ovp=True, dcdc_uvp=0x0F) → void
                                                                        // power-off sources: die over-temperature, VOFF threshold, DCDC OVP / UVP
            chip.configurePwrok(true, 8, true, false, false);           // Configure pwrok, (check=True, delay_ms=8 ms, off_delay=True, reverse_off_sequence=False, restart_on_low=False) → void
                                                                        // PWROK_CTRL delay and sequencing
            chip.setBatfetOffEnable(false);                             // Set batfet off enable, (enabled) → void
                                                                        // BATFET_CTRL bit 3: BATFET on when powered off on battery only
            chip.configureFastPoweron(false, false, null);              // Configure fast poweron, (poweron, wakeup, sequence=None) → void
                                                                        // FAST_PWRON enables; the per-rail start sequence is left untouched
            chip.configureWatchdog(false, 64, "irq");                   // Configure watchdog, (enabled, timeout_s=64 s, action='irq') → void
                                                                        // watchdog disabled here; timeout and action are still programmed
            chip.feedWatchdog();                                        // Feed watchdog, () → void
                                                                        // WATCHDOG_CTRL bit 3: clears the watchdog counter
            chip.configureBatteryWarning(10, 1);                        // Configure battery warning, (level2=10 %, level1=1 %) → void
                                                                        // LOWBAT_WARN: warning levels 2 (5–20 %) and 1 (0–15 %)
            chip.setGaugeEnabled(true);                                 // Set gauge enabled, (enabled) → void
                                                                        // MODULE_ENABLE bit 3: fuel gauge
            chip.resetGauge(false);                                     // Reset gauge, (full=False) → void
                                                                        // GAUGE_RESET bit 3 (bit 2 for the deeper reset)
            chip.setGpio1(false);                                       // Set gpio1, (low) → void
                                                                        // GPIO1 open-drain output: False = Hi-Z
            chip.writeDataBuffer(0, 0x5A);                              // Write data buffer, (index 0–3, value 0–255) → void
                                                                        // stores a byte that survives soft restarts
            var stored = chip.readDataBuffer(0);                        // Read data buffer, (index 0–3) → int
                                                                        // reads the byte back
            System.out.println("read data buffer: " + stored);
            chip.enableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT);       // Enable interrupt, (source) → void
                                                                        // sets the source bits in IRQ_EN0..2
            chip.disableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT);      // Disable interrupt, (source) → void
                                                                        // clears the source bits in IRQ_EN0..2
            var irq = chip.pollInterrupt();                             // Poll interrupt, () → int
                                                                        // reads IRQ_STATUS0..2, clears the bits seen and returns the 24-bit status
            System.out.println("poll interrupt: " + irq);

            // Destructive commands stay disabled; set ENABLE_DANGEROUS only on a bench supply.
            final boolean ENABLE_DANGEROUS = false;
            if (ENABLE_DANGEROUS) {
                chip.powerOff();                                        // Power off, () → void
                                                                        // COMMON_CONFIG bit 0: soft power-off, cuts every output except RTCLDO
                chip.restart();                                         // Restart, () → void
                                                                        // COMMON_CONFIG bit 1: restarts the system
                chip.sleep();                                           // Sleep, () → void
                                                                        // SLEEP_CTRL bit 0: enter sleep
                chip.wakeup();                                          // Wakeup, () → void
                                                                        // SLEEP_CTRL bit 1: wake up
            }
        }
    }
}
