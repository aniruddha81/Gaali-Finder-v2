//go:build !linux

package main

// Host numbers exist only on Linux, where the server runs; elsewhere (local development) the
// console simply shows them as unavailable.

func readCPU() (idle, total uint64, ok bool) { return }
func readMem() (total, avail uint64)         { return }
func readLoad() [3]float64                   { return [3]float64{} }
func readProc() (rss uint64, fds int)        { return }
func diskUsage(string) (total, free uint64)  { return }
