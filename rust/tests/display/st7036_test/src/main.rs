//! Hardware smoke test of the ST7036 driver on a Linux host (spidev + gpio-cdev).
//!
//! The picture itself is verified by eye (see the demo example).
//! Wire D/C# and RES# to the GPIO lines below (env overrides); the chip has no BUSY line.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::st7036::{St7036Full, Supply};
use periph::connection::display::{DisplayConnection, NoPin, WireMode};
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

// Dummy CS: spidev manages CS# in the kernel.
struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
    fn set_high(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
}

fn env_u32(name: &str, default: u32) -> u32 {
    std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

fn main() {
    let chip_path = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let spi_bus = env_u32("SPI_BUS", 0);
    let spi_device = env_u32("SPI_DEVICE", 0);
    let dc_offset = env_u32("DISPLAY_DC", 25);
    let res_offset = env_u32("DISPLAY_RES", 24);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let dc = CdevPin::new(
        chip.get_line(dc_offset).expect("dc line")
            .request(LineRequestFlags::OUTPUT, 0, "display_test").expect("request dc"),
    ).expect("dc pin");
    let res = CdevPin::new(
        chip.get_line(res_offset).expect("res line")
            .request(LineRequestFlags::OUTPUT, 1, "display_test").expect("request res"),
    ).expect("res pin");

    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device)).expect("open spidev");
    spi.configure(
        &SpidevOptions::new()
            .max_speed_hz(1_000_000)
            .mode(SpiModeFlags::SPI_MODE_0)
            .build(),
    )
    .expect("configure spidev");
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).expect("spi device");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay, WireMode::Simplex)
        .expect("new connection");
    let mut chip = St7036Full::new(connection, 2, None, Supply::V3_3, None)
        .expect("init");
    check_true!(true, "init_valid", passed, failed);
    check_true!(chip.columns() == 16 && chip.rows() == 2, "geometry_16x2", passed, failed);

    let mut ok = chip.clear().is_ok()
        && chip.print_at(0, 0, "Periph ST7036").is_ok()
        && chip.print_at(0, 1, "smoke test").is_ok();
    check_true!(ok, "text_accepted", passed, failed);
    ok = chip.create_char(0, &[0x1F, 0x11, 0x15, 0x11, 0x1F, 0x00, 0x1F, 0x00]).is_ok()
        && chip.set_cursor(15, 1).is_ok()
        && chip.write_char(0).is_ok()
        && chip.cursor(true, true).is_ok()
        && chip.cursor(false, false).is_ok()
        && chip.set_entry_mode(true, false).is_ok()
        && chip.shift_display(true).is_ok()
        && chip.shift_display(false).is_ok()
        && chip.shift_cursor(true).is_ok()
        && chip.return_home().is_ok()
        && chip.set_ddram_address(0x40).is_ok()
        && chip.set_contrast(40).is_ok()
        && chip.set_booster(true).is_ok()
        && chip.set_follower(true, 5).is_ok()
        && chip.set_bias(false).is_ok()
        && chip.set_contrast(24).is_ok()
        && chip.display(false).is_ok()
        && chip.display(true).is_ok()
        && chip.home().is_ok();
    check_true!(ok, "commands_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
