#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::gpio::{Input, Output, Pull};
use esp_println::println;
use periph::chips::adc_dac::{Hx710aFull, Hx710aMinimal};
use periph::connection::hx711::{HX711Error, HX711Connection};

esp_app_desc!();

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

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Hx710aMinimal ---
    {
        // DOUT on GPIO5 (input), PD_SCK on GPIO6 (output)
        let dout   = Input::new(peripherals.GPIO5, Pull::None);
        let pd_sck = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::Low);

        let connection = HX711Connection::new(dout, pd_sck);
        let mut minimal = Hx710aMinimal::new(connection).expect("init Hx710aMinimal");

        check_true!(minimal.is_ready().is_ok(), "minimal is_ready ok", passed, failed);

        let raw = minimal.read_raw();
        check_true!(raw.is_ok(), "minimal read_raw ok", passed, failed);
        if let Ok(v) = raw {
            check_true!(
                v >= -8_388_608 && v <= 8_388_607,
                "minimal read_raw in 24-bit signed range",
                passed,
                failed
            );
        }
    }

    // --- Hx710aFull ---
    {
        // DOUT on GPIO5 (input), PD_SCK on GPIO6 (output)
        let dout   = Input::new(peripherals.GPIO5, Pull::None);
        let pd_sck = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::Low);

        let connection = HX711Connection::new(dout, pd_sck);
        let mut full = Hx710aFull::new(connection).expect("init Hx710aFull");

        check_true!(full.is_ready().is_ok(), "full is_ready ok", passed, failed);

        let raw = full.read_raw();
        check_true!(raw.is_ok(), "full read_raw ok", passed, failed);
        if let Ok(v) = raw {
            check_true!(
                v >= -8_388_608 && v <= 8_388_607,
                "full read_raw in 24-bit signed range",
                passed,
                failed
            );
        }

        check_true!(full.set_rate(40).is_ok(), "set_rate 40 ok", passed, failed);
        check_true!(full.set_rate(10).is_ok(), "set_rate 10 ok", passed, failed);
        check_true!(
            matches!(full.set_rate(20), Err(HX711Error::InvalidPulseCount)),
            "set_rate 20 returns InvalidPulseCount",
            passed,
            failed
        );

        let avg = full.read_average(3);
        check_true!(avg.is_ok(), "read_average(3) ok", passed, failed);
        if let Ok(v) = avg {
            check_true!(
                v >= -8_388_608 && v <= 8_388_607,
                "read_average(3) in 24-bit signed range",
                passed,
                failed
            );
        }

        check_true!(full.tare(3).is_ok(), "tare(3) ok", passed, failed);
        let offset = full.get_offset();
        check_true!(
            offset >= -8_388_608 && offset <= 8_388_607,
            "get_offset in 24-bit signed range",
            passed,
            failed
        );

        full.set_scale(420.0);
        check_true!((full.get_scale() - 420.0).abs() < 0.001, "get_scale 420.0", passed, failed);

        let weight = full.read_weight(3);
        check_true!(weight.is_ok(), "read_weight(3) ok", passed, failed);

        let temp_raw = full.read_temperature_raw();
        check_true!(temp_raw.is_ok(), "read_temperature_raw ok", passed, failed);
        if let Ok(v) = temp_raw {
            check_true!(
                v >= -8_388_608 && v <= 8_388_607,
                "read_temperature_raw in 24-bit signed range",
                passed,
                failed
            );
        }
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
