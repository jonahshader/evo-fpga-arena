# Development Setup

## AMD Xilinx Vitis and Vivado 2024.2

This project uses Vivado and Vitis 2024.2.

### Download Setup File

Download [AMD Unified Installer for FPGAs & Adaptive SoCs 2024.2](https://www.xilinx.com/support/download/index.html/content/xilinx/en/downloadNav/vitis.html). Ensure the version and OS is correct.

### Install Vitis/Vivado

1. Run the installer
2. Log in
3. On `Select Product to Install` screen select **Vitis** (this includes Vivado)
4. On `Vitis Unified Software Platform` screen, the following are needed: `Devices -> Install Devices for Kria SOMs and Starter Kits`, `Devices -> SoCs -> Zynq-7000`, `Devices -> SoCs -> Zynq-UltraScale+ MPSoC`, `Installation Options -> Install Cable Drivers`. You can also keep DocNav checked if you want offline documentation.

## VS Code

VS Code supports developing both VHDL and C++ so it's a good choice for this project.

To install on Windows download the installer [here](https://code.visualstudio.com/download).

Once VS Code is installed, open the repo folder.
VS Code should prompt you to install the recommended extensions, do so.
At the very least, editorconfig must be installed so that line endings, white space, etc
are setup properly.

### TerosHDL

You should be prompted to install the TerosHDL VS Code extension when first opening the workspace,
but if not search for and install TerosHDL in the Extensions Marketplace or search for @recommended.

TerosHDL provides syntax highlighting, go-to-definition and hover info, formatting, linting, testing, and more for VHDL development.
Installation instructions can be found [here](https://terostechnology.github.io/terosHDLdoc/docs/getting_started/installation).

The Python back-end for TerosHDL must be installed. For Linux the quick instructions are to run

```sh
pip3 install -r requirements.txt --break-system-packages
sudo apt install make
```

For Windows, follow the instructions from their website.

## Zed

Zed has built-in support for EditorConfig and C++ (via clangd), so those work out of the box.

### VHDL Support

Install the [zed-vhdl](https://github.com/rapgenic/zed-vhdl) extension from the Zed extension marketplace for syntax highlighting.

For LSP features (go-to-definition, hover, diagnostics), install [vhdl_ls](https://github.com/VHDL-LS/rust_hdl):

1. Install via cargo:

    ```sh
    cargo install vhdl_ls
    ```

2. The standard VHDL libraries are **not** included when installing via cargo. Download the latest release archive from [GitHub releases](https://github.com/VHDL-LS/rust_hdl/releases), extract the `vhdl_libraries/` directory, and copy it to the parent directory of the binary:

    ```sh
    # Download and extract the release (adjust version as needed)
    gh release download v0.86.0 --repo VHDL-LS/rust_hdl --pattern "*linux-gnu.zip" --dir /tmp/vhdl_ls_release
    unzip /tmp/vhdl_ls_release/vhdl_ls-x86_64-unknown-linux-gnu.zip -d /tmp/vhdl_ls_release
    cp -r /tmp/vhdl_ls_release/vhdl_ls-x86_64-unknown-linux-gnu/vhdl_libraries ~/.cargo/
    ```

3. Add the following to your Zed settings (`~/.config/zed/settings.json`):

    ```json
    {
      "languages": {
        "VHDL": {
          "language_servers": ["vhdl_ls"]
        }
      },
      "lsp": {
        "vhdl_ls": {
          "binary": {
            "path": "<home>/.cargo/bin/vhdl_ls"
          }
        }
      }
    }
    ```

The project already includes a `vhdl_ls.toml` at the repo root that configures library paths for the language server.

## Simulation

To compile the simulation, run the following in `bash` or `powershell`:

1. Create a folder for the build in the sim directory:

    ```bash
    mkdir build-debug
    cd build-debug
    ```

2. Configure the project with `CMAKE_BUILD_TYPE=Debug` or `CMAKE_BUILD_TYPE=Release`:

    ```bash
    cmake -DCMAKE_BUILD_TYPE=Debug ..
    ```

3. Build the project:

    ```bash
    cmake --build .
    ```

4. The executable will be placed at /Debug/sim.exe on Windows, TODO: where on Ubuntu?:

    ```bash
    ./Debug/sim
    ```
