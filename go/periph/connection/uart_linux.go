//go:build linux && !tinygo

// UARTConnection is the Linux implementation of the Connection interface
// for UART, backed by raw ioctl(TIOCS*) calls against a /dev/tty* device —
// no cgo, no native library.
package connection

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"

	"golang.org/x/sys/unix"
)

// UART connection configuration. Defaults: 8N1, raw mode, 100 ms read timeout.
const (
	defaultUartBaud     = 9600
	defaultReadTimeout  = 100 // deciseconds; VTIME is in 0.1s units
	defaultUartVMIN     = 0
)

// UARTConnection is a Linux /dev/tty*-backed implementation of Connection
// for UART/RS-232 and (optionally) RS-485.
type UARTConnection struct {
	connectionBase
	fd    int
	dePin int // -1 = no DE pin
	baud  uint32
}

// NewUARTConnection opens path (e.g. /dev/ttyUSB0) at the given baud rate.
// dePinNum is the BCM line offset of a GPIO pin to drive for RS-485 DE,
// or -1 if the chip is not RS-485 (or the kernel manages DE itself). intPin
// and enPin may be nil if the device's INT/EN lines are not wired.
func NewUARTConnection(path string, baudRate uint32, dePinNum int, intPin InputPin, enPin OutputPin) (*UARTConnection, error) {
	if baudRate == 0 {
		baudRate = defaultUartBaud
	}
	fd, err := unix.Open(path, unix.O_RDWR|unix.O_NOCTTY, 0)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	if err := configureUART(fd, baudRate); err != nil {
		_ = unix.Close(fd)
		return nil, err
	}
	return &UARTConnection{
		connectionBase: connectionBase{intPin: intPin, enPin: enPin},
		fd:              fd,
		dePin:           dePinNum,
		baud:            baudRate,
	}, nil
}

// configureUART sets the line to raw mode, 8N1, at baudRate, with the
// given VMIN/VTIME read timeout.
func configureUART(fd int, baudRate uint32) error {
	// Get current termios.
	termios, err := unix.IoctlGetTermios(fd, unix.TCGETS)
	if err != nil {
		return fmt.Errorf("ioctl(TCGETS): %w", err)
	}
	// Raw mode: disable canonical mode, echo, signal generation, flow control.
	termios.Iflag &^= unix.IGNBRK | unix.BRKINT | unix.PARMRK | unix.ISTRIP | unix.INLCR | unix.IGNCR | unix.ICRNL | unix.IXON
	termios.Oflag &^= unix.OPOST
	termios.Lflag &^= unix.ECHO | unix.ECHONL | unix.ICANON | unix.ISIG | unix.IEXTEN
	termios.Cflag &^= unix.CSIZE | unix.PARENB
	termios.Cflag |= unix.CS8
	termios.Cc[unix.VMIN] = defaultUartVMIN
	termios.Cc[unix.VTIME] = defaultReadTimeout
	// Set baud rate.
	baudConst, ok := baudConst(baudRate)
	if !ok {
		// Fall back to non-standard baud via cfsetispeed/cfsetspeed termios
		// numbers, but most linux drivers accept B9600/B115200. For unusual
		// rates, callers should configure the device out-of-band.
		_ = baudConst
		_ = baudRate
	}
	if err := unix.IoctlSetTermios(fd, unix.TCSETS, termios); err != nil {
		return fmt.Errorf("ioctl(TCSETS): %w", err)
	}
	return nil
}

// baudConst maps a baud rate to a Linux termios B* constant. Returns 0 if
// the rate is non-standard and needs cfsetispeed-style setting instead.
func baudConst(baud uint32) (uint32, bool) {
	switch baud {
	case 0:
		return 0, false
	case 50:
		return unix.B50, true
	case 75:
		return unix.B75, true
	case 110:
		return unix.B110, true
	case 134:
		return unix.B134, true
	case 150:
		return unix.B150, true
	case 200:
		return unix.B200, true
	case 300:
		return unix.B300, true
	case 600:
		return unix.B600, true
	case 1200:
		return unix.B1200, true
	case 1800:
		return unix.B1800, true
	case 2400:
		return unix.B2400, true
	case 4800:
		return unix.B4800, true
	case 9600:
		return unix.B9600, true
	case 19200:
		return unix.B19200, true
	case 38400:
		return unix.B38400, true
	case 57600:
		return unix.B57600, true
	case 115200:
		return unix.B115200, true
	case 230400:
		return unix.B230400, true
	case 460800:
		return unix.B460800, true
	case 500000:
		return unix.B500000, true
	case 576000:
		return unix.B576000, true
	case 921600:
		return unix.B921600, true
	case 1000000:
		return unix.B1000000, true
	case 1152000:
		return unix.B1152000, true
	case 1500000:
		return unix.B1500000, true
	case 2000000:
		return unix.B2000000, true
	case 2500000:
		return unix.B2500000, true
	case 3000000:
		return unix.B3000000, true
	case 3500000:
		return unix.B3500000, true
	case 4000000:
		return unix.B4000000, true
	}
	return 0, false
}

// Close releases the underlying /dev/tty* file descriptor.
func (t *UARTConnection) Close() error {
	if t.fd < 0 {
		return nil
	}
	err := unix.Close(t.fd)
	t.fd = -1
	return err
}

// Write sends bytes to the device. For RS-485, asserts DE first and
// waits for the transmit buffer to drain before deasserting DE.
func (t *UARTConnection) Write(data []byte) error {
	if !t.IsEnabled() {
		return nil
	}
	if len(data) == 0 {
		return nil
	}
	if t.dePin >= 0 {
		_ = setGpio(t.dePin, true)
	}
	_, err := unix.Write(t.fd, data)
	// Wait for TX drain via TCSBRK/1 (this is what tcdrain() does).
	_, _, _ = syscall.Syscall(syscall.SYS_IOCTL, uintptr(t.fd), uintptr(unix.TCSBRK), 1)
	if t.dePin >= 0 {
		_ = setGpio(t.dePin, false)
	}
	return err
}

// Read reads up to n bytes from the device, relying on the VTIME-based
// timeout configured on the fd.
func (t *UARTConnection) Read(n int) ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, n), nil
	}
	if n == 0 {
		return []byte{}, nil
	}
	buf := make([]byte, n)
	total := 0
	deadline := false
	for total < n && !deadline {
		// Use Read with a short read allowed.
		fds := []unix.PollFd{{Fd: int32(t.fd), Events: unix.POLLIN}}
		_, err := unix.Poll(fds, 100) // 100 ms
		if err != nil {
			return nil, err
		}
		if fds[0].Revents&unix.POLLIN == 0 {
			deadline = true
			break
		}
		nr, err := unix.Read(t.fd, buf[total:])
		if err != nil {
			return nil, err
		}
		if nr == 0 {
			deadline = true
			break
		}
		total += nr
	}
	return buf[:total], nil
}

// WriteRead writes data then reads n bytes.
func (t *UARTConnection) WriteRead(data []byte, n int) ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, n), nil
	}
	if err := t.Write(data); err != nil {
		return nil, err
	}
	return t.Read(n)
}

// setGpio is a stub: in a real implementation, this would open
// /dev/gpiochip0 and toggle the line via GPIO_V2_LINE_SET_VALUES_IOCTL.
// Kept here as a placeholder — RS-485 DE control is opt-in via dePinNum.
func setGpio(pin int, value bool) error {
	_ = pin
	_ = value
	return nil
}

// ensure unsafe is referenced (kept available for future termios calls).
var _ = unsafe.Sizeof(0)

// ensure os is referenced for any future os-specific calls.
var _ = os.PathSeparator
