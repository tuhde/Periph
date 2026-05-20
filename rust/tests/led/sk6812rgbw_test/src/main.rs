use linux_embedded_hal::SpidevBus;
use periph::chips::led::{Sk6812RgbwMinimal, Sk6812RgbwFull};

macro_rules! check_true {
    ($cond:expr, $label:expr, $p:expr, $f:expr) => {
        if $cond { println!("PASS {}", $label); $p += 1; }
        else      { println!("FAIL {}", $label); $f += 1; }
    };
}

macro_rules! check_eq {
    ($got:expr, $exp:expr, $label:expr, $p:expr, $f:expr) => {
        if $got == $exp { println!("PASS {}", $label); $p += 1; }
        else { println!("FAIL {}: got {:?}, expected {:?}", $label, $got, $exp); $f += 1; }
    };
}

fn main() {
    let spi_bus: u8    = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let spi_device: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Sk6812RgbwMinimal ---
    {
        let spi = SpidevBus::open(format!("/dev/spidev{}.{}", spi_bus, spi_device))
            .expect("open spidev");
        let mut strip = Sk6812RgbwMinimal::new(spi, 8);

        check_true!(strip.fill(255, 0, 0, 0).is_ok(), "fill(255,0,0,0) accepted", passed, failed);
        check_true!(strip.fill(0, 255, 0, 0).is_ok(), "fill(0,255,0,0) accepted", passed, failed);
        check_true!(strip.fill(0, 0, 255, 0).is_ok(), "fill(0,0,255,0) accepted", passed, failed);
        check_true!(strip.fill(0, 0, 0, 255).is_ok(), "fill(w=255) accepted", passed, failed);
        check_true!(strip.off().is_ok(), "off() accepted", passed, failed);
    }

    // --- Sk6812RgbwFull ---
    {
        let spi = SpidevBus::open(format!("/dev/spidev{}.{}", spi_bus, spi_device))
            .expect("open spidev");
        let mut strip = Sk6812RgbwFull::new(spi, 8);

        check_eq!(strip.get_brightness(), 255u8, "default brightness is 255", passed, failed);

        strip.set_pixel(0, 255, 0, 0, 0);
        check_true!(strip.show().is_ok(), "set_pixel + show accepted", passed, failed);

        strip.set_pixel(7, 0, 0, 0, 255);
        check_true!(strip.show().is_ok(), "set_pixel(w=255) + show accepted", passed, failed);

        strip.set_brightness(128);
        check_eq!(strip.get_brightness(), 128u8, "brightness setter", passed, failed);
        check_true!(strip.show().is_ok(), "show() with brightness=128 accepted", passed, failed);

        strip.set_brightness(0);
        check_true!(strip.show().is_ok(), "show() with brightness=0 accepted", passed, failed);

        strip.set_brightness(255);
        strip.rotate(1);
        check_true!(strip.show().is_ok(), "rotate + show accepted", passed, failed);

        check_true!(strip.fill_hsv(0.0, 1.0, 1.0).is_ok(), "fill_hsv(0.0) accepted", passed, failed);
        check_true!(strip.fill_hsv(0.333, 1.0, 1.0).is_ok(), "fill_hsv(0.333) accepted", passed, failed);
        check_true!(strip.fill_hsv(0.667, 1.0, 1.0).is_ok(), "fill_hsv(0.667) accepted", passed, failed);
        check_true!(strip.off().is_ok(), "off() on Full accepted", passed, failed);
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
