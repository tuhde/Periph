use linux_embedded_hal::I2cdev;
use periph::chips::power::Axp2101Full;
use periph::chips::power::axp2101::SOURCE_VBUS_INSERT;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x34);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Axp2101Full::new(dev, addr).expect("init AXP2101");         // Create AXP2101 Full driver, (i2c, addr=0x34) → Axp2101Full
    std::thread::sleep(std::time::Duration::from_millis(20));                  // runs the init sequence: bus check, ADC channels on, fuel gauge on

    let on_src = chip.power_on_source().expect("power_on_source");      // Power on source, () → u32
                                                                        // PWRON_STATUS flags: key, IRQ pin, VBUS, battery
    println!("power on source: {:?}", on_src);
    let off_src = chip.power_off_source().expect("power_off_source");   // Power off source, () → u32
                                                                        // PWROFF_STATUS flags: why the PMIC last powered off
    println!("power off source: {:?}", off_src);
    let vbus = chip.vbus_voltage().expect("vbus_voltage");              // Vbus voltage, () → f32 V
                                                                        // VBUS ADC channel, 1 mV per LSB
    println!("vbus voltage: {:?}", vbus);
    let vsys = chip.system_voltage().expect("system_voltage");          // System voltage, () → f32 V
                                                                        // VSYS ADC channel, 1 mV per LSB
    println!("system voltage: {:?}", vsys);
    let ts = chip.ts_voltage().expect("ts_voltage");                    // Ts voltage, () → f32 V
                                                                        // TS pin ADC channel, 0.5 mV per LSB
    println!("ts voltage: {:?}", ts);
    let tdie = chip.die_temperature().expect("die_temperature");        // Die temperature, () → f32 °C
                                                                        // die-temperature channel, vendor formula (verify on hardware)
    println!("die temperature: {:?}", tdie);
    let snapshot = chip.read_all().expect("read_all");                  // Read all, () → struct
                                                                        // one call returns every measurement plus charge state and VBUS flag
    println!("read all: {:?}", snapshot);
    let flags = chip.status().expect("status");                         // Status, () → struct
                                                                        // decodes STATUS1 / STATUS2 into named flags
    println!("status: {:?}", flags);
    chip.set_adc_channels(true, true, true, true, true, false).expect("set_adc_channels"); // Set adc channels, (vbat=True, ts=True, vbus=True, vsys=True, tdie=True, gpadc=False) → ()
                                                                        // ADC_ENABLE channel mask; GPADC stays off
    chip.set_adc_low_frequency(false).expect("set_adc_low_frequency");  // Set adc low frequency, (enabled) → ()
                                                                        // TS_H bit 7: slow 64 s sampling only when powered off on battery
    chip.set_charging(true).expect("set_charging");                     // Set charging, (enabled) → ()
                                                                        // MODULE_ENABLE bit 1: main-cell charging
    chip.set_charge_voltage(4.2).expect("set_charge_voltage");          // Set charge voltage, (volts 4.0/4.1/4.2/4.35/4.4) → ()
                                                                        // CV register; 4.35 / 4.4 V are for high-voltage cells only
    chip.set_charge_current(0.5).expect("set_charge_current");          // Set charge current, (amps 0–1.5) → ()
                                                                        // ICC: 25 mA steps up to 0.2 A, 100 mA steps above; capped by the charge voltage
    chip.set_precharge_current(0.125).expect("set_precharge_current");  // Set precharge current, (amps 0–0.2) → ()
                                                                        // IPRECHG in 25 mA steps
    chip.set_termination(0.125, true).expect("set_termination");        // Set termination, (amps 0–0.2, enabled=True) → ()
                                                                        // ITERM current in 25 mA steps plus the enable bit
    chip.set_input_current_limit(1.5).expect("set_input_current_limit"); // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → ()
                                                                        // IINLIM: input current limit
    chip.set_input_voltage_limit(4.36).expect("set_input_voltage_limit"); // Set input voltage limit, (volts 3.88–5.08) → ()
                                                                        // VINDPM in 80 mV steps
    chip.set_min_system_voltage(3.7).expect("set_min_system_voltage");  // Set min system voltage, (volts 3.2–3.9) → ()
                                                                        // MIN_VSYS in 100 mV steps
    chip.set_charge_timers(60, 12, true, true, true).expect("set_charge_timers"); // Set charge timers, (precharge_min=60, done_hours=12, precharge_enabled=True, done_enabled=True, slow_in_dpm=True) → ()
                                                                        // CHG_TIMER: pre-charge 40–70 min, charge-done 5–20 h
    chip.set_thermal_regulation(100).expect("set_thermal_regulation");  // Set thermal regulation, (celsius 60/80/100/120) → ()
                                                                        // THERMAL_REG: charger thermal-regulation threshold
    chip.set_die_protection(125, true).expect("set_die_protection");    // Set die protection, (level1_celsius=125, detect=True) → ()
                                                                        // DIE_TEMP_CTRL: die over-temperature level 1 and detection
    chip.set_battery_detection(true).expect("set_battery_detection");   // Set battery detection, (enabled) → ()
                                                                        // BAT_DETECT bit 0
    chip.set_backup_charging(false, 2.9).expect("set_backup_charging"); // Set backup charging, (enabled, volts=2.9 V) → ()
                                                                        // MODULE_ENABLE bit 2 and BTN_BAT_VTERM for the coin cell
    chip.set_chgled("type_a", "hiz").expect("set_chgled");              // Set chgled, (mode='type_a', output='hiz') → ()
                                                                        // CHGLED_CTRL: charge indicator behaviour
    chip.configure_ts(true, 50, "when_adc").expect("configure_ts");     // Configure ts, (battery_sensor=True, current_ua=50 µA, source='when_adc') → ()
                                                                        // TS_CTRL: thermistor mode, injected current and source mode
    chip.set_ts_limits(1.312, 0.176, 1.984, 0.152).expect("set_ts_limits"); // Set ts limits, (charge_cold, charge_hot, work_cold, work_hot) → () V
                                                                        // VLTF/VHTF thresholds as TS voltages (32 mV / 2 mV steps)
    chip.set_ts_hysteresis(0.032, 0.004).expect("set_ts_hysteresis");   // Set ts hysteresis, (low_to_normal=0.032 V, high_to_normal=0.004 V) → ()
                                                                        // TS_HYS_L2H (16 mV steps) and TS_HYS_H2L (4 mV steps)
    chip.configure_jeita(false, None, None, true, false, 0, 1).expect("configure_jeita"); // Configure jeita, (enabled, cool_voltage=None, warm_voltage=None, cool_half_current=True, warm_half_current=False, cool_voltage_drop=0, warm_voltage_drop=1) → ()
                                                                        // JEITA profile; thresholds are left unchanged when None
    let (enabled, volts) = chip.get_rail("aldo1").expect("get_rail");   // Get rail, (name) → (bool, f32 V)
                                                                        // enable bit and programmed voltage of one rail
    println!("rail aldo1: enabled={} volts={}", enabled, volts);
    chip.set_rail_voltage("aldo1", volts).expect("set_rail_voltage");   // Set rail voltage, (name, volts) → () V
                                                                        // rounds to the nearest step inside the rail range; raises outside it
    chip.set_rail_enabled("aldo1", enabled).expect("set_rail_enabled"); // Set rail enabled, (name, enabled) → ()
                                                                        // enable bit in DCDC_EN / LDO_EN0 / LDO_EN1
    chip.configure_dcdc("dcdc1", false, None).expect("configure_dcdc"); // Configure dcdc, (name, always_pwm=False, dvm=None) → ()
                                                                        // forced-PWM bit per DCDC; DVM only for DCDC2 / DCDC3
    chip.configure_dcdc_global(false, false, false, false, 60).expect("configure_dcdc_global"); // Configure dcdc global, (force_ccm=False, slow_ramp=False, frequency_spread=False, spread_100khz=False, uvp_debounce_us=60 µs) → ()
                                                                        // DCDC_EN bits 6 / 5 and DCDC_PWM bits 7:6, 1:0
    chip.set_off_discharge(true).expect("set_off_discharge");           // Set off discharge, (enabled) → ()
                                                                        // COMMON_CONFIG bit 5: discharge outputs when off
    chip.configure_sleep(false, true, false).expect("configure_sleep"); // Configure sleep, (wake_on_irq=False, pwrok_low=True, restore_voltages=False) → ()
                                                                        // SLEEP_CTRL bits 4, 3, 2
    chip.configure_power_key(512, 6.0, 1.5, true, false, false).expect("configure_power_key"); // Configure power key, (on_ms=512, off_s=6, irq_s=1.5, long_press_off=True, restart_instead=False, force_off_16s=False) → ()
                                                                        // power-key timing and the actions of a long press
    chip.configure_power_off(true, 2.6, true, 0x0F).expect("configure_power_off"); // Configure power off, (die_over_temp=True, off_voltage=2.6 V, dcdc_ovp=True, dcdc_uvp=0x0F) → ()
                                                                        // power-off sources: die over-temperature, VOFF threshold, DCDC OVP / UVP
    chip.configure_pwrok(true, 8, true, false, false).expect("configure_pwrok"); // Configure pwrok, (check=True, delay_ms=8 ms, off_delay=True, reverse_off_sequence=False, restart_on_low=False) → ()
                                                                        // PWROK_CTRL delay and sequencing
    chip.set_batfet_off_enable(false).expect("set_batfet_off_enable");  // Set batfet off enable, (enabled) → ()
                                                                        // BATFET_CTRL bit 3: BATFET on when powered off on battery only
    chip.configure_fast_poweron(false, false, &[]).expect("configure_fast_poweron"); // Configure fast poweron, (poweron, wakeup, sequence=None) → ()
                                                                        // FAST_PWRON enables; the per-rail start sequence is left untouched
    chip.configure_watchdog(false, 64, "irq").expect("configure_watchdog"); // Configure watchdog, (enabled, timeout_s=64 s, action='irq') → ()
                                                                        // watchdog disabled here; timeout and action are still programmed
    chip.feed_watchdog().expect("feed_watchdog");                       // Feed watchdog, () → ()
                                                                        // WATCHDOG_CTRL bit 3: clears the watchdog counter
    chip.configure_battery_warning(10, 1).expect("configure_battery_warning"); // Configure battery warning, (level2=10 %, level1=1 %) → ()
                                                                        // LOWBAT_WARN: warning levels 2 (5–20 %) and 1 (0–15 %)
    chip.set_gauge_enabled(true).expect("set_gauge_enabled");           // Set gauge enabled, (enabled) → ()
                                                                        // MODULE_ENABLE bit 3: fuel gauge
    chip.reset_gauge(false).expect("reset_gauge");                      // Reset gauge, (full=False) → ()
                                                                        // GAUGE_RESET bit 3 (bit 2 for the deeper reset)
    chip.set_gpio1(false).expect("set_gpio1");                          // Set gpio1, (low) → ()
                                                                        // GPIO1 open-drain output: False = Hi-Z
    chip.write_data_buffer(0, 0x5A).expect("write_data_buffer");        // Write data buffer, (index 0–3, value 0–255) → ()
                                                                        // stores a byte that survives soft restarts
    let stored = chip.read_data_buffer(0).expect("read_data_buffer");   // Read data buffer, (index 0–3) → u32
                                                                        // reads the byte back
    println!("read data buffer: {:?}", stored);
    chip.enable_interrupt(SOURCE_VBUS_INSERT).expect("enable_interrupt"); // Enable interrupt, (source) → ()
                                                                        // sets the source bits in IRQ_EN0..2
    chip.disable_interrupt(SOURCE_VBUS_INSERT).expect("disable_interrupt"); // Disable interrupt, (source) → ()
                                                                        // clears the source bits in IRQ_EN0..2
    let irq = chip.poll_interrupt().expect("poll_interrupt");           // Poll interrupt, () → u32
                                                                        // reads IRQ_STATUS0..2, clears the bits seen and returns the 24-bit status
    println!("poll interrupt: {:?}", irq);

    // Destructive commands stay disabled; set ENABLE_DANGEROUS only on a bench supply.
    const ENABLE_DANGEROUS: bool = false;
    if ENABLE_DANGEROUS {
        chip.power_off().expect("power_off");                           // Power off, () → ()
                                                                        // COMMON_CONFIG bit 0: soft power-off, cuts every output except RTCLDO
        chip.restart().expect("restart");                               // Restart, () → ()
                                                                        // COMMON_CONFIG bit 1: restarts the system
        chip.sleep().expect("sleep");                                   // Sleep, () → ()
                                                                        // SLEEP_CTRL bit 0: enter sleep
        chip.wakeup().expect("wakeup");                                 // Wakeup, () → ()
                                                                        // SLEEP_CTRL bit 1: wake up
    }
}
