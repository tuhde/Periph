use linux_embedded_hal::CdevPin;
use periph::transport::hx711::{HX711Error, HX711Transport};

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

fn main() {
    let chip_path = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let dout_offset: u32 = std::env::var("HX711_DOUT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(5);
    let pd_sck_offset: u32 = std::env::var("HX711_PD_SCK")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(6);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let chip = gpiocdev::Chip::new(&chip_path).expect("open gpio chip");

    let dout_line = chip
        .request_line(gpiocdev::Request::input([dout_offset]).consumer("hx711_test"))
        .expect("request dout");
    let pd_sck_line = chip
        .request_line(gpiocdev::Request::output([pd_sck_offset]).consumer("hx711_test"))
        .expect("request pd_sck");

    let dout   = CdevPin::new(dout_line,   dout_offset).expect("dout pin");
    let pd_sck = CdevPin::new(pd_sck_line, pd_sck_offset).expect("pd_sck pin");

    let mut transport = HX711Transport::new(dout, pd_sck);

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
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
