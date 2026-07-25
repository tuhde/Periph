use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use periph::chips::humidity::Dht11Full;
use periph::transport::dhtxx::DHTxxTransportLinux;

fn comfort(h: f32) -> &'static str {
    if h < 30.0 { "dry" }
    else if h > 60.0 { "humid" }
    else { "comfortable" }
}

fn main() {
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let line_offset: u32 = std::env::var("DHT11_LINE").ok().and_then(|v| v.parse().ok()).unwrap_or(4);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let handle = chip.get_line(line_offset).expect("get data line")
        .request(LineRequestFlags::INPUT, 0, "dht11_demo").expect("request line");
    let pin = CdevPin::new(handle).expect("dht11 pin");

    let transport = DHTxxTransportLinux::new(pin);
    let mut dht = Dht11Full::new(transport, 3);  // Create DHT11 driver, (transport, max_retries=3)

    // --- Indoor comfort monitor ---
    // Reads temperature and humidity every 5 seconds and prints a one-line
    // status with a comfort assessment. Demonstrates reliable real-world
    // polling with retry-based error recovery.
    for _ in 0..60 {
        match dht.read_retry(3) {                  // Read with retries, (max_retries=3) → (f32 °C, f32 %RH)
            Ok((t, h)) => println!("{} C, {} %RH, {}", t, h, comfort(h)),
            Err(_) => {
                // --- Handle read failure ---
                // After all retries are exhausted, log a warning and continue.
                // The next loop iteration will try again with a fresh sample.
                eprintln!("WARN: DHT11 read failed after retries");
            }
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    }
    println!("===DONE: 0 passed, 0 failed===");
}
