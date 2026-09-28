package comms

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// rfm9xMockConnection is an in-memory fake connection.Connection for unit tests.
// RFM9x's comm byte is itself the register address (reg|0x80 for write,
// reg&0x7F for read), so a plain register-map mock works directly.
type rfm9xMockConnection struct {
	registers map[byte]byte
	writes    [][]byte
}

func newRFM9xMockConnection() *rfm9xMockConnection {
	return &rfm9xMockConnection{registers: map[byte]byte{}}
}

func (m *rfm9xMockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *rfm9xMockConnection) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if len(data) >= 2 {
		reg := data[0]
		for i, v := range data[1:] {
			m.registers[reg+byte(i)] = v
		}
	}
	return nil
}

func (m *rfm9xMockConnection) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}

func (m *rfm9xMockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	if len(data) > 0 {
		reg := data[0]
		for i := 0; i < n; i++ {
			out[i] = m.registers[reg+byte(i)]
		}
	}
	return out, nil
}

func (m *rfm9xMockConnection) Close() error                { return nil }
func (m *rfm9xMockConnection) Enable()                     {}
func (m *rfm9xMockConnection) Disable()                    {}
func (m *rfm9xMockConnection) IsEnabled() bool             { return true }
func (m *rfm9xMockConnection) IntPin() connection.InputPin { return nil }
func (m *rfm9xMockConnection) EnPin() connection.OutputPin { return nil }

