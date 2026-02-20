#!/usr/bin/env python3
"""NN testbench runner with Python model verification.

Generates random test vectors, runs the VHDL forward pass, and compares
outputs against the Python reference model via post_check.
"""

import os
import sys
import random
from pathlib import Path
from glob import glob
from typing import Callable

from vunit import VUnit

# Add models directory to path
sim_dir = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(sim_dir / "models"))
from nn_model import (  # noqa: E402  # ty: ignore[unresolved-import]
  TOTAL_PARAMS,
  WEIGHTS_PER_NEURON,
  nn_forward,
)

root = Path(__file__).resolve().parent
src = root.parent.parent / "src"

# --- Test data generation ---

test_data_dir = root / "test_data"
test_data_dir.mkdir(exist_ok=True)


def generate_test(seed: int) -> tuple[list[int], list[int]]:
  """Generate random params and input logits for a given seed."""
  rng = random.Random(seed)

  seed_dir = test_data_dir / f"seed_{seed}"
  seed_dir.mkdir(exist_ok=True)

  # Random 4-bit unsigned params (0-15)
  params = [rng.randint(0, 15) for _ in range(TOTAL_PARAMS)]
  with open(seed_dir / "params.txt", "w") as f:
    for p in params:
      f.write(f"{p}\n")

  # Random 12-bit signed input logits (-2048 to 2047)
  input_logits = [rng.randint(-2048, 2047) for _ in range(WEIGHTS_PER_NEURON)]
  with open(seed_dir / "input_logits.txt", "w") as f:
    for v in input_logits:
      f.write(f"{v}\n")

  return params, input_logits


def make_post_check(
  params: list[int], input_logits: list[int]
) -> Callable[[str], bool]:
  """Create a post_check closure that compares VHDL output against Python model."""

  def post_check(output_path: str) -> bool:
    # Run Python reference model
    expected_logits, expected_actions = nn_forward(params, input_logits)

    # Read VHDL output
    output_file = os.path.join(output_path, "output.txt")
    with open(output_file) as f:
      lines = [line.strip() for line in f if line.strip()]

    actual_logits = [int(lines[i]) for i in range(3)]
    actual_actions = [int(lines[i]) for i in range(3, 6)]

    ok = True
    for i, name in enumerate(["left", "right", "jump"]):
      if actual_logits[i] != expected_logits[i]:
        print(
          f"  Logit {name} mismatch: expected {expected_logits[i]}, "
          f"got {actual_logits[i]}"
        )
        ok = False
      if actual_actions[i] != int(expected_actions[i]):
        print(
          f"  Action {name} mismatch: expected {expected_actions[i]}, "
          f"got {actual_actions[i]}"
        )
        ok = False

    if ok:
      print(
        f"  Logits match: {actual_logits}, "
        f"actions: {['left' if a else '' for a in expected_actions]}"
      )

    return ok

  return post_check


# --- VUnit setup ---

vu = VUnit.from_argv()
vu.add_vhdl_builtins()
lib = vu.add_library("lib")

# Add source files
src_files = [f for f in glob(str(src / "*.vhd")) if os.path.basename(f) != "top.vhd"]
lib.add_source_files(src_files)
lib.add_source_files(str(src / "imports" / "*.vhd"))
lib.add_source_files(str(src / "neural_network" / "*.vhd"))

# Add testbench
lib.add_source_files(str(root / "tb_nn.vhd"))

# Generate test configs
tb = lib.test_bench("tb_nn")

for seed in [42, 123, 999, 0, 65535]:
  params, input_logits = generate_test(seed)
  tb.add_config(
    name=f"seed_{seed}",
    generics={"input_path": str(test_data_dir / f"seed_{seed}") + "/"},
    post_check=make_post_check(params, input_logits),
  )

vu.main()
