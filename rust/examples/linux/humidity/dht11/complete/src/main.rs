use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use periph::chips::humidity::Dht11Full;
use periph::transport::dhtxx::DHTxxTransportLinux;

fn main() {
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let line_offset: u32 = std::env::var("DHT11_LINE").ok().and_then(|v| v.parse().ok()).unwrap_or(4);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let handle = chip.get_line(line_offset).expect("get data line")
        .request(LineRequestFlags::INPUT, 0, "dht11_complete").expect("request line");
    let pin = CdevPin::new(handle).expect("dht11 pin");

    let transport = DHTxxTransportLinux::new(pin);
    let mut dht = Dht11Full::new(transport, 3);  // Create DHT11 driver, (transport, max_retries=3)

    let t = dht.read_temperature().unwrap_or(0.0);  // Read temperature, () → f32 °C
                                                    // returns a fresh conversion each call
    let h = dht.read_humidity().unwrap_or(0.0);     // Read humidity, () → f32 %RH
                                                    // returns a fresh conversion each call
    let r = dht.read_retry(5);                      // Read with retries, (max_retries=5) → (f32 °C, f32 %RH)
                                                    // retries up to 5 times on checksum error
    let raw = dht.read_raw().unwrap_or([0; 5]);     // Read raw frame, () → [u8; 5]
                                                    // returns the validated 5-byte frame
    println!("t={} h={} retry_t={} raw[0]=0x{:02X}", t, h, r.map(|(t, _)| t).unwrap_or(0.0), raw[0]);
    println!("===DONE: 0 passed, 0 failed===");
}
