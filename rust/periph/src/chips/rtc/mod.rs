pub mod ds3231;
pub use ds3231::{
    Ds3231Minimal, Ds3231Full, DateTime, Alarm1Match, Alarm2Match,
    SOURCE_ALARM1, SOURCE_ALARM2,
};
