import json
import locale
import os
import queue
import subprocess
import threading
import time
import tkinter as tk
from dataclasses import asdict, dataclass
from pathlib import Path
from tkinter import messagebox
from typing import Optional
from uuid import uuid4

APP_TITLE = "Command Panel"
DATA_FILE = Path(__file__).with_name("commands.json")
ADD_ICON_FILE = Path(__file__).with_name("add_button.png")

# Add default command buttons here if you want the app to start with built-in data.
DEFAULT_COMMANDS = []
# Example:
# DEFAULT_COMMANDS = [
#     {
#         "id": "check-git-status",
#         "title": "check git status",
#         "commands": "git status",
#     },
#     {
#         "id": "move-to-b-material",
#         "title": "move to B-Material repo",
#         "commands": r"cd D:\Desktop\AndroidProjects\B-Material",
#     },
#     {
#         "id": "build-b-material-debug",
#         "title": "build B-Material APK debug",
#         "commands": r"cd D:\Desktop\AndroidProjects\B-Material\n./gradlew assembleDebug",
#     },
# ]


@dataclass
class CommandItem:
    id: str
    title: str
    commands: str


class CommandDialog(tk.Toplevel):
    def __init__(
        self,
        master: tk.Tk,
        title: str,
        initial_title: str = "",
        initial_commands: str = "",
        show_delete: bool = False,
        on_save=None,
        on_delete=None,
    ):
        super().__init__(master)
        self.title(title)
        self.resizable(True, True)
        self.minsize(560, 440)
        self.transient(master)
        self.grab_set()

        self.on_save = on_save
        self.on_delete = on_delete

        container = tk.Frame(self, padx=16, pady=16)
        container.pack(fill="both", expand=True)

        tk.Label(container, text="Display name", anchor="w").pack(fill="x")
        self.title_entry = tk.Entry(container)
        self.title_entry.pack(fill="x", pady=(4, 12))
        self.title_entry.insert(0, initial_title)

        tk.Label(container, text="Commands", anchor="w").pack(fill="x")
        tk.Label(
            container,
            text="Write one command per line. Use cd <path> to change the panel working directory.",
            anchor="w",
            fg="#666666",
        ).pack(fill="x", pady=(0, 4))

        text_frame = tk.Frame(container)
        text_frame.pack(fill="both", expand=True, pady=(4, 12))

        self.commands_text = tk.Text(text_frame, wrap="none", undo=True)
        self.commands_text.pack(side="left", fill="both", expand=True)
        self.commands_text.insert("1.0", initial_commands)

        y_scrollbar = tk.Scrollbar(text_frame, command=self.commands_text.yview)
        y_scrollbar.pack(side="right", fill="y")
        self.commands_text.configure(yscrollcommand=y_scrollbar.set)

        x_scrollbar = tk.Scrollbar(container, orient="horizontal", command=self.commands_text.xview)
        x_scrollbar.pack(fill="x")
        self.commands_text.configure(xscrollcommand=x_scrollbar.set)

        button_row = tk.Frame(container)
        button_row.pack(fill="x", pady=(12, 0))

        if show_delete:
            delete_btn = tk.Button(
                button_row,
                text="Delete",
                command=self._delete,
                bg="#ffdddd",
                activebackground="#ffcccc",
            )
            delete_btn.pack(side="left")

        cancel_btn = tk.Button(button_row, text="Cancel", command=self.destroy)
        cancel_btn.pack(side="right", padx=(8, 0))

        save_btn = tk.Button(button_row, text="Save", command=self._save)
        save_btn.pack(side="right")

        self.bind("<Escape>", lambda _event: self.destroy())
        self.bind("<Control-s>", lambda _event: self._save())

        self.title_entry.focus_set()
        self._center_on_parent(master)

    def _center_on_parent(self, parent: tk.Tk):
        self.update_idletasks()
        parent_x = parent.winfo_rootx()
        parent_y = parent.winfo_rooty()
        parent_w = parent.winfo_width()
        parent_h = parent.winfo_height()
        w = self.winfo_width()
        h = self.winfo_height()
        x = parent_x + max((parent_w - w) // 2, 0)
        y = parent_y + max((parent_h - h) // 2, 0)
        self.geometry(f"+{x}+{y}")

    def _save(self):
        title = self.title_entry.get().strip()
        commands = self.commands_text.get("1.0", "end-1c")

        if not title:
            messagebox.showwarning("Missing name", "Please enter a display name for this command button.")
            return
        if not commands.strip():
            messagebox.showwarning("Missing commands", "Please enter at least one command.")
            return

        if self.on_save:
            self.on_save(title, commands)
        self.destroy()

    def _delete(self):
        if messagebox.askyesno("Delete command", "Are you sure you want to delete this command button?"):
            if self.on_delete:
                self.on_delete()
            self.destroy()


class CommandPanelApp:
    DEFAULT_GRID_COLUMNS = 4
    MAX_GRID_COLUMNS = 8
    BUTTON_SIZE = 88
    CELL_GAP = 12
    SINGLE_CLICK_DELAY_MS = 280
    OUTPUT_POLL_MS = 80
    STOP_ON_ERROR = True

    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry("980x700")
        self.root.minsize(760, 520)

        self.commands: list[CommandItem] = self._load_commands()
        self.add_icon: Optional[tk.PhotoImage] = self._load_add_icon()

        self.pending_click_after_id: Optional[str] = None
        self.last_click_command_id: Optional[str] = None
        self.last_click_time = 0.0
        self.current_grid_columns = self.DEFAULT_GRID_COLUMNS
        self.resize_after_id: Optional[str] = None

        self.current_working_dir = Path.cwd()
        self.output_queue: queue.Queue[tuple[str, str]] = queue.Queue()
        self.runner_thread: Optional[threading.Thread] = None
        self.running_process: Optional[subprocess.Popen] = None
        self.process_lock = threading.Lock()
        self.is_running = False
        self.stop_requested = False

        self._build_ui()
        self._render_grid()
        self.root.after(self.OUTPUT_POLL_MS, self._drain_output_queue)

    def _build_ui(self):
        root_frame = tk.Frame(self.root, padx=12, pady=12)
        root_frame.pack(fill="both", expand=True)

        header = tk.Frame(root_frame)
        header.pack(fill="x", pady=(0, 10))

        tk.Label(
            header,
            text=APP_TITLE,
            font=("Segoe UI", 15, "bold"),
            anchor="w",
        ).pack(side="left")

        tk.Button(header, text="Reload", command=self._reload_from_disk).pack(side="right", padx=(6, 0))
        self.stop_button = tk.Button(header, text="Stop", command=self._stop_running_command, state="disabled")
        self.stop_button.pack(side="right", padx=(6, 0))
        tk.Button(header, text="Clear Console", command=self._clear_console).pack(side="right", padx=(6, 0))

        help_text = "Single-click to run. Double-click to edit or delete. Use + to add a new command button."
        tk.Label(root_frame, text=help_text, anchor="w", fg="#555555").pack(fill="x", pady=(0, 8))

        self.cwd_var = tk.StringVar(value=f"Current directory: {self.current_working_dir}")
        tk.Label(root_frame, textvariable=self.cwd_var, anchor="w", fg="#444444").pack(fill="x", pady=(0, 8))

        main_area = tk.PanedWindow(root_frame, orient="vertical", sashrelief="raised", sashwidth=6)
        main_area.pack(fill="both", expand=True)

        canvas_frame = tk.Frame(main_area)
        main_area.add(canvas_frame, minsize=180)

        self.canvas = tk.Canvas(canvas_frame, highlightthickness=0)
        self.canvas.pack(side="left", fill="both", expand=True)

        scrollbar = tk.Scrollbar(canvas_frame, orient="vertical", command=self.canvas.yview)
        scrollbar.pack(side="right", fill="y")
        self.canvas.configure(yscrollcommand=scrollbar.set)

        self.grid_frame = tk.Frame(self.canvas)
        self.canvas_window = self.canvas.create_window((0, 0), window=self.grid_frame, anchor="nw")

        self.grid_frame.bind("<Configure>", self._on_grid_configure)
        self.canvas.bind("<Configure>", self._on_canvas_configure)

        console_frame = tk.LabelFrame(main_area, text="Console")
        main_area.add(console_frame, minsize=180)

        console_toolbar = tk.Frame(console_frame)
        console_toolbar.pack(fill="x", padx=6, pady=(4, 2))

        self.status_var = tk.StringVar(value=f"Data file: {DATA_FILE}")
        tk.Label(console_toolbar, textvariable=self.status_var, anchor="w", fg="#2f6f2f").pack(side="left", fill="x", expand=True)

        console_text_frame = tk.Frame(console_frame)
        console_text_frame.pack(fill="both", expand=True, padx=6, pady=(0, 6))

        self.console_text = tk.Text(
            console_text_frame,
            height=12,
            wrap="none",
            state="disabled",
            bg="#111111",
            fg="#eeeeee",
            insertbackground="#eeeeee",
            font=("Consolas", 10),
        )
        self.console_text.pack(side="left", fill="both", expand=True)

        y_scrollbar = tk.Scrollbar(console_text_frame, command=self.console_text.yview)
        y_scrollbar.pack(side="right", fill="y")
        self.console_text.configure(yscrollcommand=y_scrollbar.set)

        x_scrollbar = tk.Scrollbar(console_frame, orient="horizontal", command=self.console_text.xview)
        x_scrollbar.pack(fill="x", padx=6, pady=(0, 6))
        self.console_text.configure(xscrollcommand=x_scrollbar.set)

        self._append_console_direct("Command Panel is ready.\n")
        self._append_console_direct(f"Current directory: {self.current_working_dir}\n")

    def _on_grid_configure(self, _event):
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _on_canvas_configure(self, event):
        self.canvas.itemconfigure(self.canvas_window, width=event.width)

        new_columns = self._calculate_grid_columns(event.width)
        if new_columns != self.current_grid_columns:
            self.current_grid_columns = new_columns
            if self.resize_after_id is not None:
                self.root.after_cancel(self.resize_after_id)
            self.resize_after_id = self.root.after(80, self._render_grid)

    def _calculate_grid_columns(self, available_width: Optional[int] = None) -> int:
        width = available_width or self.canvas.winfo_width() or self.root.winfo_width()
        cell_width = self.BUTTON_SIZE + self.CELL_GAP
        columns = max(1, width // cell_width)
        return min(columns, self.MAX_GRID_COLUMNS)

    def _load_add_icon(self) -> Optional[tk.PhotoImage]:
        if not ADD_ICON_FILE.exists():
            return None
        try:
            image = tk.PhotoImage(file=str(ADD_ICON_FILE))
            return image.subsample(8, 8)
        except tk.TclError:
            return None

    def _load_commands(self) -> list[CommandItem]:
        if not DATA_FILE.exists():
            return [CommandItem(**item) for item in DEFAULT_COMMANDS]

        try:
            with DATA_FILE.open("r", encoding="utf-8") as f:
                raw = json.load(f)
        except (json.JSONDecodeError, OSError) as exc:
            messagebox.showerror("JSON read error", f"Could not read commands.json:\n{exc}")
            return []

        raw_items = raw.get("commands", raw) if isinstance(raw, dict) else raw
        if not isinstance(raw_items, list):
            return []

        items: list[CommandItem] = []
        for item in raw_items:
            if not isinstance(item, dict):
                continue
            title = str(item.get("title", "")).strip()
            commands = str(item.get("commands", ""))
            if title and commands:
                items.append(
                    CommandItem(
                        id=str(item.get("id") or uuid4()),
                        title=title,
                        commands=commands,
                    )
                )
        return items

    def _save_commands(self):
        data = {"commands": [asdict(item) for item in self.commands]}
        with DATA_FILE.open("w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    def _reload_from_disk(self):
        if self.is_running:
            messagebox.showwarning("Command running", "Please stop or wait for the running command before reloading.")
            return
        self.commands = self._load_commands()
        self._render_grid()
        self.status_var.set("Reloaded commands.json")

    def _render_grid(self):
        self.resize_after_id = None

        for child in self.grid_frame.winfo_children():
            child.destroy()

        columns = self.current_grid_columns
        for col in range(self.MAX_GRID_COLUMNS):
            self.grid_frame.grid_columnconfigure(col, minsize=0, weight=0)
        for row in range(100):
            self.grid_frame.grid_rowconfigure(row, minsize=0, weight=0)

        for col in range(columns):
            self.grid_frame.grid_columnconfigure(
                col,
                minsize=self.BUTTON_SIZE + self.CELL_GAP,
                weight=0,
                uniform="command_button_column",
            )

        total_items = len(self.commands) + 1
        total_rows = (total_items + columns - 1) // columns
        for row in range(total_rows):
            self.grid_frame.grid_rowconfigure(
                row,
                minsize=self.BUTTON_SIZE + self.CELL_GAP,
                weight=0,
                uniform="command_button_row",
            )

        for index, item in enumerate(self.commands):
            row = index // columns
            col = index % columns
            self._create_command_button(item, row, col)

        add_index = len(self.commands)
        add_row = add_index // columns
        add_col = add_index % columns
        self._create_add_button(add_row, add_col)

    def _create_square_cell(self, row: int, col: int) -> tk.Frame:
        cell = tk.Frame(
            self.grid_frame,
            width=self.BUTTON_SIZE,
            height=self.BUTTON_SIZE,
        )
        cell.grid(row=row, column=col, padx=self.CELL_GAP // 2, pady=self.CELL_GAP // 2)
        cell.grid_propagate(False)
        cell.pack_propagate(False)
        return cell

    def _create_command_button(self, item: CommandItem, row: int, col: int):
        cell = self._create_square_cell(row, col)
        btn = tk.Button(
            cell,
            text=item.title,
            wraplength=self.BUTTON_SIZE - 16,
            justify="center",
            relief="raised",
            bg="#f4f7fb",
            activebackground="#e4ecfb",
            padx=4,
            pady=4,
        )
        btn.place(x=0, y=0, width=self.BUTTON_SIZE, height=self.BUTTON_SIZE)
        btn.bind("<ButtonRelease-1>", lambda _event, current=item: self._handle_command_click(current))

    def _create_add_button(self, row: int, col: int):
        cell = self._create_square_cell(row, col)
        kwargs = {
            "relief": "raised",
            "bg": "#eef7ee",
            "activebackground": "#ddf0dd",
            "command": self._open_add_dialog,
        }
        if self.add_icon is not None:
            kwargs["image"] = self.add_icon
        else:
            kwargs["text"] = "+"
            kwargs["font"] = ("Segoe UI", 26, "bold")

        btn = tk.Button(cell, **kwargs)
        btn.place(x=0, y=0, width=self.BUTTON_SIZE, height=self.BUTTON_SIZE)

    def _handle_command_click(self, item: CommandItem):
        now = time.monotonic()
        is_double_click = (
            self.last_click_command_id == item.id
            and now - self.last_click_time <= self.SINGLE_CLICK_DELAY_MS / 1000 * 1.5
        )

        if is_double_click:
            if self.pending_click_after_id is not None:
                self.root.after_cancel(self.pending_click_after_id)
                self.pending_click_after_id = None
            self.last_click_command_id = None
            self.last_click_time = 0.0
            self._open_edit_dialog(item)
            return

        if self.pending_click_after_id is not None:
            self.root.after_cancel(self.pending_click_after_id)

        self.last_click_command_id = item.id
        self.last_click_time = now
        self.pending_click_after_id = self.root.after(
            self.SINGLE_CLICK_DELAY_MS,
            lambda current=item: self._run_command_item(current),
        )

    def _run_command_item(self, item: CommandItem):
        self.pending_click_after_id = None
        self.last_click_command_id = None
        self.last_click_time = 0.0

        if self.is_running:
            messagebox.showwarning("Command running", "A command is already running. Please wait or click Stop.")
            return

        self.is_running = True
        self.stop_requested = False
        self.stop_button.configure(state="normal")
        self.status_var.set(f"Running: {item.title}")

        self.runner_thread = threading.Thread(
            target=self._run_command_worker,
            args=(item,),
            daemon=True,
        )
        self.runner_thread.start()

    def _run_command_worker(self, item: CommandItem):
        exit_code = 0
        self._queue_append("\n" + "=" * 80 + "\n")
        self._queue_append(f"Running: {item.title}\n")
        self._queue_append(f"Start directory: {self.current_working_dir}\n")
        self._queue_append("=" * 80 + "\n")

        for raw_line in item.commands.splitlines():
            command = raw_line.strip()
            if not command or command.startswith("#"):
                continue

            if self.stop_requested:
                self._queue_append("\nStopped by user.\n")
                exit_code = -1
                break

            cd_target = self._parse_cd_command(command)
            if cd_target is not None:
                if not cd_target.exists() or not cd_target.is_dir():
                    self._queue_append(f"\n[cd error] Directory does not exist: {cd_target}\n")
                    exit_code = 1
                    break

                self.current_working_dir = cd_target
                self._queue_cwd(str(self.current_working_dir))
                self._queue_append(f"\n$ cd {self.current_working_dir}\n")
                continue

            command_to_run = self._normalize_command_for_platform(command)
            self._queue_append(f"\n{self._prompt()} {command_to_run}\n")
            command_exit = self._execute_shell_command(command_to_run)
            if command_exit != 0:
                exit_code = command_exit
                self._queue_append(f"\nCommand exited with code {command_exit}.\n")
                if self.STOP_ON_ERROR:
                    self._queue_append("Stopping command set because STOP_ON_ERROR is enabled.\n")
                    break

        self._queue_append(f"\nFinished: {item.title} | exit code: {exit_code}\n")
        self.output_queue.put(("done", str(exit_code)))

    def _execute_shell_command(self, command: str) -> int:
        encoding = locale.getpreferredencoding(False) or "utf-8"
        try:
            process = subprocess.Popen(
                command,
                cwd=str(self.current_working_dir),
                shell=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                text=True,
                encoding=encoding,
                errors="replace",
            )
        except OSError as exc:
            self._queue_append(f"[error] Could not start command: {exc}\n")
            return 1

        with self.process_lock:
            self.running_process = process

        try:
            if process.stdout is not None:
                for line in process.stdout:
                    self._queue_append(line)
                    if self.stop_requested:
                        break

            if self.stop_requested and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

            return process.wait()
        finally:
            with self.process_lock:
                self.running_process = None

    def _parse_cd_command(self, command: str) -> Optional[Path]:
        lower = command.lower()
        if lower != "cd" and not lower.startswith("cd "):
            return None
        if "&&" in command or ";" in command:
            return None

        target_text = command[2:].strip()
        if target_text.lower().startswith("/d "):
            target_text = target_text[3:].strip()

        if not target_text:
            return self.current_working_dir

        if len(target_text) >= 2 and target_text[0] == target_text[-1] and target_text[0] in ('"', "'"):
            target_text = target_text[1:-1]

        target_text = os.path.expandvars(os.path.expanduser(target_text))
        target = Path(target_text)
        if not target.is_absolute():
            target = self.current_working_dir / target
        return target.resolve()

    def _normalize_command_for_platform(self, command: str) -> str:
        if os.name != "nt":
            return command

        stripped = command.strip()
        lower = stripped.lower()
        if lower.startswith("./gradlew"):
            remainder = stripped[len("./gradlew"):]
            gradlew_bat = self.current_working_dir / "gradlew.bat"
            if gradlew_bat.exists():
                return f"gradlew.bat{remainder}"
        if lower.startswith(".\\gradlew"):
            remainder = stripped[len(".\\gradlew"):]
            gradlew_bat = self.current_working_dir / "gradlew.bat"
            if gradlew_bat.exists():
                return f"gradlew.bat{remainder}"
        return command

    def _prompt(self) -> str:
        return f"[{self.current_working_dir}]>"

    def _stop_running_command(self):
        if not self.is_running:
            return
        self.stop_requested = True
        self.status_var.set("Stopping command...")
        with self.process_lock:
            if self.running_process is not None and self.running_process.poll() is None:
                try:
                    self.running_process.terminate()
                except OSError:
                    pass

    def _queue_append(self, text: str):
        self.output_queue.put(("append", text))

    def _queue_cwd(self, cwd: str):
        self.output_queue.put(("cwd", cwd))

    def _drain_output_queue(self):
        try:
            while True:
                kind, value = self.output_queue.get_nowait()
                if kind == "append":
                    self._append_console_direct(value)
                elif kind == "cwd":
                    self.cwd_var.set(f"Current directory: {value}")
                elif kind == "done":
                    self.is_running = False
                    self.stop_requested = False
                    self.stop_button.configure(state="disabled")
                    self.status_var.set(f"Finished with exit code: {value}")
        except queue.Empty:
            pass
        finally:
            self.root.after(self.OUTPUT_POLL_MS, self._drain_output_queue)

    def _append_console_direct(self, text: str):
        self.console_text.configure(state="normal")
        self.console_text.insert("end", text)
        self.console_text.see("end")
        self.console_text.configure(state="disabled")

    def _clear_console(self):
        self.console_text.configure(state="normal")
        self.console_text.delete("1.0", "end")
        self.console_text.configure(state="disabled")

    def _open_add_dialog(self):
        if self.is_running:
            messagebox.showwarning("Command running", "Please stop or wait for the running command before editing buttons.")
            return
        CommandDialog(
            self.root,
            title="Add Command",
            initial_title="",
            initial_commands="",
            show_delete=False,
            on_save=self._add_command,
        )

    def _open_edit_dialog(self, item: CommandItem):
        if self.is_running:
            messagebox.showwarning("Command running", "Please stop or wait for the running command before editing buttons.")
            return
        CommandDialog(
            self.root,
            title=f"Edit Command: {item.title}",
            initial_title=item.title,
            initial_commands=item.commands,
            show_delete=True,
            on_save=lambda title, commands: self._update_command(item.id, title, commands),
            on_delete=lambda: self._delete_command(item.id),
        )

    def _add_command(self, title: str, commands: str):
        self.commands.append(CommandItem(id=str(uuid4()), title=title, commands=commands))
        self._save_commands()
        self._render_grid()
        self.status_var.set(f"Added command: {title}")

    def _update_command(self, command_id: str, title: str, commands: str):
        for item in self.commands:
            if item.id == command_id:
                item.title = title
                item.commands = commands
                break
        self._save_commands()
        self._render_grid()
        self.status_var.set(f"Updated command: {title}")

    def _delete_command(self, command_id: str):
        before = len(self.commands)
        self.commands = [item for item in self.commands if item.id != command_id]
        if len(self.commands) != before:
            self._save_commands()
            self._render_grid()
            self.status_var.set("Deleted command")


def main():
    root = tk.Tk()
    CommandPanelApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
