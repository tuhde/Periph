use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use linux_embedded_hal::Delay;
use periph::chips::humidity::Dht11Full;
use periph::transport::dht11::DHT11Transport;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let data_offset: u32 = std::env::var("DHT11_DATA").ok().and_then(|v| v.parse().ok()).unwrap_or(4);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let data_in_handle  = chip.get_line(data_offset).expect("get data line")
        .request(LineRequestFlags::INPUT, 0, "dht11_complete_in").expect("request data in");
    let data_out_handle = chip.get_line(data_offset).expect("get data line")
        .request(LineRequestFlags::OUTPUT, 0, "dht11_complete_out").expect("request data out");
    let data_in  = CdevPin::new(data_in_handle).expect("data in pin");
    let data_out = CdevPin::new(data_out_handle).expect("data out pin");

    let transport = DHT11Transport::new(data_in, data_out);
    let mut chip = Dht11Full::new(transport);  // Create DHT11 driver, (transport) → Dht11Full
    let mut delay = Delay;

    loop {
        let (t, h) = chip.read_retry(&mut delay, 3).expect("read_retry");  // Read with retry, (delay, max_retries=3) → Result<(f32, f32)>
        let raw = chip.read_raw(&mut delay).expect("read_raw");           // Read raw 5-byte frame, (delay) → Result<[u8; 5]>
        println!("t={:.1} h={:.1} raw={:02X?}", t, h, raw);

        sleep(Duration::from_secs(2));
    }
}
