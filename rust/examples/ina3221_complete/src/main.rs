use linux_embedded_hal::I2cdev;
use periph::chips::power::Ina3221Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina3221Full::new(dev, addr, 0.1);  // Create INA3221 driver, (i2c, addr, r_shunt=0.1 Ω)

    println!("manufacturer_id: 0x{:04X}", chip.manufacturer_id().expect("manufacturer_id"));  // Read Manufacturer ID, () → u16 0x5449
    println!("die_id: 0x{:04X}", chip.die_id().expect("die_id"));                              // Read Die ID, () → u16 0x3220

    for ch in 1..=3 {
        println!("ch{} voltage: {:.3}V", ch, chip.voltage(ch).expect("voltage"));              // Read bus voltage, (channel) → f32 V
        println!("ch{} shunt: {:.6}V", ch, chip.shunt_voltage(ch).expect("shunt_voltage"));        // Read shunt voltage, (channel) → f32 V
        println!("ch{} current: {:.4}A", ch, chip.current(ch).expect("current"));                   // Read load current, (channel) → f32 A
        println!("ch{} power: {:.4}W", ch, chip.power(ch).expect("power"));                        // Read power, (channel) → f32 W
    }

    println!("conversion_ready: {:?}", chip.conversion_ready().expect("conversion_ready"));  // Check conversion done, () → bool

    chip.configure(4, 4, 4, 7).expect("configure");  // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → ()

    chip.enable_channel(1, true).expect("enable_channel");                               // Enable channel, (channel, enabled) → ()
    let ena = chip.channel_enabled(1).expect("channel_enabled");                          // Read channel enabled, (channel) → bool

    chip.set_critical_alert(1, 0.1, false).expect("set_critical_alert");                 // Set critical alert, (channel, limit_v, latch=False) → ()
    chip.set_warning_alert(2, 0.05, false).expect("set_warning_alert");                  // Set warning alert, (channel, limit_v, latch=False) → ()

    let flags = chip.alert_flags().expect("alert_flags");                                 // Read alert flags, () → u16

    chip.set_summation_channels(&[1, 2], 0.2).expect("set_summation_channels");        // Set summation channels, (channels, limit_v) → ()
    let sv_sum = chip.summation_value().expect("summation_value");                      // Read summation value, () → f32 V

    chip.set_power_valid_limits(5.5, 4.5).expect("set_power_valid_limits");            // Set PV limits, (upper_v, lower_v) → ()
    let pv = chip.power_valid().expect("power_valid");                                   // Read power valid, () → bool

    chip.shutdown().expect("shutdown");                 // Put chip into power-down mode, () → ()
    sleep(Duration::from_millis(1));
    chip.wake().expect("wake");                         // Restore operating mode, () → ()

    chip.reset().expect("reset");                       // Reset all registers, () → ()

    println!("===DONE===");
}