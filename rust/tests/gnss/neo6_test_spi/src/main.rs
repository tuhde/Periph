use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::gnss::{Neo6Minimal, SpiBus};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
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

// Requires a NEO-6 module wired to SPI (mode 0, <=200 kHz) with a clear sky
// view. Achieving an actual fix needs an outdoor antenna and can take up to
// ~26 s (cold start); this test only requires that well-typed values come
// back.
fn main() {
    let spi_bus    = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0u32);
    let spi_device = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0u32);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device)).expect("open spidev");
    spi.configure(
        &SpidevOptions::new()
            .max_speed_hz(200_000)
            .mode(SpiModeFlags::SPI_MODE_0)
            .build(),
    )
    .expect("configure spidev");

    let bus = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus, NullCs).expect("create SpiDevice");
    let mut gps = Neo6Minimal::new(SpiBus(device));

    check_true!(gps.fix() == 0, "fix_starts_at_0", passed, failed);
    check_true!(gps.latitude().is_none(), "latitude_starts_at_none", passed, failed);

    for _ in 0..3000 {
        let _ = gps.update();
    }

    check_true!(gps.fix() <= 2, "fix_is_valid_quality_code", passed, failed);
    if gps.fix() > 0 {
        let lat = gps.latitude().unwrap();
        let lon = gps.longitude().unwrap();
        check_true!(lat >= -90.0 && lat <= 90.0, "latitude_in_range_once_fixed", passed, failed);
        check_true!(lon >= -180.0 && lon <= 180.0, "longitude_in_range_once_fixed", passed, failed);
        check_true!(gps.altitude().is_some(), "altitude_populated_once_fixed", passed, failed);
    } else {
        println!("note: no fix acquired during the test window (needs sky view)");
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
