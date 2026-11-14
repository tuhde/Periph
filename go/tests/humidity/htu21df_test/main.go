//go:build linux && !tinygo

// HTU21DF hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the HTU21DF check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
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

	chip := humidity.NewHTU21DFFull(conn)

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

	check("init", chip.Init() == nil)
	reg, err := chip.ReadUserRegister()
	check("user register after reset", err == nil && reg&0x83 == 0x02)

	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("temperature range", t > -40 && t < 125)
	check("humidity range", rh >= -6 && rh <= 119)
	rhp, err := chip.ReadHumidity()
	check("read_humidity range", err == nil && rhp >= -6 && rhp <= 119)
	tc, err := chip.ReadTemperature()
	check("read_temperature range", err == nil && tc > -40 && tc < 125)
	comp, err := chip.ReadHumidityCompensated()
	check("compensated humidity range", err == nil && comp >= -6 && comp <= 125)
	_, _, err = chip.ReadRaw()
	check("raw ok", err == nil)

	for res := 0; res < 4; res++ {
		check("set_resolution", chip.SetResolution(res) == nil)
		got, err := chip.GetResolution()
		check("get_resolution", err == nil && got == res)
		_, _, err = chip.Read()
		check("read at resolution", err == nil)
	}
	check("set_resolution default", chip.SetResolution(0) == nil)

	check("heater on", chip.SetHeater(true) == nil)
	on, err := chip.Heater()
	check("heater on readback", err == nil && on)
	check("heater off", chip.SetHeater(false) == nil)
	on, err = chip.Heater()
	check("heater off readback", err == nil && !on)
	low, err := chip.BatteryLow()
	check("battery ok", err == nil && !low)

	dp, err := chip.DewPoint()
	check("dew point plausible", err == nil && dp > -60 && dp < 60)
	check("soft_reset", chip.SoftReset() == nil)
	_, _, err = chip.Read()
	check("read after soft reset", err == nil)

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
