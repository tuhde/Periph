//! Hardware smoke test of the UC1611s driver on a Linux host (spidev + gpio-cdev).
//!
//! The picture itself is verified by eye (see the demo example).
//! Wire D/C# and RES# to the GPIO lines below (env overrides); the chip has no BUSY line.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::uc1611s::{Error, Uc1611sSpiFull};
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
            .max_speed_hz(4_000_000)
            .mode(SpiModeFlags::SPI_MODE_0)
            .build(),
    )
    .expect("configure spidev");
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).expect("spi device");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay, WireMode::Simplex)
        .expect("new connection");
    let mut chip = Uc1611sSpiFull::new_spi(connection, 240, 128, false, 0x8F).unwrap();
    check_true!(true, "init_valid", passed, failed);
    check_true!(chip.width() == 240 && chip.height() == 128, "geometry_240x128", passed, failed);

    chip.clear();
    chip.draw_rect(0, 0, 240, 128, true, false);
    chip.draw_line(0, 0, 239, 127, true);
    chip.draw_rect(20, 20, 40, 30, true, true);
    let mut ok = chip.show().is_ok() && chip.ok();
    check_true!(ok, "show_accepted", passed, failed);
    ok = chip.show_page(3).is_ok()
        && chip.set_contrast(0x70).is_ok()
        && chip.set_contrast(0x8F).is_ok()
        && chip.set_invert(true).is_ok()
        && chip.set_invert(false).is_ok()
        && chip.set_all_on(true).is_ok()
        && chip.set_all_on(false).is_ok()
        && chip.set_flip(false, true).is_ok()
        && chip.set_flip(true, false).is_ok()
        && chip.set_fixed_lines(2).is_ok()
        && chip.set_scroll_line(8).is_ok()
        && chip.set_scroll_line(0).is_ok()
        && chip.set_fixed_lines(0).is_ok()
        && chip.set_bias_ratio(2).is_ok()
        && chip.set_temperature_compensation(1).is_ok()
        && chip.set_line_rate(3).is_ok()
        && chip.set_pump(true).is_ok()
        && chip.set_panel_loading(true).is_ok()
        && chip.set_nline_inversion(0, false).is_ok()
        && chip.set_isolation_clock(1, 0).is_ok()
        && chip.set_partial_display(true, 32, Some(95)).is_ok()
        && chip.set_partial_display(false, 0, None).is_ok()
        && chip.show().is_ok()
        && chip.set_display_pattern(1).is_ok()
        && chip.set_display_pattern(0).is_ok();
    check_true!(ok, "commands_accepted", passed, failed);
    ok = chip.sleep().is_ok() && chip.wake().is_ok() && chip.reset().is_ok() && chip.show().is_ok();
    check_true!(ok, "sleep_wake_reset_accepted", passed, failed);

    let mut buf = [0u8; 4];
    check_true!(matches!(chip.read_status(), Err(Error::Unsupported)), "read_status_unsupported", passed, failed);
    check_true!(matches!(chip.read_ram(0, 0, &mut buf), Err(Error::Unsupported)), "read_ram_unsupported", passed, failed);
    check_true!(chip.retry_count() == 0 && chip.error_count() == 0, "no_retries_or_errors", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
