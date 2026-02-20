library ieee;

library vunit_lib;
context vunit_lib.vunit_context;

use ieee.std_logic_1164.all;
use ieee.numeric_std.all;
use std.textio.all;

use work.nn_types.all;
use work.bram_types.all;
use work.decoder_funs.all;

entity tb_nn is
  generic (
    RUNNER_CFG  : string;
    input_path  : string := "";
    output_path : string := ""
  );
end entity tb_nn;

architecture tb of tb_nn is

begin

  test_process : process is

    variable layers       : layers_t        := default_layers_t;
    variable logits       : neuron_logits_t := default_neuron_logits_t;
    variable input_logits : neuron_logits_t := default_neuron_logits_t;

    file f    : text;
    variable l   : line;
    variable val : integer;

  begin
    test_runner_setup(runner, RUNNER_CFG);

    while test_suite loop
      if run("forward_pass") then
        check(input_path /= "", "input_path generic must be set");

        -- Read params and decode into layers
        layers := default_layers_t;
        file_open(f, input_path & "params.txt", read_mode);

        for i in 0 to TOTAL_PARAMS - 1 loop
          readline(f, l);
          read(l, val);
          layers := decode_address(
              layers,
              std_logic_vector(to_unsigned(val, 4)),
              to_unsigned(i, BRAM_ADDR_BITS)
            );
        end loop;

        file_close(f);

        -- Read input logits
        input_logits := default_neuron_logits_t;
        file_open(f, input_path & "input_logits.txt", read_mode);

        for i in 0 to WEIGHTS_PER_NEURON - 1 loop
          readline(f, l);
          read(l, val);
          input_logits(i) := to_signed(val, NEURON_DATA_WIDTH);
        end loop;

        file_close(f);

        -- Forward pass (matching nn.vhd main_proc)
        logits := layer_forward(layers(0), input_logits, true);

        for layer_i in 1 to LAYER_COUNT - 2 loop
          logits := layer_forward(layers(layer_i), logits, true);
        end loop;

        logits := layer_forward(layers(LAYER_COUNT - 1), logits, false);

        -- Write output
        file_open(f, output_path & "output.txt", write_mode);

        for i in 0 to 2 loop
          write(l, to_integer(logits(i)));
          writeline(f, l);
        end loop;

        -- Write actions as 1/0
        for i in 0 to 2 loop
          if logits(i) > 0 then
            write(l, 1);
          else
            write(l, 0);
          end if;
          writeline(f, l);
        end loop;

        file_close(f);
      end if;
    end loop;

    test_runner_cleanup(runner);
  end process;

end architecture tb;
