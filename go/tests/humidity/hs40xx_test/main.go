//go:build linux && !tinygo

// HS40xx hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the HS40xx check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x54"), 0, 8)
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

	chip := humidity.NewHS40xxFull(conn)

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	t, rh, err := chip.Read()
	check("read", err == nil)
	check("temperature range", t >= -40 && t <= 125)
	check("humidity range", rh >= 0 && rh <= 100)
	t, err = chip.ReadTemperature()
	check("read_temperature range", err == nil && t >= -40 && t <= 125)
	rh, err = chip.ReadHumidity()
	check("read_humidity range", err == nil && rh >= 0 && rh <= 100)
	rhRaw, tRaw, err := chip.ReadRaw()
	check("raw ranges", err == nil && rhRaw <= 16383 && tRaw <= 16383)
	id, err := chip.ReadID()
	check("id nonzero", err == nil && id != 0)

	check("set resolution 12/10", chip.SetResolution(12, 10) == nil)
	hb, tb, err := chip.GetResolution()
	check("resolution readback", err == nil && hb == 12 && tb == 10)
	check("restore resolution", chip.SetResolution(14, 14) == nil)

	check("start periodic", chip.StartPeriodic(0.5) == nil)
	time.Sleep(time.Second)
	p, err := chip.ReadPeriodic()
	check("periodic humidity", err == nil && p.HumidityPct >= 0 && p.HumidityPct <= 100)
	check("stop periodic", chip.StopPeriodic() == nil)
	check("soft reset", chip.SoftReset() == nil)

	dp, err := chip.DewPoint()
	check("dew point plausible", err == nil && dp > -60 && dp < 60)

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
