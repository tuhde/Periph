use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use linux_embedded_hal::Delay;
use periph::chips::humidity::Dht11Minimal;
use periph::transport::dht11::DHT11Transport;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let data_offset: u32 = std::env::var("DHT11_DATA").ok().and_then(|v| v.parse().ok()).unwrap_or(4);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    // --- DHT11 DATA line ---
    // The DHT11's bidirectional DATA line is requested as INPUT for reading
    // and as OUTPUT for driving; we obtain two separate handles from the
    // same physical line. On Linux this means re-requesting the line per
    // direction; the transport does not need a single combined IoPin.
    let data_in_handle  = chip.get_line(data_offset).expect("get data line")
        .request(LineRequestFlags::INPUT, 0, "dht11_minimal_in").expect("request data in");
    let data_out_handle = chip.get_line(data_offset).expect("get data line")
        .request(LineRequestFlags::OUTPUT, 0, "dht11_minimal_out").expect("request data out");
    let data_in  = CdevPin::new(data_in_handle).expect("data in pin");
    let data_out = CdevPin::new(data_out_handle).expect("data out pin");

    let transport = DHT11Transport::new(data_in, data_out);
    let mut chip = Dht11Minimal::new(transport);  // Create DHT11 driver, (transport) → Dht11Minimal
    let mut delay = Delay;

    loop {
        let (t, h) = chip.read(&mut delay).expect("read");  // Read temperature and humidity, (delay) → Result<(f32, f32)>
        println!("T={:.1} C  H={:.1} %RH", t, h);
        sleep(Duration::from_secs(2));
    }
}
