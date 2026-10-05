// GENERATED from registry/chips.json by registry/scripts/generate.js - do not edit; run the script instead.

package discovery

// IdProbe is the identity-register read that confirms a chip.
type IdProbe struct {
	Register     uint32
	RegBytes     int
	Length       int
	LittleEndian bool
	Mask         uint32
	Expected     []uint32
}

// Chip is one registry entry. Driver is "" and Probe is nil when absent.
type Chip struct {
	ID             string
	Driver         string
	WriteSensitive bool
	Aliased        bool
	Addresses      []uint8
	Probe          *IdProbe
}

var registry = []Chip{
	{ID: "adxl345", Driver: "adxl345", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x1D, 0x53}, Probe: &IdProbe{Register: 0x00, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0xE5}}},
	{ID: "bma180", Driver: "", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x40, 0x41}, Probe: &IdProbe{Register: 0x00, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0x07, Expected: []uint32{0x03}}},
	{ID: "lis3dh", Driver: "", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x18, 0x19}, Probe: &IdProbe{Register: 0x0F, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x33}}},
	{ID: "lsm303-accel", Driver: "", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x19}, Probe: nil},
	{ID: "lsm303-mag", Driver: "", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x1E}, Probe: &IdProbe{Register: 0x0A, RegBytes: 1, Length: 3, LittleEndian: false, Mask: 0xFFFFFF, Expected: []uint32{0x483433}}},
	{ID: "mcp4725", Driver: "mcp4725", WriteSensitive: true, Aliased: false, Addresses: []uint8{0x60, 0x61}, Probe: nil},
	{ID: "mcp4728", Driver: "mcp4728", WriteSensitive: true, Aliased: false, Addresses: []uint8{0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67}, Probe: nil},
	{ID: "pcf8591", Driver: "pcf8591", WriteSensitive: true, Aliased: false, Addresses: []uint8{0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F}, Probe: nil},
	{ID: "rda5807m", Driver: "rda5807m", WriteSensitive: true, Aliased: false, Addresses: []uint8{0x10}, Probe: nil},
	{ID: "pcf8576", Driver: "pcf8576", WriteSensitive: true, Aliased: false, Addresses: []uint8{0x38, 0x39}, Probe: nil},
	{ID: "aht21", Driver: "aht21", WriteSensitive: true, Aliased: false, Addresses: []uint8{0x38}, Probe: nil},
	{ID: "bme280", Driver: "bme280", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x76, 0x77}, Probe: &IdProbe{Register: 0xD0, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x60}}},
	{ID: "bme680", Driver: "bme680", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x76, 0x77}, Probe: &IdProbe{Register: 0xD0, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x61}}},
	{ID: "ens160", Driver: "ens160", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x52, 0x53}, Probe: &IdProbe{Register: 0x00, RegBytes: 1, Length: 2, LittleEndian: true, Mask: 0xFFFF, Expected: []uint32{0x0160}}},
	{ID: "neo-6", Driver: "neo-6", WriteSensitive: true, Aliased: false, Addresses: []uint8{0x42}, Probe: nil},
	{ID: "l3g4200d", Driver: "l3g4200d", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x68, 0x69}, Probe: &IdProbe{Register: 0x0F, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0xD3}}},
	{ID: "l3gd20h", Driver: "l3gd20h", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x6A, 0x6B}, Probe: &IdProbe{Register: 0x0F, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0xD4, 0xD7}}},
	{ID: "mpu6050", Driver: "mpu6050", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x68, 0x69}, Probe: &IdProbe{Register: 0x75, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0x7E, Expected: []uint32{0x68}}},
	{ID: "mpu9250", Driver: "mpu9250", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x68, 0x69}, Probe: &IdProbe{Register: 0x75, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x71}}},
	{ID: "mpu9255", Driver: "mpu9255", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x68, 0x69}, Probe: &IdProbe{Register: 0x75, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x73}}},
	{ID: "mcp23017", Driver: "mcp23017", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27}, Probe: nil},
	{ID: "pcf8574", Driver: "pcf8574", WriteSensitive: true, Aliased: false, Addresses: []uint8{0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F}, Probe: nil},
	{ID: "pcf8575", Driver: "pcf8575", WriteSensitive: true, Aliased: false, Addresses: []uint8{0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27}, Probe: nil},
	{ID: "apds-9930", Driver: "apds-9930", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x39}, Probe: &IdProbe{Register: 0x92, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x39}}},
	{ID: "apds9960", Driver: "apds9960", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x39}, Probe: &IdProbe{Register: 0x92, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0xAB}}},
	{ID: "as5600", Driver: "as5600", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x36}, Probe: nil},
	{ID: "hmc5883l", Driver: "hmc5883l", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x1E}, Probe: &IdProbe{Register: 0x0A, RegBytes: 1, Length: 3, LittleEndian: false, Mask: 0xFFFFFF, Expected: []uint32{0x483433}}},
	{ID: "24aa02uid", Driver: "24aa02uid", WriteSensitive: false, Aliased: true, Addresses: []uint8{0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57}, Probe: nil},
	{ID: "24aa025uid", Driver: "", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57}, Probe: nil},
	{ID: "drv8830", Driver: "drv8830", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68}, Probe: nil},
	{ID: "mpr121", Driver: "mpr121", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x5A, 0x5B, 0x5C, 0x5D}, Probe: nil},
	{ID: "ade7953", Driver: "ade7953", WriteSensitive: true, Aliased: false, Addresses: []uint8{0x38}, Probe: nil},
	{ID: "ina219", Driver: "ina219", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F}, Probe: nil},
	{ID: "ina226", Driver: "ina226", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F}, Probe: &IdProbe{Register: 0xFF, RegBytes: 1, Length: 2, LittleEndian: false, Mask: 0xFFFF, Expected: []uint32{0x2260}}},
	{ID: "ina3221", Driver: "ina3221", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x40, 0x41, 0x42, 0x43}, Probe: &IdProbe{Register: 0xFF, RegBytes: 1, Length: 2, LittleEndian: false, Mask: 0xFFFF, Expected: []uint32{0x3220}}},
	{ID: "bmp085", Driver: "bmp085", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x77}, Probe: &IdProbe{Register: 0xD0, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x55}}},
	{ID: "bmp180", Driver: "bmp180", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x77}, Probe: &IdProbe{Register: 0xD0, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x55}}},
	{ID: "bmp280", Driver: "bmp280", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x76, 0x77}, Probe: &IdProbe{Register: 0xD0, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x58}}},
	{ID: "bmp384", Driver: "bmp384", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x76, 0x77}, Probe: &IdProbe{Register: 0x00, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x50}}},
	{ID: "bmp581", Driver: "bmp581", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x46, 0x47}, Probe: &IdProbe{Register: 0x01, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x50}}},
	{ID: "lps22df", Driver: "lps22df", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x5C, 0x5D}, Probe: &IdProbe{Register: 0x0F, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0xB4}}},
	{ID: "lps28dfw", Driver: "lps28dfw", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x5C, 0x5D}, Probe: &IdProbe{Register: 0x0F, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0xB4}}},
	{ID: "lps33hw", Driver: "lps33hw", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x5C, 0x5D}, Probe: &IdProbe{Register: 0x0F, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0xB1}}},
	{ID: "mfrc522", Driver: "mfrc522", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F}, Probe: &IdProbe{Register: 0x37, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0x91, 0x92}}},
	{ID: "ds3231", Driver: "ds3231", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x68}, Probe: nil},
	{ID: "pcf8523", Driver: "pcf8523", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x68}, Probe: nil},
	{ID: "mcp9808", Driver: "mcp9808", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F}, Probe: &IdProbe{Register: 0x07, RegBytes: 1, Length: 2, LittleEndian: false, Mask: 0xFF00, Expected: []uint32{0x0400}}},
	{ID: "tmp117", Driver: "tmp117", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x48, 0x49, 0x4A, 0x4B}, Probe: &IdProbe{Register: 0x0F, RegBytes: 1, Length: 2, LittleEndian: false, Mask: 0x0FFF, Expected: []uint32{0x0117}}},
	{ID: "vl53l0x", Driver: "vl53l0x", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x29}, Probe: &IdProbe{Register: 0xC0, RegBytes: 1, Length: 1, LittleEndian: false, Mask: 0xFF, Expected: []uint32{0xEE}}},
	{ID: "vl53l1x", Driver: "vl53l1x", WriteSensitive: false, Aliased: false, Addresses: []uint8{0x29}, Probe: &IdProbe{Register: 0x10F, RegBytes: 2, Length: 2, LittleEndian: false, Mask: 0xFFFF, Expected: []uint32{0xEACC}}},
}
