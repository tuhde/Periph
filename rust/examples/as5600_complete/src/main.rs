use linux_embedded_hal::I2cdev;
use periph::chips::magnetometer::As5600Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x36);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = As5600Full::new(dev, addr).expect("init AS5600");

    // --- Status and magnet checks ---
    println!("{}", chip.is_magnet_detected().unwrap());    // Check magnet present, () → bool
    println!("{}", chip.is_magnet_too_strong().unwrap());  // Check magnet too strong, () → bool
    println!("{}", chip.is_magnet_too_weak().unwrap());    // Check magnet too weak, () → bool
    println!("0x{:02X}", chip.status_byte().unwrap());     // Read raw status, () → u8

    // --- Angle readings ---
    println!("{}", chip.angle().unwrap());                 // Read absolute angle, () → f32 degrees
    println!("{}", chip.angle_raw().unwrap());             // Read scaled angle count, () → u16 0-4095
    println!("{}", chip.raw_angle().unwrap());             // Read raw unscaled angle, () → u16 0-4095
    println!("{}", chip.raw_angle_degrees().unwrap());     // Read raw angle in degrees, () → f32 degrees

    // --- Diagnostics ---
    println!("{}", chip.agc().unwrap());                   // Read AGC value, () → u8
    println!("{}", chip.magnitude().unwrap());             // Read CORDIC magnitude, () → u16

    // --- Position configuration (volatile) ---
    println!("{}", chip.zero_position().unwrap());         // Read ZPOS, () → u16 0-4095
    println!("{}", chip.max_position().unwrap());          // Read MPOS, () → u16 0-4095
    println!("{}", chip.max_angle().unwrap());             // Read MANG, () → u16 0-4095

    chip.set_zero_position(0).unwrap();                    // Set zero position, (pos 0-4095) → None
    chip.set_max_position(4095).unwrap();                  // Set max position, (pos 0-4095) → None
    chip.set_max_angle(2048).unwrap();                     // Set max angle span, (span 0-4095) → None

    // --- Configure power mode and output ---
    chip.configure(0, 0, 0, 0, 0, 0, false).unwrap();     // Configure chip, (pm 0-3, hyst 0-3, outs 0-2, pwmf 0-3, sf 0-3, fth 0-7, wd bool) → None

    // --- Burn count ---
    println!("{}", chip.burn_count().unwrap());            // Read burn count, () → u8 0-3
}
