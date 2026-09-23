pub mod mcp9808;
pub use mcp9808::{
    Mcp9808Minimal, Mcp9808Full, Mcp9808Error, Mcp9808AlertMode, Mcp9808AlertOutput,
    Mcp9808AlertPolarity, MCP9808_I2C_ADDRESS, MCP9808_MANUFACTURER_ID, MCP9808_DEVICE_ID,
    MCP9808_SOURCE_LOWER, MCP9808_SOURCE_UPPER, MCP9808_SOURCE_CRITICAL,
};
pub mod tmp117;
pub use tmp117::{
    Tmp117Minimal, Tmp117Full, Tmp117Error, Tmp117Mode, Tmp117AlertMode, Tmp117AlertPolarity,
    Tmp117AlertPinFunction, Tmp117Config, TMP117_I2C_ADDRESS, TMP117_DEVICE_ID,
    TMP117_SOURCE_HIGH, TMP117_SOURCE_LOW,
};
