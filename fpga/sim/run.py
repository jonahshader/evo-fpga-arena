#!/usr/bin/env python3
"""Run all VHDL testbenches."""

from vunit import VUnit
import os
from os.path import dirname, join, basename
import glob

root = dirname(__file__)
src = join(root, "..", "src")

vu = VUnit.from_argv()
vu.add_vhdl_builtins()

lib = vu.add_library("lib")

# Add all source files (excluding top.vhd)
src_files = [f for f in glob.glob(join(src, "*.vhd")) if basename(f) != "top.vhd"]
lib.add_source_files(src_files)
lib.add_source_files(join(src, "imports", "*.vhd"))
lib.add_source_files(join(src, "neural_network", "*.vhd"))

# Add all testbenches except nn (which has its own test.py with model comparison)
tb_files = [
  f for f in glob.glob(join(root, "*", "tb_*.vhd")) if "nn" + os.sep + "tb_nn" not in f
]
lib.add_source_files(tb_files)

vu.main()
