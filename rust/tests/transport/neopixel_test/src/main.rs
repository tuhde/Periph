use linux_embedded_hal::Spidev;
use periph::transport::neopixel::NeoPixelTransport;

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
    let spi_bus: u8 = std::env::var("SPI_BUS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    let spi_device: u8 = std::env::var("SPI_DEVICE")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);

    let dev = Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device))
        .expect("open spidev");
    let mut transport = NeoPixelTransport::new(dev);

    let data = [0xFFu8, 0x00, 0x00];
    transport.write(&data).expect("write");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(true, "write_accepted_data", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}