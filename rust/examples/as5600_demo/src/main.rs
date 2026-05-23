use linux_embedded_hal::I2cdev;
use periph::chips::magnetometer::As5600Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x36);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = As5600Full::new(dev, addr).expect("init AS5600");

    // --- Motor feedback monitor: read angle 10 times per second ---
    // AGC monitoring detects magnet distance drift; status changes alert to magnet removal.
    // In 5 V mode, target AGC ≈ 128; in 3.3 V mode, target AGC ≈ 64.

    let mut prev_status = chip.status_byte().unwrap();

    for _n in 0..10 {
        let a = chip.angle().unwrap();                   // Read absolute angle, () → f32 degrees
        let r = chip.raw_angle().unwrap();               // Read raw unscaled angle, () → u16 0-4095
        let g = chip.agc().unwrap();                     // Read AGC value, () → u8

        // --- Check for status changes (magnet inserted/removed) ---
        let status = chip.status_byte().unwrap();
        if status != prev_status {
            if !chip.is_magnet_detected().unwrap() {
                println!("[MAGNET REMOVED] MD=0");
            } else {
                println!("[MAGNET DETECTED] MD=1  MH={}  ML={}",
                    chip.is_magnet_too_strong().unwrap(),
                    chip.is_magnet_too_weak().unwrap());
            }
            prev_status = status;
        }

        // --- AGC health check ---
        if chip.is_magnet_detected().unwrap() {
            let tag = if g < 64 || g > 192 {
                "[AGC low — magnet weak or too far]"
            } else {
                "[OK]"
            };
            println!("angle={:.2}°  raw={}  agc={}  {}", a, r, g, tag);
        }

        sleep(Duration::from_millis(100));
    }
}
