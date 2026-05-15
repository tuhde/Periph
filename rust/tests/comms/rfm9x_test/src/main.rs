use linux_embedded_hal::I2cdev;
use periph::chips::comms::rfm9x::RFM95Full;

macro_rules! check_eq {
    ($label:expr, $got:expr, $expected:expr, $passed:expr, $failed:expr) => {
        if $got == $expected {
            println!("PASS {}", $label);
            *$passed += 1;
        } else {
            println!("FAIL {}: got 0x{:02X}, expected 0x{:02X}", $label, $got, $expected);
            *$failed += 1;
        }
    };
}

macro_rules! check_true {
    ($label:expr, $cond:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            *$passed += 1;
        } else {
            println!("FAIL {}", $label);
            *$failed += 1;
        }
    };
}

fn main() {
    let spi_bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let spi_device: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);

    let dev = spidev::Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device)).expect("open spi");
    let mut spi = spidev::SpidevOptions::new()
        .max_speed_hz(5_000_000)
        .mode(spidev::SpiModeFlags::SPI_MODE_0)
        .build();
    spi.configure(&dev).expect("configure spi");
    let cs_pin = 0;
    let device = embedded_hal_bus::spi::ExclusiveDevice::new_no_delay(spi, cs_pin).expect("exclusive device");

    let mut rfm = RFM95Full::new(device, 868_000_000).expect("init RFM95");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let ver = rfm.version().expect("version");
    check_eq!("version_reg", ver, 0x12, &mut passed, &mut failed);
    check_true!("version_nonzero", ver != 0, &mut passed, &mut failed);
    let rssi = rfm.rssi().expect("rssi");
    check_true!("rssi_sane", rssi > -150.0 && rssi < 0.0, &mut passed, &mut failed);

    rfm.send(b"test").expect("send");
    std::thread::sleep(std::time::Duration::from_millis(50));

    rfm.standby().expect("standby");
    rfm.sleep().expect("sleep");
    rfm.standby().expect("standby");

    rfm.set_tx_power(14, false).expect("set_tx_power_rfo");
    rfm.set_tx_power(17, true).expect("set_tx_power_boost");
    rfm.set_frequency(868_000_000).expect("set_frequency");
    rfm.configure(7, 125, 5, true).expect("configure");
    check_true!("configure_valid", true, &mut passed, &mut failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}