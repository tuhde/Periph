use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::power::lc709203f::DIRECTION_AUTO;
use periph::chips::power::Lc709203fFull;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x0B);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Lc709203fFull::new(dev, addr, 1000, 1, &mut delay).expect("init LC709203F"); // Create LC709203F driver, (i2c, addr, capacity_mah, profile, delay)

    println!("{}", chip.voltage().unwrap());                            // Cell voltage, () → f32 V
    println!("{}", chip.percent().unwrap());                            // State of charge (ITE), () → f32 %
    println!("{}", chip.temperature().unwrap());                        // Cell temperature, () → f32 °C
    chip.set_temperature(25.0).unwrap();                                // Host temperature (I²C mode), (celsius) clamped to -20...+60 °C
    println!("{}", chip.rsoc().unwrap());                               // RSOC register, () → u8 %
    chip.set_rsoc(50).unwrap();                                         // Force RSOC, (percent)
    chip.init_rsoc(&mut delay).unwrap();                                // Initial RSOC from present voltage, (delay)
    chip.before_rsoc(&mut delay).unwrap();                              // RSOC from max voltage since reset, (delay)
    chip.use_thermistor(3380).unwrap();                                 // Thermistor mode, (b_constant)
    println!("{}", chip.is_thermistor_mode().unwrap());                 // Temperature source, () → bool
    println!("{}", chip.get_b_constant().unwrap());                     // Thermistor B constant, () → u16 K
    chip.use_i2c_temperature(25.0).unwrap();                            // Back to I²C temperature mode, (celsius)
    chip.set_apa(0x19).unwrap();                                        // Adjustment Pack Application, (value)
    println!("{}", chip.get_apa().unwrap());                            // () → u8
    chip.set_apt(0x1E).unwrap();                                        // Adjustment Pack Thermistor, (value)
    println!("{}", chip.get_apt().unwrap());                            // () → u16
    chip.set_current_direction(DIRECTION_AUTO).unwrap();                // Current direction, (mode) AUTO / CHARGE / DISCHARGE
    println!("{}", chip.get_current_direction().unwrap());              // () → u16
    chip.set_profile(1, &mut delay).unwrap();                           // Battery profile 0/1, (profile, delay) — re-initializes the chip (90 ms)
    println!("{}", chip.get_profile().unwrap());                        // () → u8
    println!("{:#06x}", chip.profile_id().unwrap());                    // Number of the parameter, () → u16
    println!("{:#06x}", chip.ic_version().unwrap());                    // IC version, () → u16
    chip.set_alarm_rsoc(20).unwrap();                                   // Low-RSOC alarm, (percent) 0 = off
    println!("{}", chip.get_alarm_rsoc().unwrap());                     // () → u8 %
    chip.set_alarm_voltage(3.5).unwrap();                               // Low-voltage alarm, (volts) 0 = off
    println!("{}", chip.get_alarm_voltage().unwrap());                  // () → f32 V
    println!("{}", chip.poll_interrupt().unwrap());                     // Alarm bitmask, () → u8 (ALARM_RSOC = 1, ALARM_VOLTAGE = 2)
    chip.sleep().unwrap();                                              // Sleep mode, ()
    chip.wake(&mut delay);                                              // Wake up, (delay)
    println!("{}", chip.is_sleeping(&mut delay).unwrap());              // Power mode, (delay) → bool
}
