use linux_embedded_hal::I2cdev;
use periph::chips::power::Ina219Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina219Full::new(dev, addr, 0.1, 2.0).expect("init INA219");

    println!("voltage: {:.3} V", chip.voltage().unwrap());          // Read bus voltage, () → f32 V
    println!("shunt_voltage: {:.6} V", chip.shunt_voltage().unwrap()); // Read shunt voltage, () → f32 V
    println!("current: {:.6} A", chip.current().unwrap());         // Read load current, () → f32 A
    println!("power: {:.6} W", chip.power().unwrap());             // Read power, () → f32 W
    println!("conversion_ready: {}", chip.conversion_ready().unwrap()); // Check conversion done, () → bool
    println!("overflow: {}", chip.overflow().unwrap());             // Check math overflow, () → bool

    chip.configure(1, 3, 0x03, 0x03, 7).unwrap();
                                                                  // Configure ADC, (brng 0–1, pga 0–3, badc 0x0F, sadc 0x0F, mode 0–7) → ()

    chip.shutdown().unwrap();           // Put chip into power-down mode, () → ()
    chip.wake().unwrap();              // Restore previous operating mode, () → ()

    chip.reset().unwrap();             // Reset all registers and re-write calibration, () → ()
}