use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use periph::chips::humidity::Dht11Minimal;
use periph::connection::dhtxx::DHTxxConnectionLinux;

fn main() {
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let line_offset: u32 = std::env::var("DHT11_LINE").ok().and_then(|v| v.parse().ok()).unwrap_or(4);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let handle = chip.get_line(line_offset).expect("get data line")
        .request(LineRequestFlags::INPUT, 0, "dht11_minimal").expect("request line");
    let pin = CdevPin::new(handle).expect("dht11 pin");

    let connection = DHTxxConnectionLinux::new(pin);
    let mut dht = Dht11Minimal::new(connection);  // Create DHT11 driver, (connection)

    for _ in 0..5 {
        match dht.read() {                        // Read temperature & humidity, () → (f32 °C, f32 %RH)
            Ok((t, h)) => println!("{} C, {} %RH", t, h),
            Err(e)     => eprintln!("read error: {:?}", e),
        }
        std::thread::sleep(std::time::Duration::from_secs(2));
    }
    println!("===DONE: 0 passed, 0 failed===");
}
