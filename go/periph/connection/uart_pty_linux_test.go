//go:build linux && !tinygo

package connection

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"testing"
	"unsafe"

	"golang.org/x/sys/unix"
)

// openPty returns the master end and the slave path of a new pseudo-terminal.
func openPty(t *testing.T) (*os.File, string) {
	t.Helper()
	master, err := os.OpenFile("/dev/ptmx", os.O_RDWR|unix.O_NOCTTY, 0)
	if err != nil {
		t.Skipf("no /dev/ptmx: %v", err)
	}
	unlock := 0
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, master.Fd(), unix.TIOCSPTLCK, uintptr(unsafe.Pointer(&unlock))); errno != 0 {
		t.Fatalf("TIOCSPTLCK: %v", errno)
	}
	n, err := unix.IoctlGetInt(int(master.Fd()), unix.TIOCGPTN)
	if err != nil {
		t.Fatalf("TIOCGPTN: %v", err)
	}
	return master, fmt.Sprintf("/dev/pts/%d", n)
}

// A large Write delivers every byte. A blocking tty write() normally loops in
// the kernel, so a short count only appears when a signal interrupts it; the
// Write loop (#767: the count used to be discarded) must still end up here.
func TestUARTWriteQueuesEveryByte(t *testing.T) {
	master, slave := openPty(t)
	defer master.Close()
	u, err := NewUARTConnection(slave, 115200, -1, nil, nil)
	if err != nil {
		t.Fatalf("open %s: %v", slave, err)
	}
	defer u.Close()

	payload := bytes.Repeat([]byte("0123456789abcdef"), 4096) // 64 KiB, far above the pty buffer
	got := make(chan []byte, 1)
	go func() {
		b, _ := io.ReadAll(io.LimitReader(master, int64(len(payload))))
		got <- b
	}()
	if err := u.Write(payload); err != nil {
		t.Fatalf("Write: %v", err)
	}
	if b := <-got; !bytes.Equal(b, payload) {
		t.Fatalf("master received %d bytes, want %d identical bytes", len(b), len(payload))
	}
}

// Write errors carry the errno instead of being dropped.
func TestUARTWriteReportsErrno(t *testing.T) {
	master, slave := openPty(t)
	u, err := NewUARTConnection(slave, 115200, -1, nil, nil)
	if err != nil {
		t.Fatalf("open %s: %v", slave, err)
	}
	defer u.Close()
	master.Close() // hang up: further writes on the slave fail with EIO
	err = u.Write([]byte("x"))
	if err == nil {
		t.Fatal("Write after hang-up returned nil")
	}
	if !bytes.Contains([]byte(err.Error()), []byte("uart:")) {
		t.Fatalf("unexpected error text: %v", err)
	}
}
