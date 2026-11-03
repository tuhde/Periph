//go:build linux && !tinygo

// MB85RC hardware test — Linux host.
//
// Drives every method in the MB85RCFull API and reports PASS/FAIL per
// check. Prints the standard ===DONE: ... === summary line at the end.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"bytes"
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr64, err := strconv.ParseUint(envOr("I2C_ADDR", "0x50"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}
	ADDR := uint8(addr64)

	conn, err := connection.NewI2CConnectionWithWidth(bus, ADDR, 2, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := memory.NewMB85RCFull(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	// The Device ID lives at reserved address 0x7C; i2c-dev may refuse it.
	var idConn connection.RegisterConnection = unavailableConn{}
	if c, err := connection.NewI2CConnection(bus, 0x7C, nil, nil); err == nil {
		defer c.Close()
		idConn = c
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
	report := func(label string, err error) {
		if err != nil {
			fmt.Printf("%s: %v\n", label, err)
		}
	}

	check("size", chip.Size() == 32768)

	report("write_byte", chip.WriteUserByte(0x0010, 0x5A))
	b, err := chip.ReadUserByte(0x0010)
	report("read_byte", err)
	check("write_byte_read_byte_round_trip", b == 0x5A)

	data := make([]byte, 100)
	for i := range data {
		data[i] = byte(i)
	}
	report("write", chip.Write(0x0100, data))
	got, err := chip.Read(0x0100, len(data))
	report("read", err)
	check("chunked_write_read_round_trip", bytes.Equal(got, data))

	report("fill", chip.Fill(0x0100, 100, 0xC3))
	got, err = chip.Read(0x0100, 100)
	report("read fill", err)
	check("fill", bytes.Equal(got, bytes.Repeat([]byte{0xC3}, 100)))
	report("fill zero", chip.Fill(0x0100, 100, 0))
	got, err = chip.Read(0x0100, 100)
	report("read fill zero", err)
	check("fill_zero", bytes.Equal(got, make([]byte, 100)))

	report("write end", chip.Write(0x7FFE, []byte{0x12, 0x34}))
	got, err = chip.Read(0x7FFE, 2)
	report("read end", err)
	check("last_bytes_round_trip", bytes.Equal(got, []byte{0x12, 0x34}))

	_, err = chip.Read(0x7FFF, 2)
	check("wrap_rejected", err != nil)

	m, d, p, err := chip.ReadDeviceID(idConn, ADDR)
	if err != nil {
		fmt.Println("SKIP read_device_id (0x7C not accessible)")
	} else {
		check("read_device_id", m == 0x00A && d == 0x5 && p == 0x10)
	}

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

// unavailableConn stands in for the 0x7C connection when the host refuses it.
type unavailableConn struct{ connection.RegisterConnection }

func (unavailableConn) ReadReg(uint32, int) ([]byte, error) {
	return nil, fmt.Errorf("0x7C unavailable")
}
