#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::gpio::{Input, Output, Pull};
use esp_println::println;
use periph::transport::hx711::{HX711Error, HX711Transport};

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

    // DOUT on GPIO5 (input), PD_SCK on GPIO6 (output)
    let dout   = Input::new(peripherals.GPIO5, Pull::None);
    let pd_sck = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::Low);

    let mut transport = HX711Transport::new(dout, pd_sck);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let ready = transport.is_ready();
    check_true!(ready.is_ok(), "is_ready ok", passed, failed);

    let val = transport.read_raw(25);
    check_true!(val.is_ok(), "read_raw(25) ok", passed, failed);
    if let Ok(v) = val {
        check_true!(
            v >= -8_388_608 && v <= 8_388_607,
            "read_raw(25) in 24-bit signed range",
            passed,
            failed
        );
    }

    let val = transport.read_raw(26);
    check_true!(val.is_ok(), "read_raw(26) ok", passed, failed);
    if let Ok(v) = val {
        check_true!(
            v >= -8_388_608 && v <= 8_388_607,
            "read_raw(26) in 24-bit signed range",
            passed,
            failed
        );
    }

    let val = transport.read_raw(27);
    check_true!(val.is_ok(), "read_raw(27) ok", passed, failed);
    if let Ok(v) = val {
        check_true!(
            v >= -8_388_608 && v <= 8_388_607,
            "read_raw(27) in 24-bit signed range",
            passed,
            failed
        );
    }

    let bad = transport.read_raw(24);
    check_true!(
        matches!(bad, Err(HX711Error::InvalidPulseCount)),
        "read_raw(24) returns InvalidPulseCount",
        passed,
        failed
    );

    check_true!(transport.power_down().is_ok(), "power_down ok", passed, failed);
    check_true!(transport.power_up().is_ok(),   "power_up ok",   passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
