use linux_embedded_hal::I2cdev;
use periph::chips::power::Ina219Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina219Full::new(dev, addr, 0.1, 2.0).expect("init INA219");

    // --- Configure for noise-sensitive power rail monitoring ---
    // 128-sample averaging suppresses switching noise on a noisy 5 V rail;
    // continuous mode avoids re-triggering overhead between measurements.
    chip.configure(1, 3, 0x0F, 0x0F, 7).unwrap();
                                                           // Configure ADC, (brng 0–1, pga 0–3, badc 0x0F, sadc 0x0F, mode 0–7) → ()

    for _ in 0..10 {
        while !chip.conversion_ready().unwrap() {          // Check conversion done, () → bool
            sleep(Duration::from_millis(10));
        }

        let v = chip.voltage().unwrap();                   // Read bus voltage, () → f32 V
        let i = chip.current().unwrap();                   // Read load current, () → f32 A
        let p = chip.power().unwrap();                     // Read power, () → f32 W
        println!("V={:.3}V  I={:.4}A  P={:.4}W", v, i, p);

        sleep(Duration::from_secs(1));
    }
}