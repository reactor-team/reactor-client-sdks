# Pygame Example Application

A pygame-based application demonstrating the Reactor Python SDK with real-time video streaming and dynamic UI controls.

## Features

- Real-time video stream display from Reactor models
- Dynamic controller UI that builds controls based on model capabilities
- Support for sliders, checkboxes, dropdowns, and text inputs
- Automatic command execution when adjusting slider values

## Prerequisites

- Python 3.10 or higher
- A Reactor API key (get one at https://reactor.inc)

## Installation

1. Install the Reactor SDK (from the parent directory):

```bash
cd /path/to/py-sdk
pip install -e .
```

2. Install pygame:

```bash
pip install pygame
```

Or install all dependencies:

```bash
cd examples/pygame_app
pip install -r requirements.txt
```

## Usage

### Local Development

Connect to a local Reactor model running at `localhost:8080`:

```bash
python main.py --local
```

### Remote Connection

Connect to a remote Reactor model using your API key:

```bash
python main.py --api-key REACTOR_API_KEY --model your-model-name
```

### Command Line Options

| Option | Short | Description |
|--------|-------|-------------|
| `--api-key` | `-k` | Reactor API key for authentication |
| `--model` | `-m` | Model name to connect to (default: `example-model`) |
| `--local` | `-l` | Connect to local coordinator at `localhost:8080` |
| `--coordinator-url` | `-c` | Custom coordinator URL |
| `--verbose` | `-v` | Enable verbose logging |

### Examples

```bash
# Local development
python main.py --local --model my-local-model

# Production with API key
python main.py -k sk_live_xxxxx -m production-model

# With verbose logging
python main.py --local -v

# Custom coordinator
python main.py -k REACTOR_API_KEY -m my-model -c https://custom-coordinator.example.com
```

## Controls

- **ESC**: Quit the application
- **Mouse Click**: Interact with controller UI elements
- **Mouse Scroll**: Scroll the controller panel

## UI Layout

```
┌─────────────────────────────────┬──────────────────┐
│                                 │                  │
│                                 │  Reactor         │
│      Video Stream               │  Commands        │
│      (960 x 720)                │                  │
│                                 │  [Dynamic UI     │
│                                 │   controls       │
│                                 │   based on       │
│                                 │   model caps]    │
│                                 │                  │
└─────────────────────────────────┴──────────────────┘
```

## How It Works

1. **Connection**: The app connects to the Reactor coordinator, which assigns a GPU machine
2. **Capabilities**: Once connected, it requests the model's capabilities schema
3. **Dynamic UI**: The controller parses the schema and builds appropriate UI controls
4. **Commands**: User interactions trigger commands sent to the model via WebRTC data channel
5. **Video**: The model's video output is streamed back and displayed in real-time

## Troubleshooting

### "No API key provided" error
Make sure to provide either `--api-key` or `--local` flag.

### Video not displaying
- Check that the model is running and producing output
- Verify the connection status indicator (green = connected)
- Enable verbose logging with `-v` to see detailed connection info

### Controller not showing commands
- Wait a few seconds after connection for capabilities to be received
- The app automatically retries requesting capabilities every 5 seconds
- Check verbose logs for any errors in the capabilities response
