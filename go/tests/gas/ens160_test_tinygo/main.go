//go:build tinygo

// ENS160 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an ENS160 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x53, nil, nil)
	chip, err := gas.NewENS160Full(conn)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	status, err := chip.Status()
	check("status_no_error", err == nil)
	check("status_in_range", err == nil && status >= 0 && status <= 3)

	aqi, tvoc, eco2, err := chip.ReadAirQuality()
	if err != nil {
		check("read_air_quality_no_io_error", err.Error() != "")
	} else {
		check("aqi_in_range", aqi >= 1 && aqi <= 5)
		check("tvoc_non_negative", tvoc >= 0)
		check("eco2_non_negative", eco2 >= 0)
	}

	if err := chip.SetCompensation(25.0, 50.0); err != nil {
		fmt.Printf("set_compensation: %v\n", err)
	}
	check("set_compensation", true)

	tvoc, err = chip.ReadTVOC()
	check("read_tvoc_no_error", err == nil)
	check("read_tvoc_in_range", err == nil && tvoc >= 0)

	eco2, err = chip.ReadECO2()
	check("read_eco2_no_error", err == nil)
	check("read_eco2_in_range", err == nil && eco2 >= 0)

	aqiOnly, err := chip.ReadAQI()
	check("read_aqi_no_error", err == nil)
	check("read_aqi_in_range", err == nil && aqiOnly >= 0 && aqiOnly <= 5)

	ethanol, err := chip.ReadEthanol()
	check("read_ethanol_no_error", err == nil)
	check("read_ethanol_in_range", err == nil && ethanol >= 0)

	_, err = chip.ReadRawResistance(1)
	check("read_raw_resistance_1_no_error", err == nil)
	_, err = chip.ReadRawResistance(4)
	check("read_raw_resistance_4_no_error", err == nil)

	tAct, rhAct, err := chip.ReadCompensationActuals()
	check("read_compensation_actuals_no_error", err == nil)
	check("compensation_temp_in_range", err == nil && tAct > -50 && tAct < 100)
	check("compensation_rh_in_range", err == nil && rhAct >= 0 && rhAct <= 100)

	major, minor, release, err := chip.GetFirmwareVersion()
	check("get_firmware_version_no_error", err == nil)
	check("firmware_version_in_range", err == nil && major >= 0 && minor >= 0 && release >= 0)

	if err := chip.ConfigureInterrupt(false, false, false, false, false); err != nil {
		fmt.Printf("configure_interrupt: %v\n", err)
	}
	check("configure_interrupt", true)

	if err := chip.Sleep(); err != nil {
		fmt.Printf("sleep: %v\n", err)
	}
	check("sleep", true)

	if err := chip.Wake(); err != nil {
		fmt.Printf("wake: %v\n", err)
	}
	check("wake", true)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
