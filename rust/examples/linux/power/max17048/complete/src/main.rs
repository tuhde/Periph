use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::power::Max17048Full;
use periph::chips::power::max17048::{SOURCE_RESET, SOURCE_SOC_CHANGE};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x36);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Max17048Full::new(dev, addr, 1).expect("init MAX17048");     // Create MAX17048 driver, (i2c, addr, cells)

    println!("{:.3} V", chip.voltage().unwrap());                       // Cell voltage, () → Result<f32> V
    println!("{:.2} %", chip.percent().unwrap());                       // State of charge, () → Result<f32> %
    println!("0x{:04X}", chip.raw_voltage().unwrap());                  // VCELL register, () → Result<u16>
    println!("0x{:04X}", chip.raw_soc().unwrap());                      // SOC register, () → Result<u16>
    println!("{:.2} %/h", chip.charge_rate().unwrap());                 // Charge/discharge rate, () → Result<f32> %/h
    println!("0x{:04X}", chip.version().unwrap());                      // VERSION register, () → Result<u16>
    println!("0x{:02X}", chip.chip_id().unwrap());                      // Factory ID, () → Result<u8>

    chip.set_rcomp(0x97).unwrap();                                      // RCOMP, (value) → Result<()>
    println!("{}", chip.get_rcomp().unwrap());                          // () → Result<u8>
    chip.compensate_temperature(25.0).unwrap();                         // RCOMP from the temperature, (celsius) → Result<()>

    chip.set_alert_soc(10).unwrap();                                    // Empty alert threshold, (percent) → Result<()>; 1-32 %
    println!("{}", chip.get_alert_soc().unwrap());                      // () → Result<u8> %
    chip.set_soc_change_alert(true).unwrap();                           // Alert on 1 % SOC change, (enabled) → Result<()>
    chip.set_voltage_alert(3.3, 4.25).unwrap();                         // Voltage window, (min_v, max_v) → Result<()>; 0 / 5.1 V disable a limit
    println!("{:?}", chip.get_voltage_alert().unwrap());                // () → Result<(f32, f32)>
    chip.set_reset_voltage(3.0).unwrap();                               // Reset voltage, (volts) → Result<()>
    println!("{}", chip.get_reset_voltage().unwrap());                  // () → Result<f32> V
    chip.enable_voltage_reset_alert(true).unwrap();                     // Voltage reset asserts ALRT, (enabled) → Result<()>
    chip.set_comparator_disabled(false).unwrap();                       // Hibernate comparator off, (disabled) → Result<()>

    chip.set_hibernate_thresholds(26.624, 0.06).unwrap();               // Hibernate thresholds, (hib_thr_pct_per_h, act_thr_v) → Result<()>
    println!("{:?}", chip.get_hibernate_thresholds().unwrap());         // () → Result<(f32, f32)>
    chip.disable_hibernate().unwrap();                                  // Never hibernate, () → Result<()>
    chip.force_hibernate().unwrap();                                    // Always hibernate, () → Result<()>
    chip.set_hibernate_thresholds(26.624, 0.06).unwrap();               // Restore the defaults
    println!("{}", chip.is_hibernating());                              // Hibernate status, () → bool

    println!("0x{:04X}", chip.read_status().unwrap());                  // STATUS register, () → Result<u16>
    chip.clear_status(SOURCE_RESET).unwrap();                           // Clear flags and ALRT, (mask) → Result<()>
    let config = chip.read_config().unwrap();                           // CONFIG register, () → Result<u16>
    chip.write_config(config).unwrap();                                 // Write CONFIG, (value) → Result<()>

    println!("0x{:04X}", chip.poll_interrupt().unwrap());               // Alert sources, () → Result<u16>
                                                                        // SOURCE_RESET / _OVERVOLTAGE / _UNDERVOLTAGE
                                                                        // / _VRESET / _LOW_SOC / _SOC_CHANGE
    chip.enable_interrupt(SOURCE_SOC_CHANGE).unwrap();                  // Enable a source, (source) → Result<()>
    chip.disable_interrupt(SOURCE_SOC_CHANGE).unwrap();                 // Disable a source, (source) → Result<()>

    chip.enable_sleep(true).unwrap();                                   // Allow sleep mode, (enabled) → Result<()>
    chip.sleep().unwrap();                                              // Sleep, () → Result<()>
    chip.wake().unwrap();                                               // Wake up, () → Result<()>
    chip.enable_sleep(false).unwrap();

    chip.quick_start().unwrap();                                        // Restart the SOC estimate, () → Result<()>; only if the first SOC is clearly wrong
    chip.reset(&mut delay).unwrap();                                    // Power-on reset, (delay) → Result<()>; waits 1 s
}
