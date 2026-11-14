//go:build linux && !tinygo

// AT24C02N hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the AT24C02N check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"bytes"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x50"), 0, 8)
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

	chip, err := memory.NewAT24C02NFull(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
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

	check("size", chip.Size() == 256)
	check("page_size", chip.PageSize() == 8)

	check("write_byte", chip.WriteByteAt(0x0010, 0x5A) == nil)
	v, err := chip.ReadByteAt(0x0010)
	check("write_byte/read_byte round-trip", err == nil && v == 0x5A)
	check("wait_ready after write", chip.WaitReady(10*time.Millisecond))

	data := make([]byte, 100)
	for i := range data {
		data[i] = byte(i)
	}
	check("page-split write", chip.Write(0x0070, data) == nil)
	back, err := chip.Read(0x0070, len(data))
	check("page-split write/read round-trip", err == nil && bytes.Equal(back, data))

	page := make([]byte, 8)
	for i := range page {
		page[i] = byte(i)
	}
	check("write_page", chip.WritePage(0x00C0, page) == nil)
	pb, err := chip.Read(0x00C0, 8)
	check("write_page round-trip", err == nil && bytes.Equal(pb, page))
	check("write_page crossing rejected", chip.WritePage(0x00CC, make([]byte, 8)) != nil)

	check("fill", chip.Fill(0x0040, 100, 0xC3) == nil)
	f, err := chip.Read(0x0040, 100)
	check("fill content", err == nil && bytes.Equal(f, bytes.Repeat([]byte{0xC3}, 100)))
	check("fill zero", chip.Fill(0x0040, 100, 0) == nil)
	f, err = chip.Read(0x0040, 100)
	check("fill zero content", err == nil && bytes.Equal(f, make([]byte, 100)))

	check("last bytes write", chip.Write(0x00FE, []byte{0x12, 0x34}) == nil)
	tail, err := chip.Read(0x00FE, 2)
	check("last bytes round-trip", err == nil && bytes.Equal(tail, []byte{0x12, 0x34}))
	_, err = chip.Read(0x00FF, 2)
	check("wrap rejected", err != nil)

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
