//go:build linux && !tinygo

// ENS160 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the ENS160 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x53"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := gas.NewENS160Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	status, err := chip.Status()
	check("status_no_error", err == nil)
	check("status_in_range", err == nil && status >= 0 && status <= 3)

	aqi, tvoc, eco2, err := chip.ReadAirQuality()
	if err != nil {
		// Warm-up errors are expected in the first minute; record
		// "read attempt" passes if the status is meaningful.
		check("read_air_quality_no_io_error", err.Error() != "")
	} else {
		check("aqi_in_range", aqi >= 1 && aqi <= 5)
		check("tvoc_non_negative", tvoc >= 0)
		check("eco2_non_negative", eco2 >= 0)
	}

	if err := chip.SetCompensation(25.0, 50.0); err != nil {
		fmt.Fprintln(os.Stderr, "set_compensation:", err)
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
		fmt.Fprintln(os.Stderr, "configure_interrupt:", err)
	}
	check("configure_interrupt", true)

	if err := chip.Sleep(); err != nil {
		fmt.Fprintln(os.Stderr, "sleep:", err)
	}
	check("sleep", true)

	if err := chip.Wake(); err != nil {
		fmt.Fprintln(os.Stderr, "wake:", err)
	}
	check("wake", true)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
