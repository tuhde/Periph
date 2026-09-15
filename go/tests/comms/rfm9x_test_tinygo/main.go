//go:build tinygo

// RFM95W hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an RFM95W on SPI0 (GP18=SCK, GP19=SDO,
// GP16=SDI, GP17=CS). Prints PASS/FAIL per check and ends with the
// standard ===DONE: ... === line. The test runner (go/test_tinygo.sh)
// reads the serial output and reports exit code 0/1/2 based on the
// ===DONE=== line.
//
// Checks that don't require a peer radio (version, configure, frequency
// validation, TX power, sleep/standby, send) always run. The RX checks
// (receive, continuous receive, link-quality readouts) only pass if a
// second RFM95W is transmitting on the same frequency during the test —
// they're skipped with a note otherwise.
package main

import (
	"fmt"
	"time"

	"machine"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 5_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		fmt.Printf("FAIL spi_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil)
	chip, err := comms.NewRFM95Full(conn, 868_000_000, nil, nil)
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

	// --- Identification ---
	ver, err := chip.Version()
	check("version_reads", err == nil)
	check("version_is_0x12", ver == 0x12)

	// --- Modem configuration ---
	err = chip.Configure(7, 125.0, 5, true)
	check("configure_sf7_bw125_cr5", err == nil)

	err = chip.Configure(6, 500.0, 8, true)
	check("configure_sf6_special_case", err == nil)

	err = chip.Configure(13, 125.0, 5, true)
	check("configure_rejects_sf_above_max", err != nil)

	err = chip.Configure(7, 99.9, 5, true)
	check("configure_rejects_unsupported_bandwidth", err != nil)

	check("configure_restore_defaults", chip.Configure(7, 125.0, 5, true) == nil)

	// --- Carrier frequency ---
	check("set_frequency_in_range", chip.SetFrequency(868_000_000) == nil)
	check("set_frequency_rejects_out_of_range", chip.SetFrequency(1) != nil)

	// --- TX power ---
	check("set_tx_power_17dbm_paboost", chip.SetTxPower(17, true) == nil)
	check("set_tx_power_20dbm_high_power", chip.SetTxPower(20, true) == nil)
	check("set_tx_power_14dbm_rfo", chip.SetTxPower(14, false) == nil)
	check("set_tx_power_restore_default", chip.SetTxPower(17, true) == nil)

	// --- Power management ---
	check("standby", chip.Standby() == nil)
	check("sleep", chip.Sleep() == nil)
	check("standby_after_sleep", chip.Standby() == nil)

	// --- Send (no peer required to observe TxDone locally) ---
	check("send_completes", chip.Send([]byte("periph rfm9x hil test")) == nil)
	check("send_rejects_over_255_bytes", chip.Send(make([]byte, 256)) != nil)

	// --- Receive (requires a peer transmitting on the same frequency) ---
	pkt, err := chip.Receive(1000, false)
	check("receive_polling_no_error", err == nil)
	if pkt != nil {
		check("receive_polling_got_packet", true)
		_, err := chip.LastPacketRSSI()
		check("last_packet_rssi", err == nil)
		_, err = chip.LastPacketSNR()
		check("last_packet_snr", err == nil)
	} else {
		fmt.Println("note: no packet received within timeout; skipping receive_polling_got_packet (needs a peer radio)")
	}

	// --- Continuous receive ---
	check("receive_continuous_enter", chip.ReceiveContinuous() == nil)
	_, err = chip.RSSI()
	check("rssi_reads_in_continuous_mode", err == nil)
	_, err = chip.ReadPacket()
	check("read_packet_no_error", err == nil)
	check("stop_receive", chip.StopReceive() == nil)

	time.Sleep(100 * time.Millisecond)
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
