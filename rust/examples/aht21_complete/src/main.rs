use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::environmental::Aht21Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Aht21Full::new(dev, addr, &mut delay).expect("init AHT21");      // Create AHT21 driver, (i2c, addr=0x38, delay) → Result

    println!("is_calibrated: {}", chip.is_calibrated().unwrap());                   // Check calibration status, () → Result<bool>
                                                                                    // reads CAL bit from status byte
    println!("is_busy: {}", chip.is_busy().unwrap());                               // Check busy status, () → Result<bool>
                                                                                    // reads BUSY bit from status byte

    let (t, h) = chip.read(&mut delay).unwrap();                                    // Trigger measurement, (delay) → Result<(f32 °C, f32 %RH)>
                                                                                    // sends 0xAC trigger, waits 80 ms, decodes 6 bytes
    println!("temperature: {:.2} C", t);
    println!("humidity: {:.2} %RH", h);

    println!("read_temperature: {:.2} C", chip.read_temperature(&mut delay).unwrap()); // Read temperature only, (delay) → Result<f32 °C>
                                                                                       // triggers full measurement, returns temperature_c
    println!("read_humidity: {:.2} %RH", chip.read_humidity(&mut delay).unwrap());     // Read humidity only, (delay) → Result<f32 %RH>
                                                                                       // triggers full measurement, returns humidity_pct

    let (tc, hc, crc_ok) = chip.read_with_crc(&mut delay).unwrap();                 // Read with CRC verification, (delay) → Result<(f32 °C, f32 %RH, bool)>
                                                                                    // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    println!("T: {:.2} C  H: {:.2} %RH  CRC: {}", tc, hc, crc_ok);

    chip.soft_reset(&mut delay).unwrap();                                           // Send soft reset command, (delay) → Result<()>
                                                                                    // sends 0xBA, waits 20 ms for recovery
}