func bytesEqualRFM9x(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func hasWrite(conn *rfm9xMockConnection, want []byte) bool {
	for _, w := range conn.writes {
		if bytesEqualRFM9x(w, want) {
			return true
		}
	}
	return false
}

type fakeOutputPinRFM9x struct {
	calls []bool
}

func (p *fakeOutputPinRFM9x) Set(high bool) error {
	p.calls = append(p.calls, high)
	return nil
}

type fakeInputPinRFM9x struct{}

func (p *fakeInputPinRFM9x) OnEdge(trigger connection.Trigger, handler func()) func() {
	go handler()
	return func() {}
}

const rfm9xRegVersionTest = 0x42
const rfm9xExpectedVersionTest = 0x12

func newRFM9xConnection() *rfm9xMockConnection {
	c := newRFM9xMockConnection()
	c.setRegister(rfm9xRegVersionTest, rfm9xExpectedVersionTest)
	return c
}

func TestRFM9xInitAndSend(t *testing.T) {
	conn := newRFM9xConnection()
	sensor, err := NewRFM95Minimal(conn, 915000000)
	if err != nil {
		t.Fatalf("NewRFM95Minimal: %v", err)
	}
	if !hasWrite(conn, []byte{0x86, 0xE4}) || !hasWrite(conn, []byte{0x87, 0xC0}) || !hasWrite(conn, []byte{0x88, 0x00}) {
		t.Errorf("init did not write expected FRF bytes: %v", conn.writes)
	}
	if !hasWrite(conn, []byte{0x9D, 0x72}) || !hasWrite(conn, []byte{0x9E, 0x77}) {
		t.Errorf("init did not write expected default modem config: %v", conn.writes)
	}
	if !hasWrite(conn, []byte{0x89, 0x8F}) {
		t.Errorf("init did not write expected default TX power: %v", conn.writes)
	}

	badVersionConn := newRFM9xMockConnection()
	badVersionConn.setRegister(rfm9xRegVersionTest, 0x99)
	if _, err := NewRFM95Minimal(badVersionConn, 915000000); err == nil {
		t.Errorf("expected error for wrong version")
	}

	if _, err := NewRFM95Minimal(conn, 433000000); err == nil {
		t.Errorf("expected error for out-of-range frequency")
	}

	lfConn := newRFM9xConnection()
	if _, err := NewRFM96Minimal(lfConn, 433000000); err != nil {
		t.Fatalf("NewRFM96Minimal: %v", err)
	}

	// send([0xDE, 0xAD, 0xBE]): standby first, then FIFO/TX sequence.
	conn.setRegister(0x12, 0x08) // IRQ_FLAGS: TX_DONE set, poll succeeds immediately
	conn.writes = nil
	if err := sensor.Send([]byte{0xDE, 0xAD, 0xBE}); err != nil {
		t.Fatalf("Send: %v", err)
	}
	if !bytesEqualRFM9x(conn.writes[1], []byte{0x8D, 0x80}) {
		t.Errorf("send fifo addr ptr: got %v", conn.writes[1])
	}
	if !bytesEqualRFM9x(conn.writes[2], []byte{0x80, 0xDE, 0xAD, 0xBE}) {
		t.Errorf("send fifo payload: got %v", conn.writes[2])
	}
	if !bytesEqualRFM9x(conn.writes[3], []byte{0xA2, 0x03}) {
		t.Errorf("send payload length: got %v", conn.writes[3])
	}
	if !bytesEqualRFM9x(conn.writes[4], []byte{0xC0, 0x40}) {
		t.Errorf("send dio mapping: got %v", conn.writes[4])
	}
	if !bytesEqualRFM9x(conn.writes[5], []byte{0x81, 0x83}) {
		t.Errorf("send tx mode: got %v", conn.writes[5])
	}
	if !hasWrite(conn, []byte{0x92, 0x08}) {
		t.Errorf("send did not clear irq")
	}
	if !bytesEqualRFM9x(conn.writes[len(conn.writes)-1], []byte{0x81, 0x81}) {
		t.Errorf("send did not return to standby: got %v", conn.writes[len(conn.writes)-1])
	}

	if err := sensor.Send(make([]byte, 256)); err == nil {
		t.Errorf("expected error for payload > 255 bytes")
	}
}

func TestRFM9xReceive(t *testing.T) {
	conn := newRFM9xConnection()
	sensor, err := NewRFM95Minimal(conn, 915000000)
	if err != nil {
		t.Fatalf("NewRFM95Minimal: %v", err)
	}

	conn.setRegister(0x12, 0x40) // IRQ_FLAGS: RX_DONE
	conn.setRegister(0x10, 0x00) // FIFO_RX_CURRENT
	conn.setRegister(0x13, 0x03) // RX_NB_BYTES
	conn.setRegister(0x00, 0xAA, 0xBB, 0xCC)
	conn.writes = nil
	payload, err := sensor.Receive(100)
	if err != nil {
		t.Fatalf("Receive: %v", err)
	}
	if !bytesEqualRFM9x(conn.writes[2], []byte{0x81, 0x86}) {
		t.Errorf("receive rx mode: got %v", conn.writes[2])
	}
	if !bytesEqualRFM9x(payload, []byte{0xAA, 0xBB, 0xCC}) {
		t.Errorf("receive payload: got %v", payload)
	}

	conn.setRegister(0x12, 0x00)
	timeoutPayload, err := sensor.Receive(10)
	if err != nil {
		t.Fatalf("Receive timeout: %v", err)
	}
	if timeoutPayload != nil {
		t.Errorf("expected nil payload on timeout, got %v", timeoutPayload)
	}
}

func TestRFM9xFullConfigureAndTxPower(t *testing.T) {
	conn := newRFM9xConnection()
	full, err := NewRFM95Full(conn, 915000000, nil, nil)
	if err != nil {
		t.Fatalf("NewRFM95Full: %v", err)
	}

	conn.writes = nil
	if err := full.Configure(9, 125.0, 5, true); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if !hasWrite(conn, []byte{0xB1, 0x03}) {
		t.Errorf("configure detection opt: got %v", conn.writes)
	}
	if !hasWrite(conn, []byte{0x9D, 0x72}) {
		t.Errorf("configure modem config 1: got %v", conn.writes)
	}
	if !hasWrite(conn, []byte{0x9E, 0x97}) {
		t.Errorf("configure modem config 2: got %v", conn.writes)
	}

	if err := full.Configure(9, 999.0, 5, true); err == nil {
		t.Errorf("expected error for invalid bandwidth")
	}

	rfm97Conn := newRFM9xConnection()
	rfm97, err := NewRFM97Full(rfm97Conn, 915000000, nil, nil)
	if err != nil {
		t.Fatalf("NewRFM97Full: %v", err)
	}
	if err := rfm97.Configure(10, 125.0, 5, true); err == nil {
		t.Errorf("expected error for sf over RFM97's max_sf(9)")
	}

	conn.writes = nil
	if err := full.SetFrequency(868000000); err != nil {
		t.Fatalf("SetFrequency: %v", err)
	}
	if !bytesEqualRFM9x(conn.writes[0], []byte{0x86, 0xD9}) {
		t.Errorf("set_frequency frf msb: got %v", conn.writes[0])
	}

	conn.writes = nil
	if err := full.SetTxPower(20, true); err != nil {
		t.Fatalf("SetTxPower: %v", err)
	}
	if !hasWrite(conn, []byte{0xCD, 0x87}) || !hasWrite(conn, []byte{0x8B, 0x3B}) || !hasWrite(conn, []byte{0x89, 0x8F}) {
		t.Errorf("set_tx_power high power path: got %v", conn.writes)
	}

	conn.writes = nil
	if err := full.SetTxPower(10, false); err != nil {
		t.Fatalf("SetTxPower rfo: %v", err)
	}
	if !hasWrite(conn, []byte{0x89, 0x7A}) {
		t.Errorf("set_tx_power rfo path: got %v", conn.writes)
	}
}

func TestRFM9xTelemetryAndPowerControl(t *testing.T) {
	conn := newRFM9xConnection()
	full, err := NewRFM95Full(conn, 915000000, nil, nil)
	if err != nil {
		t.Fatalf("NewRFM95Full: %v", err)
	}

	conn.writes = nil
	if err := full.Standby(); err != nil {
		t.Fatalf("Standby: %v", err)
	}
	if !bytesEqualRFM9x(conn.writes[len(conn.writes)-1], []byte{0x81, 0x81}) {
		t.Errorf("standby: got %v", conn.writes)
	}

	conn.writes = nil
	if err := full.Sleep(); err != nil {
		t.Fatalf("Sleep: %v", err)
	}
	if !bytesEqualRFM9x(conn.writes[len(conn.writes)-1], []byte{0x81, 0x80}) {
		t.Errorf("sleep: got %v", conn.writes)
	}

	v, err := full.Version()
	if err != nil || v != 0x12 {
		t.Errorf("Version: got %v, %v", v, err)
	}

	conn.setRegister(0x1B, 100)
	rssi, err := full.RSSI()
	if err != nil || rssi != -137+100 {
		t.Errorf("RSSI: got %v, %v", rssi, err)
	}
	conn.setRegister(0x1A, 90)
	lpr, err := full.LastPacketRSSI()
	if err != nil || lpr != -137+90 {
		t.Errorf("LastPacketRSSI: got %v, %v", lpr, err)
	}
	conn.setRegister(0x19, 20)
	snr, err := full.LastPacketSNR()
	if err != nil || absRFM9x(snr-5.0) > 1e-9 {
		t.Errorf("LastPacketSNR positive: got %v, %v", snr, err)
	}
	conn.setRegister(0x19, 0xF4)
	snr2, err := full.LastPacketSNR()
	if err != nil || absRFM9x(snr2-(-3.0)) > 1e-9 {
		t.Errorf("LastPacketSNR negative: got %v, %v", snr2, err)
	}
}

func TestRFM9xReceiveContinuousAndReadPacket(t *testing.T) {
	conn := newRFM9xConnection()
	full, err := NewRFM95Full(conn, 915000000, nil, nil)
	if err != nil {
		t.Fatalf("NewRFM95Full: %v", err)
	}

	conn.writes = nil
	if err := full.ReceiveContinuous(); err != nil {
		t.Fatalf("ReceiveContinuous: %v", err)
	}
	if !bytesEqualRFM9x(conn.writes[len(conn.writes)-1], []byte{0x81, 0x85}) {
		t.Errorf("receive_continuous rx cont mode: got %v", conn.writes)
	}

	conn.setRegister(0x12, 0x40)
	conn.setRegister(0x10, 0x00)
	conn.setRegister(0x13, 0x02)
	conn.setRegister(0x00, 0x11, 0x22)
	payload, err := full.ReadPacket()
	if err != nil || !bytesEqualRFM9x(payload, []byte{0x11, 0x22}) {
		t.Errorf("ReadPacket: got %v, %v", payload, err)
	}

	conn.setRegister(0x12, 0x00)
	nonePayload, err := full.ReadPacket()
	if err != nil || nonePayload != nil {
		t.Errorf("ReadPacket none: got %v, %v", nonePayload, err)
	}

	conn.writes = nil
	if err := full.StopReceive(); err != nil {
		t.Fatalf("StopReceive: %v", err)
	}
	if !bytesEqualRFM9x(conn.writes[len(conn.writes)-1], []byte{0x81, 0x81}) {
		t.Errorf("stop_receive returns to standby: got %v", conn.writes)
	}
}

func TestRFM9xReceiveInterrupt(t *testing.T) {
	conn := newRFM9xConnection()
	full, err := NewRFM95Full(conn, 915000000, nil, nil)
	if err != nil {
		t.Fatalf("NewRFM95Full: %v", err)
	}
	if _, err := full.Receive(2000, true); err == nil {
		t.Errorf("expected error: useInterrupt requires dio0Pin")
	}

	dio0Conn := newRFM9xConnection()
	dio0Conn.setRegister(0x12, 0x40)
	dio0Conn.setRegister(0x10, 0x00)
	dio0Conn.setRegister(0x13, 0x01)
	dio0Conn.setRegister(0x00, 0x99)
	dio0 := &fakeInputPinRFM9x{}
	fullWithDio0, err := NewRFM95Full(dio0Conn, 915000000, nil, dio0)
	if err != nil {
		t.Fatalf("NewRFM95Full with dio0: %v", err)
	}
	payload, err := fullWithDio0.Receive(2000, true)
	if err != nil || !bytesEqualRFM9x(payload, []byte{0x99}) {
		t.Errorf("interrupt receive: got %v, %v", payload, err)
	}
}

func TestRFM9xReset(t *testing.T) {
	conn := newRFM9xConnection()
	full, err := NewRFM95Full(conn, 915000000, nil, nil)
	if err != nil {
		t.Fatalf("NewRFM95Full: %v", err)
	}
	if err := full.Reset(); err == nil {
		t.Errorf("expected error resetting without a resetPin")
	}

	pin := &fakeOutputPinRFM9x{}
	conn2 := newRFM9xConnection()
	withReset, err := NewRFM95Full(conn2, 915000000, pin, nil)
	if err != nil {
		t.Fatalf("NewRFM95Full with resetPin: %v", err)
	}
	if len(pin.calls) != 2 || pin.calls[0] != false || pin.calls[1] != true {
		t.Errorf("init reset pulse: got %v", pin.calls)
	}
	pin.calls = nil
	if err := withReset.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if len(pin.calls) != 2 || pin.calls[0] != false || pin.calls[1] != true {
		t.Errorf("reset pulse: got %v", pin.calls)
	}
}

func absRFM9x(f float64) float64 {
	if f < 0 {
		return -f
	}
	return f
}
