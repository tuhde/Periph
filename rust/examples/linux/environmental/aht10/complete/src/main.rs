use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::environmental::Aht10Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Aht10Full::new(dev, addr, &mut delay).expect("init AHT10");      // Create AHT10 driver, (i2c, addr=0x38, delay) → Result

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

    let (raw_rh, raw_t) = chip.read_raw(&mut delay).unwrap();                       // Read raw 20-bit values, (delay) → Result<(u32, u32)>
                                                                                    // same measurement as read(), undecoded
    println!("raw RH: {}  raw T: {}", raw_rh, raw_t);
    println!("Status: 0x{:02X}", chip.read_status().unwrap());                      // Read status byte, () → Result<u8>
                                                                                    // bit 7 BUSY, bits 6:5 MODE, bit 3 CAL
    println!("Dew point: {:.2} C", chip.dew_point(&mut delay).unwrap());            // Dew point, (delay) → Result<f32 °C>
                                                                                    // one fresh measurement, Magnus formula

    chip.soft_reset(&mut delay).unwrap();                                           // Send soft reset command, (delay) → Result<()>
                                                                                    // sends 0xBA, waits 20 ms, re-runs the init command
}
