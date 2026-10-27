//go:build linux

package main

import (
	"os"
	"strconv"
	"strings"
	"syscall"
)

// Host numbers for the admin console, read straight from /proc: no agent, no dependency.

func readCPU() (idle, total uint64, ok bool) {
	b, err := os.ReadFile("/proc/stat")
	if err != nil {
		return
	}
	line, _, _ := strings.Cut(string(b), "\n")
	f := strings.Fields(line) // cpu user nice system idle iowait irq softirq steal
	if len(f) < 5 || f[0] != "cpu" {
		return
	}
	for i, v := range f[1:min(len(f), 9)] {
		n, _ := strconv.ParseUint(v, 10, 64)
		total += n
		if i == 3 || i == 4 {
			idle += n
		}
	}
	return idle, total, true
}

func readMem() (total, avail uint64) {
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return
	}
	for _, line := range strings.Split(string(b), "\n") {
		k, v, _ := strings.Cut(line, ":")
		f := strings.Fields(v)
		if len(f) == 0 {
			continue
		}
		n, _ := strconv.ParseUint(f[0], 10, 64)
		switch k {
		case "MemTotal":
			total = n * 1024
		case "MemAvailable":
			avail = n * 1024
		}
	}
	return
}

func readLoad() [3]float64 {
	var l [3]float64
	if b, err := os.ReadFile("/proc/loadavg"); err == nil {
		for i, f := range strings.Fields(string(b)) {
			if i < 3 {
				l[i], _ = strconv.ParseFloat(f, 64)
			}
		}
	}
	return l
}

// readProc returns this process's resident memory and open file descriptor count.
func readProc() (rss uint64, fds int) {
	if b, err := os.ReadFile("/proc/self/status"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			if v, ok := strings.CutPrefix(line, "VmRSS:"); ok {
				if f := strings.Fields(v); len(f) > 0 {
					n, _ := strconv.ParseUint(f[0], 10, 64)
					rss = n * 1024
				}
			}
		}
	}
	if d, err := os.ReadDir("/proc/self/fd"); err == nil {
		fds = len(d)
	}
	return
}

func diskUsage(path string) (total, free uint64) {
	var st syscall.Statfs_t
	if syscall.Statfs(path, &st) != nil {
		return
	}
	return st.Blocks * uint64(st.Bsize), st.Bavail * uint64(st.Bsize)
}
