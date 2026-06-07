# Command Panel

Command Panel is a simple desktop tool that lets you create buttons for frequently used command sets.

Instead of typing the same commands again and again, you can save them once, then run them by clicking a button.

## Features

- Display command buttons in a square grid layout.
- Single-click a button to run its commands.
- Double-click a button to edit or delete it.
- Add new command buttons using the `+` button.
- Show command output in the built-in console area.
- Save command buttons to `commands.json`.
- Reload command buttons from file.
- Support multi-line command sets.
- Support `cd <path>` as a persistent working directory change inside the app.

## Example Commands

### Check Git Status

```text
git status
```

### Move to B-Material Repo

```text
cd D:\Desktop\AndroidProjects\B-Material
```

### Build B-Material Debug APK

```text
cd D:\Desktop\AndroidProjects\B-Material
./gradlew assembleDebug
```

On Windows, you can also use:

```text
gradlew.bat assembleDebug
```

## Data Storage

Command buttons are stored in:

```text
commands.json
```

Example:

```json
{
  "commands": [
    {
      "id": "check-git-status",
      "title": "check git status",
      "commands": "git status"
    }
  ]
}
```

## Run

Recommended on Windows:

```bash
pythonw command_panel.pyw
```

Normal run:

```bash
python command_panel.py
```

## Notes

Only save commands that you trust. This tool runs the commands exactly as configured.

## Requirements

- Python 3.10+
- Tkinter
