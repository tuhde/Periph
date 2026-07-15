use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use linux_embedded_hal::Delay;
use periph::chips::humidity::Dht11Full;
use periph::transport::dht11::DHT11Transport;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

fn main() {
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let data_offset: u32 = std::env::var("DHT11_DATA").ok().and_then(|v| v.parse().ok()).unwrap_or(4);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let data_in_handle  = chip.get_line(data_offset).expect("get data line")
        .request(LineRequestFlags::INPUT, 0, "dht11_test_in").expect("request data in");
    let data_out_handle = chip.get_line(data_offset).expect("get data line")
        .request(LineRequestFlags::OUTPUT, 0, "dht11_test_out").expect("request data out");
    let data_in  = CdevPin::new(data_in_handle).expect("data in pin");
    let data_out = CdevPin::new(data_out_handle).expect("data out pin");

    let transport = DHT11Transport::new(data_in, data_out);
    let mut chip  = Dht11Full::new(transport);
    let mut delay = Delay;

    let result = chip.read_retry(&mut delay, 3).expect("read_retry");
    check_true!(result.0 >= -20.0 && result.0 <= 60.0, "read_retry temperature in [-20, 60]", passed, failed);
    check_true!(result.1 >=   0.0 && result.1 <= 100.0, "read_retry humidity in [0, 100]", passed, failed);

    let raw = chip.read_raw(&mut delay).expect("read_raw");
    check_true!(raw.len() == 5, "read_raw length is 5", passed, failed);
    let checksum = raw[0].wrapping_add(raw[1]).wrapping_add(raw[2]).wrapping_add(raw[3]);
    check_true!(checksum == raw[4], "read_raw checksum OK", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
