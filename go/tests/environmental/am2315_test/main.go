//go:build linux && !tinygo

// AM2315 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the AM2315 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
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

	chip := environmental.NewAM2315Full(conn)

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

	check("device info", func() bool { m, _, _, err := chip.ReadDeviceInfo(); return err == nil && m != 0xFFFF }())

	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("temperature range", t >= -40 && t <= 125)
	check("humidity range", rh >= 0 && rh <= 100)
	rhp, err := chip.ReadHumidity()
	check("read_humidity range", err == nil && rhp >= 0 && rhp <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature range", err == nil && tc >= -40 && tc <= 125)
	rawRH, rawT, err := chip.ReadRaw()
	check("raw ranges", err == nil && rawRH <= 1000 && rawT&0x7FFF <= 1250)
	ft, fh, err := chip.ReadFresh()
	check("read_fresh range", err == nil && ft >= -40 && ft <= 125 && fh >= 0 && fh <= 100)
	regs, err := chip.ReadRegisters(0x00, 4)
	check("read_registers length", err == nil && len(regs) == 4)

	check("write_user_registers", chip.WriteUserRegisters(0x1234, 0xABCD) == nil)
	u1, u2, err := chip.ReadUserRegisters()
	check("user registers round trip", err == nil && u1 == 0x1234 && u2 == 0xABCD)
	check("clear user registers", chip.WriteUserRegisters(0, 0) == nil)
	chip.Wake()

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
