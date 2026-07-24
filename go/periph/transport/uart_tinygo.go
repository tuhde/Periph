//go:build tinygo

// UARTTransport is the TinyGo implementation of the Transport interface
// for UART, backed by a configured machine.UART value the caller passes
// in. The machine.UART implements io.Reader/io.Writer directly.
package transport

import (
	"machine"
	"time"
)

// UARTTransport is a TinyGo machine.UART-backed implementation of Transport.
type UARTTransport struct {
	uart   *machine.UART
	hasDE  bool
	dePin  machine.Pin // optional RS-485 DE pin
	baud   uint32
}

// NewUARTTransport binds a configured machine.UART and an optional
// RS-485 DE pin. Pass dePin as machine.Pin(0) (the zero value, which
// is the unconfigured/unused pin) if the chip is not RS-485.
func NewUARTTransport(uart *machine.UART, dePin machine.Pin) *UARTTransport {
	t := &UARTTransport{uart: uart}
	if dePin != 0 {
		dePin.Configure(machine.PinConfig{Mode: machine.PinOutput})
		dePin.Low()
		t.hasDE = true
		t.dePin = dePin
	}
	return t
}

// Close is a no-op: machine.UART has no explicit release.
func (t *UARTTransport) Close() error {
	return nil
}

// Write sends bytes to the device. For RS-485, asserts DE first, then
// waits the baud-rate-derived time before deasserting DE (TinyGo's
// machine.UART has no explicit TX-drain call).
func (t *UARTTransport) Write(data []byte) error {
	if len(data) == 0 {
		return nil
	}
	if t.hasDE {
		t.dePin.High()
	}
	n, err := t.uart.Write(data)
	_ = n
	if t.hasDE {
		// Wait for the bytes to leave the UART at the configured baud rate.
		// 10 bits per byte (8N1 + start + stop), plus a 100 µs safety margin.
		baud := uint32(9600)
		delay := time.Duration(len(data))*10*time.Second/time.Duration(baud) + 100*time.Microsecond
		time.Sleep(delay)
		t.dePin.Low()
	}
	return err
}

// Read reads n bytes from the device in a loop until n bytes accumulate
// or a 100 ms deadline elapses. machine.UART has no blocking
// read-exactly-n call.
func (t *UARTTransport) Read(n int) ([]byte, error) {
	if n == 0 {
		return []byte{}, nil
	}
	buf := make([]byte, n)
	total := 0
	deadline := time.Now().Add(100 * time.Millisecond)
	for total < n {
		if time.Now().After(deadline) {
			break
		}
		nr, err := t.uart.Read(buf[total:])
		if err != nil {
			return nil, err
		}
		if nr == 0 {
			time.Sleep(1 * time.Millisecond)
			continue
		}
		total += nr
	}
	return buf[:total], nil
}

// WriteRead writes data then reads n bytes.
func (t *UARTTransport) WriteRead(data []byte, n int) ([]byte, error) {
	if err := t.Write(data); err != nil {
		return nil, err
	}
	return t.Read(n)
}
