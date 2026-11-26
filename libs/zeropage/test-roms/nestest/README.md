# nestest

Kevin Horton's ("kevtris") CPU test ROM for NES emulators (V1.00,
2004), with its documentation (`nestest.txt`) and the reference log
(`nestest.log`): the CPU's registers and cycle count before every
instruction, as recorded by Nintendulator, an accurate emulator.

Used by `test/cljs/zeropage/nestest_test.cljs` to check `zeropage.cpu`
(the ClojureScript 6502 that nes-cljs also runs) against every line of
the log.

These are the copies widely shared by emulator projects; these came from
[nescore](https://github.com/afska/nescore) (`public/testroms`), and the
ROM is the same as the one in [jsnes](https://github.com/bfirsh/jsnes)
(`roms/nestest`). No license accompanies them.
