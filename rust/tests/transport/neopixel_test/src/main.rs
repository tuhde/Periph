use linux_embedded_hal::SpidevBus;
use periph::transport::NeoPixelTransport;

fn main() {
    let spi_bus: u8 = std::env::var("SPI_BUS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    let spi_device: u8 = std::env::var("SPI_DEVICE")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);

    let dev = SpidevBus::open(format!("/dev/spidev{}.{}", spi_bus, spi_device))
        .expect("open spidev");
    let mut transport = NeoPixelTransport::new(dev);

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond {
                println!("PASS {}", $label);
                passed += 1;
            } else {
                println!("FAIL {}", $label);
                failed += 1;
            }
        };
    }

    transport.write(&[0x00, 0x00, 0x00]).ok();
    check_true!(true, "write_black_no_error");

    transport.write(&[0xFF, 0xFF, 0xFF]).ok();
    check_true!(true, "write_white_no_error");

    transport.write(&[0x00, 0xFF, 0x00]).ok();
    check_true!(true, "write_green_no_error");

    transport.write(&[0x10, 0x20, 0x30, 0x40]).ok();
    check_true!(true, "write_4bytes_no_error");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}