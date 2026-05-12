use embedded_hal::spi::{Operation, SpiDevice};
use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

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

// Dummy CS — spidev manages CS via the kernel; no GPIO needed.
struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn main() {
    let spi_bus    = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0u32);
    let spi_device = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0u32);
    let spi_speed  = std::env::var("SPI_SPEED").ok().and_then(|v| v.parse().ok()).unwrap_or(1_000_000u32);

    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device))
        .expect("open spidev");
    spi.configure(
        &SpidevOptions::new()
            .max_speed_hz(spi_speed)
            .mode(SpiModeFlags::SPI_MODE_0)
            .build(),
    )
    .expect("configure spidev");

    // SpidevBus wraps Spidev and implements embedded-hal SpiBus.
    let bus = SpidevBus(spi);
    let mut device = ExclusiveDevice::new_no_delay(bus, NullCs).expect("create SpiDevice");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(device.write(&[0x00]).is_ok(), "write_ok", passed, failed);

    let mut buf = [0u8; 1];
    check_true!(device.read(&mut buf).is_ok(), "read_ok", passed, failed);

    let mut buf = [0u8; 1];
    check_true!(
        device
            .transaction(&mut [Operation::Write(&[0x00]), Operation::Read(&mut buf)])
            .is_ok(),
        "write_read_ok",
        passed,
        failed
    );

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
