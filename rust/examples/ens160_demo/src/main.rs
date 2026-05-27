use linux_embedded_hal::I2cdev;
use periph::chips::gas::Ens160Full;

fn aqi_label(aqi: u8) -> &'static str {
    match aqi {
        1 => "Excellent",
        2 => "Good",
        3 => "Moderate",
        4 => "Poor",
        5 => "Unhealthy",
        _ => "Unknown",
    }
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x52);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Ens160Full::new(dev, addr).expect("init ENS160"); // Create ENS160 driver, (i2c, addr=0x52)

    // --- Wait for sensor warm-up ---
    // The ENS160 requires ~3 minutes after power-on or idle before VALIDITY_FLAG
    // reaches 0. During warm-up, readings are unreliable. The driver surfaces the
    // status so the application can display progress to the user.
    println!("Waiting for sensor warm-up...");
    while sensor.status().unwrap() != 0 {                // Poll validity, () → u8 0–3
        let s = sensor.status().unwrap();
        if s == 1 {
            println!("Warm-up in progress...");
        } else if s == 2 {
            println!("Initial start-up (first power-on, up to 1 hour)...");
        } else {
            println!("No valid output");
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
    println!("Sensor ready!");

    // --- Set compensation from external sensor ---
    // If an external temperature/humidity sensor is available, feeding its readings
    // to the ENS160 improves accuracy outside the 20-80%RH range. Here we use a
    // fixed 22C/45%RH as an example.
    sensor.set_compensation(22.0, 45.0).expect("set comp");  // Set compensation, (temp_celsius=22.0, rh_percent=45.0) → ()

    // --- Indoor air quality monitoring loop ---
    // Reads AQI, TVOC, and eCO2 every second and prints a human-readable label.
    // AQI 1-2 is acceptable for occupied spaces; AQI 3+ suggests ventilation.
    for n in 0..60 {
        let (aqi, tvoc_ppb, eco2_ppm) = sensor.read_air_quality().expect("read air quality");  // Read air quality, () → (u8, f32, f32)
        let label = aqi_label(aqi);
        println!("{}s: AQI={} ({}) TVOC={} ppb eCO2={} ppm", n, aqi, label, tvoc_ppb, eco2_ppm);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}
