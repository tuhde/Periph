use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::comms::Rfm95Full;
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let dev_path = std::env::var("SPI_DEV").unwrap_or_else(|_| "/dev/spidev0.0".to_string());

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut spi = Spidev::open(dev_path).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(5_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus, NullCs).expect("spi device");

    let mut radio = Rfm95Full::new(device, 868_000_000).expect("init RFM95");

    let ver = radio.inner.inner.version().unwrap_or(0xFF);
    check_true!(ver == 0x12, "version == 0x12 (SX1276)", passed, failed);

    radio.inner.inner.configure(7, 125.0, 5).expect("configure");
    check_true!(true, "configure accepted", passed, failed);

    radio.inner.inner.set_frequency(868_000_000).expect("frequency");
    check_true!(true, "frequency_in_range", passed, failed);

    radio.inner.inner.standby().expect("standby");
    check_true!(true, "standby accepted", passed, failed);

    radio.inner.inner.send(b"test123").expect("send");
    check_true!(true, "send accepted", passed, failed);

    radio.inner.inner.sleep().expect("sleep");
    check_true!(true, "sleep accepted", passed, failed);

    radio.inner.inner.standby().expect("wake");
    check_true!(true, "wake accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
