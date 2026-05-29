use linux_embedded_hal::I2cdev;
use periph::chips::gas::Ens160Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x52);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Ens160Minimal::new(dev, addr).expect("init ENS160"); // Create ENS160 driver, (i2c, addr=0x52)

    println!("Waiting for sensor warm-up...");
    loop {                                                    // Wait for valid data, () → blocks until warm
        let status = sensor.wait_for_new_data(2000).unwrap();
        if (status >> 2) & 0x03 == 0 { break; }
    }

    for _ in 0..10 {
        let (aqi, tvoc_ppb, eco2_ppm) = sensor.read_air_quality().expect("read air quality");  // Read air quality, () → (u8, f32, f32)
        println!("AQI={} TVOC={} ppb eCO2={} ppm", aqi, tvoc_ppb, eco2_ppm);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}
