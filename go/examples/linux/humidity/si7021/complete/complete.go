//go:build linux && !tinygo

// Si7021 complete example — Linux host.
//
// Constructs the Full driver and calls every public method once.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x40) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := humidity.NewSi7021Full(conn) // Create Si7021 driver, (connection) → *Si7021Full

	t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	// RH without hold master, temperature of the same conversion
	chk(err)
	fmt.Println(t, rh)
	rhp, err := chip.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	chk(err)
	fmt.Println(rhp)
	tc, err := chip.ReadTemperature() // Read temperature (new conversion), () → (float32 °C, error)
	chk(err)
	fmt.Println(tc)
	tp, err := chip.ReadTemperaturePrevious() // Temperature of the last RH measurement, () → (float32 °C, error)
	// command 0xE0, no new conversion
	chk(err)
	fmt.Println(tp)
	rawRH, err := chip.ReadRawHumidity() // Read raw RH code, () → (uint16, error)
	// 16-bit code, status bits included
	chk(err)
	rawT, err := chip.ReadRawTemperature() // Read raw temperature code, () → (uint16, error)
	chk(err)
	fmt.Println(rawRH, rawT)

	chk(chip.SetResolution(11, 11)) // Set resolution, (rhBits, temperatureBits int) → error
	// (12, 14) / (8, 12) / (10, 13) / (11, 11)
	rhBits, tBits, err := chip.GetResolution() // Get resolution, () → (int, int, error)
	chk(err)
	fmt.Println(rhBits, tBits)
	ct, err := chip.ConversionTime() // Typical conversion time, () → (float32 s, error)
	chk(err)
	fmt.Println(ct)
	chk(chip.SetResolution(12, 14))

	chk(chip.SetHeater(true)) // Switch the heater, (on bool) → error
	// about 3.1 mA; readings drift while on
	on, err := chip.IsHeaterOn() // Heater state, () → (bool, error)
	chk(err)
	fmt.Println(on)
	chk(chip.SetHeater(false))
	low, err := chip.VDDLow() // Supply low flag, () → (bool, error)
	// true when VDD is below about 1.9 V
	chk(err)
	fmt.Println(low)
	reg, err := chip.ReadUserRegister() // Read USER_REG_1, () → (byte, error)
	// 0x3A after reset
	chk(err)
	chk(chip.WriteUserRegister(reg)) // Write USER_REG_1, (value byte) → error
	// reserved bits are preserved
	chip.SetClockStretching(false) // Use hold-master commands, (on bool)
	// needs bus-layer support; off polls the NACKed read header

	sn, err := chip.SerialNumber() // Electronic serial number, () → (uint64, error)
	chk(err)
	fmt.Printf("serial number 0x%016X\n", sn)
	dt, err := chip.DeviceType() // Device type, () → (byte, error)
	// 0x15 = Si7021
	chk(err)
	fmt.Printf("device type 0x%02X\n", dt)
	fw, err := chip.FirmwareRevision() // Firmware revision, () → (byte, error)
	// 0xFF = firmware 1.0
	chk(err)
	fmt.Printf("firmware 0x%02X\n", fw)
	dp, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	// one measurement, Magnus formula
	chk(err)
	fmt.Println(dp)
	chk(chip.SoftReset()) // Software reset, () → error
	// 0xFE, waits 15 ms

}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
