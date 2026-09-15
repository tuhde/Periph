//go:build linux && !tinygo

// RFM95W hardware test — Linux host.
//
// Opens /dev/spidevB.D and runs the RFM95Full check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure, 2 if the test did not complete.
//
// Checks that don't require a peer radio (version, configure, frequency
// validation, TX power, sleep/standby, send) always run. The RX checks
// (receive, continuous receive, link-quality readouts) only pass if a
// second RFM95W is transmitting on the same frequency during the test —
// they're skipped with a note otherwise.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SPI_BUS:", err)
		os.Exit(2)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SPI_DEVICE:", err)
		os.Exit(2)
	}
	freq, err := strconv.ParseUint(envOr("RFM9X_FREQUENCY_HZ", "868000000"), 10, 32)
	if err != nil {
		fmt.Fprintln(os.Stderr, "RFM9X_FREQUENCY_HZ:", err)
		os.Exit(2)
	}

	conn, err := connection.NewSPIConnection(bus, device, 5_000_000, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := comms.NewRFM95Full(conn, uint32(freq), nil, nil)
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

	// restore Minimal-stage defaults for the send/receive checks below
	check("configure_restore_defaults", chip.Configure(7, 125.0, 5, true) == nil)

	// --- Carrier frequency ---
	err = chip.SetFrequency(uint32(freq))
	check("set_frequency_in_range", err == nil)

	err = chip.SetFrequency(1) // 1 Hz is out of range for every variant
	check("set_frequency_rejects_out_of_range", err != nil)

	// --- TX power ---
	check("set_tx_power_17dbm_paboost", chip.SetTxPower(17, true) == nil)
	check("set_tx_power_20dbm_high_power", chip.SetTxPower(20, true) == nil)
	check("set_tx_power_14dbm_rfo", chip.SetTxPower(14, false) == nil)
	// restore the Minimal-stage default before send/receive
	check("set_tx_power_restore_default", chip.SetTxPower(17, true) == nil)

	// --- Power management ---
	check("standby", chip.Standby() == nil)
	check("sleep", chip.Sleep() == nil)
	check("standby_after_sleep", chip.Standby() == nil)

	// --- Send (no peer required to observe TxDone locally) ---
	err = chip.Send([]byte("periph rfm9x hil test"))
	check("send_completes", err == nil)

	err = chip.Send(make([]byte, 256))
	check("send_rejects_over_255_bytes", err != nil)

	// --- Receive (requires a peer transmitting on the same frequency) ---
	pkt, err := chip.Receive(1000, false)
	check("receive_polling_no_error", err == nil)
	if pkt != nil {
		check("receive_polling_got_packet", true)
		rssi, err := chip.LastPacketRSSI()
		check("last_packet_rssi", err == nil)
		snr, err := chip.LastPacketSNR()
		check("last_packet_snr", err == nil)
		fmt.Printf("note: received %d B, rssi=%.1f dBm, snr=%.1f dB\n", len(pkt), rssi, snr)
	} else {
		fmt.Println("note: no packet received within timeout; skipping receive_polling_got_packet (needs a peer radio)")
	}

	// --- Continuous receive ---
	check("receive_continuous_enter", chip.ReceiveContinuous() == nil)
	rssi, err := chip.RSSI()
	check("rssi_reads_in_continuous_mode", err == nil)
	_ = rssi
	pkt, err = chip.ReadPacket()
	check("read_packet_no_error", err == nil)
	if pkt != nil {
		fmt.Printf("note: continuous mode received %d B\n", len(pkt))
	}
	check("stop_receive", chip.StopReceive() == nil)

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
