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
        .request(LineRequestFlags::INPUT, 0, "dht11_demo_in").expect("request data in");
    let data_out_handle = chip.get_line(data_offset).expect("get data line")
        .request(LineRequestFlags::OUTPUT, 0, "dht11_demo_out").expect("request data out");
    let data_in  = CdevPin::new(data_in_handle).expect("data in pin");
    let data_out = CdevPin::new(data_out_handle).expect("data out pin");

    let transport = DHT11Transport::new(data_in, data_out);
    let mut chip = Dht11Full::new(transport);  // Create DHT11 driver, (transport) → Dht11Full
    let mut delay = Delay;

    // --- Indoor comfort monitor ---
    // Poll the sensor every 5 seconds and print a one-line status with a
    // comfort assessment. read_retry() recovers from occasional checksum
    // errors caused by timing jitter on Linux userspace bit-bang.
    loop {
        // --- Sample temperature and humidity with retry ---
        let result = chip.read_retry(&mut delay, 3);                   // Read with retry, (delay, max_retries=3) → Result<(f32, f32)>

        // --- Classify comfort zone ---
        if let Ok((t, h)) = result {
            let comfort = if h < 30.0 { "dry" } else if h <= 60.0 { "comfortable" } else { "humid" };
            println!("T={:.1} C  H={:.1} %RH  ({})", t, h, comfort);
        } else {
            println!("read failed after 3 attempts");
        }
        sleep(Duration::from_secs(5));
    }
}
