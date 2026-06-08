import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandPanelSwing {
    private static final String APP_TITLE = "Command Panel";
    private static final String DATA_FILE_NAME = "commands.json";
    private static final String ADD_ICON_FILE_NAME = "add_button.png";

    private static final int DEFAULT_GRID_COLUMNS = 4;
    private static final int MAX_GRID_COLUMNS = 8;
    private static final int BUTTON_SIZE = 88;
    private static final int CELL_GAP = 12;
    private static final int SINGLE_CLICK_DELAY_MS = 280;
    private static final boolean STOP_ON_ERROR = true;

    private final JFrame frame = new JFrame(APP_TITLE);
    private final JPanel gridPanel = new JPanel(new GridBagLayout());
    private JScrollPane gridScrollPane;
    private JTextArea consoleText;
    private JLabel cwdLabel;
    private JLabel statusLabel;
    private JButton stopButton;

    private final Path appDir;
    private final Path dataFile;
    private final Path addIconFile;

    private final List<CommandItem> commands = new ArrayList<CommandItem>();
    private ImageIcon addIcon;
    private int currentGridColumns = DEFAULT_GRID_COLUMNS;

    private javax.swing.Timer pendingClickTimer;
    private String lastClickCommandId;
    private long lastClickTimeMs;

    private volatile boolean isRunning = false;
    private volatile boolean stopRequested = false;
    private volatile Process runningProcess = null;
    private final Object processLock = new Object();

    private Path currentWorkingDir;

    public CommandPanelSwing() {
        this.appDir = resolveAppDir();
        this.dataFile = appDir.resolve(DATA_FILE_NAME);
        this.addIconFile = appDir.resolve(ADD_ICON_FILE_NAME);
        this.currentWorkingDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        this.addIcon = loadAddIcon();
        loadCommands();
        buildUi();
        renderGrid();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // Use default look and feel if the system one is unavailable.
                }

                CommandPanelSwing app = new CommandPanelSwing();
                app.show();
            }
        });
    }

    private void show() {
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static Path resolveAppDir() {
        try {
            File location = new File(CommandPanelSwing.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (location.isFile()) {
                return location.getParentFile().toPath().toAbsolutePath().normalize();
            }
            return location.toPath().toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
    }

    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(980, 700);
        frame.setMinimumSize(new Dimension(760, 520));

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        frame.setContentPane(root);

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        root.add(topPanel, BorderLayout.NORTH);

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(header);

        JLabel titleLabel = new JLabel(APP_TITLE);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        header.add(titleLabel, BorderLayout.WEST);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton reloadButton = new JButton("Reload");
        reloadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                reloadFromDisk();
            }
        });

        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        stopButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopRunningCommand();
            }
        });

        JButton clearButton = new JButton("Clear Console");
        clearButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                consoleText.setText("");
            }
        });

        actionRow.add(clearButton);
        actionRow.add(stopButton);
        actionRow.add(reloadButton);
        header.add(actionRow, BorderLayout.EAST);

        JLabel helpLabel = new JLabel("Single-click to run. Double-click to edit or delete. Use + to add a new command button.");
        helpLabel.setForeground(new Color(85, 85, 85));
        helpLabel.setBorder(new EmptyBorder(8, 0, 0, 0));
        helpLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(helpLabel);

        cwdLabel = new JLabel("Current directory: " + currentWorkingDir.toString());
        cwdLabel.setForeground(new Color(68, 68, 68));
        cwdLabel.setBorder(new EmptyBorder(8, 0, 0, 0));
        cwdLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(cwdLabel);

        gridPanel.setBackground(UIManager.getColor("Panel.background"));
        gridScrollPane = new JScrollPane(gridPanel);
        gridScrollPane.setBorder(BorderFactory.createEtchedBorder());
        gridScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        gridScrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int newColumns = calculateGridColumns();
                if (newColumns != currentGridColumns) {
                    currentGridColumns = newColumns;
                    renderGrid();
                }
            }
        });

        JPanel consolePanel = new JPanel(new BorderLayout(0, 6));
        consolePanel.setBorder(BorderFactory.createTitledBorder("Console"));

        JPanel consoleToolbar = new JPanel(new BorderLayout());
        statusLabel = new JLabel("Data file: " + dataFile.toString());
        statusLabel.setForeground(new Color(47, 111, 47));
        consoleToolbar.add(statusLabel, BorderLayout.WEST);
        consolePanel.add(consoleToolbar, BorderLayout.NORTH);

        consoleText = new JTextArea();
        consoleText.setEditable(false);
        consoleText.setLineWrap(false);
        consoleText.setWrapStyleWord(false);
        consoleText.setFont(new Font("Consolas", Font.PLAIN, 13));
        consoleText.setBackground(new Color(17, 17, 17));
        consoleText.setForeground(new Color(238, 238, 238));
        consoleText.setCaretColor(new Color(238, 238, 238));
        DefaultCaret caret = (DefaultCaret) consoleText.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane consoleScroll = new JScrollPane(consoleText);
        consoleScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        consoleScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        consolePanel.add(consoleScroll, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, gridScrollPane, consolePanel);
        splitPane.setResizeWeight(0.45);
        splitPane.setDividerSize(7);
        splitPane.setOneTouchExpandable(true);
        root.add(splitPane, BorderLayout.CENTER);

        appendConsole("Command Panel is ready.\n");
        appendConsole("Current directory: " + currentWorkingDir + "\n");
    }

    private int calculateGridColumns() {
        int width = 0;
        if (gridScrollPane != null && gridScrollPane.getViewport() != null) {
            width = gridScrollPane.getViewport().getExtentSize().width;
        }
        if (width <= 0) {
            width = frame.getWidth();
        }
        int cellWidth = BUTTON_SIZE + CELL_GAP;
        int columns = Math.max(1, width / cellWidth);
        return Math.min(columns, MAX_GRID_COLUMNS);
    }

    private void renderGrid() {
        if (gridPanel == null) {
            return;
        }
        gridPanel.removeAll();

        int columns = calculateGridColumns();
        if (columns <= 0) {
            columns = DEFAULT_GRID_COLUMNS;
        }
        currentGridColumns = columns;

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(CELL_GAP / 2, CELL_GAP / 2, CELL_GAP / 2, CELL_GAP / 2);
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;

        int index = 0;
        for (CommandItem item : commands) {
            gbc.gridx = index % columns;
            gbc.gridy = index / columns;
            gridPanel.add(createCommandButton(item), gbc);
            index++;
        }

        gbc.gridx = index % columns;
        gbc.gridy = index / columns;
        gridPanel.add(createAddButton(), gbc);

        // Horizontal filler.
        gbc.gridx = columns;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gridPanel.add(Box.createHorizontalGlue(), gbc);

        // Vertical filler.
        gbc.gridx = 0;
        gbc.gridy = Math.max(1, (index / columns) + 1);
        gbc.gridwidth = columns + 1;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gridPanel.add(Box.createGlue(), gbc);

        gridPanel.revalidate();
        gridPanel.repaint();
    }

    private JButton createCommandButton(final CommandItem item) {
        JButton button = new JButton(toHtmlButtonText(item.title, BUTTON_SIZE - 18));
        button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setMinimumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setMaximumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setFocusPainted(false);
        button.setBackground(new Color(244, 247, 251));
        button.setToolTipText(item.title);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setVerticalAlignment(SwingConstants.CENTER);
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setVerticalTextPosition(SwingConstants.CENTER);
        button.setIconTextGap(0);
        button.setMargin(new Insets(0, 0, 0, 0));

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                handleCommandClick(item);
            }
        });
        return button;
    }

    private JButton createAddButton() {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setMinimumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setMaximumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setFocusPainted(false);
        button.setBackground(new Color(238, 247, 238));
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setVerticalAlignment(SwingConstants.CENTER);
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setVerticalTextPosition(SwingConstants.CENTER);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setToolTipText("Add Command");

        if (addIcon != null) {
            button.setIcon(addIcon);
        } else {
            button.setText("+");
            button.setFont(new Font("Segoe UI", Font.BOLD, 28));
        }
        button.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openAddDialog();
            }
        });
        return button;
    }

    private ImageIcon loadAddIcon() {
        if (!Files.exists(addIconFile)) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(addIconFile.toFile());
            if (image == null) {
                return null;
            }
            int target = 32;
            Image scaled = image.getScaledInstance(target, target, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (IOException ignored) {
            return null;
        }
    }

    private String toHtmlButtonText(String text, int widthPx) {
        return "<html><body style='margin:0;padding:0;text-align:center;'>" +
                "<div style='text-align:center;width:" + widthPx + "px;'>" + escapeHtml(text) + "</div>" +
                "</body></html>";
    }

    private static String escapeHtml(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private void handleCommandClick(final CommandItem item) {
        long now = System.currentTimeMillis();
        boolean isDoubleClick = lastClickCommandId != null
                && lastClickCommandId.equals(item.id)
                && now - lastClickTimeMs <= (long) (SINGLE_CLICK_DELAY_MS * 1.5);

        if (isDoubleClick) {
            if (pendingClickTimer != null) {
                pendingClickTimer.stop();
                pendingClickTimer = null;
            }
            lastClickCommandId = null;
            lastClickTimeMs = 0L;
            openEditDialog(item);
            return;
        }

        if (pendingClickTimer != null) {
            pendingClickTimer.stop();
        }
        lastClickCommandId = item.id;
        lastClickTimeMs = now;
        pendingClickTimer = new javax.swing.Timer(SINGLE_CLICK_DELAY_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pendingClickTimer = null;
                lastClickCommandId = null;
                lastClickTimeMs = 0L;
                runCommandItem(item);
            }
        });
        pendingClickTimer.setRepeats(false);
        pendingClickTimer.start();
    }

    private void openAddDialog() {
        if (isRunning) {
            showWarning("Command running", "Please stop or wait for the running command before editing buttons.");
            return;
        }
        CommandDialog dialog = new CommandDialog(frame, "Add Command", "", "", false);
        dialog.setSaveListener(new CommandDialog.SaveListener() {
            @Override
            public void onSave(String title, String commandsText) {
                addCommand(title, commandsText);
            }
        });
        dialog.showDialog();
    }

    private void openEditDialog(final CommandItem item) {
        if (isRunning) {
            showWarning("Command running", "Please stop or wait for the running command before editing buttons.");
            return;
        }
        CommandDialog dialog = new CommandDialog(frame, "Edit Command: " + item.title, item.title, item.commands, true);
        dialog.setSaveListener(new CommandDialog.SaveListener() {
            @Override
            public void onSave(String title, String commandsText) {
                updateCommand(item.id, title, commandsText);
            }
        });
        dialog.setDeleteListener(new CommandDialog.DeleteListener() {
            @Override
            public void onDelete() {
                deleteCommand(item.id);
            }
        });
        dialog.showDialog();
    }

    private void addCommand(String title, String commandsText) {
        commands.add(new CommandItem(UUID.randomUUID().toString(), title, commandsText));
        saveCommands();
        renderGrid();
        setStatus("Added command: " + title);
    }

    private void updateCommand(String id, String title, String commandsText) {
        for (CommandItem item : commands) {
            if (item.id.equals(id)) {
                item.title = title;
                item.commands = commandsText;
                break;
            }
        }
        saveCommands();
        renderGrid();
        setStatus("Updated command: " + title);
    }

    private void deleteCommand(String id) {
        Iterator<CommandItem> iterator = commands.iterator();
        while (iterator.hasNext()) {
            CommandItem item = iterator.next();
            if (item.id.equals(id)) {
                iterator.remove();
                break;
            }
        }
        saveCommands();
        renderGrid();
        setStatus("Deleted command");
    }

    private void reloadFromDisk() {
        if (isRunning) {
            showWarning("Command running", "Please stop or wait for the running command before reloading.");
            return;
        }
        loadCommands();
        renderGrid();
        setStatus("Reloaded commands.json");
    }

    private void loadCommands() {
        commands.clear();
        if (!Files.exists(dataFile)) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8);
            Object root = new JsonParser(json).parse();
            Object rawItems = root;
            if (root instanceof Map) {
                rawItems = ((Map<?, ?>) root).get("commands");
            }
            if (!(rawItems instanceof List)) {
                return;
            }
            List<?> rawList = (List<?>) rawItems;
            for (Object value : rawList) {
                if (!(value instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) value;
                String title = asString(map.get("title")).trim();
                String commandsText = asString(map.get("commands"));
                if (!title.isEmpty() && !commandsText.isEmpty()) {
                    String id = asString(map.get("id"));
                    if (id.trim().isEmpty()) {
                        id = UUID.randomUUID().toString();
                    }
                    commands.add(new CommandItem(id, title, commandsText));
                }
            }
        } catch (Exception ex) {
            showError("JSON read error", "Could not read commands.json:\n" + ex.getMessage());
        }
    }

    private void saveCommands() {
        try {
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            List<Object> items = new ArrayList<Object>();
            for (CommandItem item : commands) {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                map.put("id", item.id);
                map.put("title", item.title);
                map.put("commands", item.commands);
                items.add(map);
            }
            root.put("commands", items);
            String json = JsonWriter.write(root);
            Files.write(dataFile, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            showError("JSON write error", "Could not save commands.json:\n" + ex.getMessage());
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void runCommandItem(final CommandItem item) {
        if (isRunning) {
            showWarning("Command running", "A command is already running. Please wait or click Stop.");
            return;
        }

        isRunning = true;
        stopRequested = false;
        stopButton.setEnabled(true);
        setStatus("Running: " + item.title);

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runCommandWorker(item);
            }
        }, "command-runner");
        thread.setDaemon(true);
        thread.start();
    }

    private void runCommandWorker(CommandItem item) {
        int exitCode = 0;
        appendConsole("\n" + repeatChar('=', 80) + "\n");
        appendConsole("Running: " + item.title + "\n");
        appendConsole("Start directory: " + currentWorkingDir + "\n");
        appendConsole(repeatChar('=', 80) + "\n");

        String commandText = item.commands == null ? "" : item.commands;
        if (isBatchBlock(commandText)) {
            exitCode = executeBatchBlock(commandText);
        } else {
            String[] lines = commandText.split("\\r?\\n", -1);
            for (String rawLine : lines) {
                String command = rawLine.trim();
                if (command.isEmpty() || command.startsWith("#")) {
                    continue;
                }
                if (stopRequested) {
                    appendConsole("\nStopped by user.\n");
                    exitCode = -1;
                    break;
                }

                Path cdTarget = parseCdCommand(command);
                if (cdTarget != null) {
                    if (!Files.isDirectory(cdTarget)) {
                        appendConsole("\n[cd error] Directory does not exist: " + cdTarget + "\n");
                        exitCode = 1;
                        break;
                    }
                    currentWorkingDir = cdTarget.toAbsolutePath().normalize();
                    updateCwdLabel();
                    appendConsole("\n$ cd " + currentWorkingDir + "\n");
                    continue;
                }

                String commandToRun = normalizeCommandForPlatform(command);
                appendConsole("\n" + prompt() + " " + commandToRun + "\n");
                int commandExit = executeShellCommand(commandToRun);
                if (commandExit != 0) {
                    exitCode = commandExit;
                    appendConsole("\nCommand exited with code " + commandExit + ".\n");
                    if (STOP_ON_ERROR) {
                        appendConsole("Stopping command set because STOP_ON_ERROR is enabled.\n");
                        break;
                    }
                }
            }
        }

        appendConsole("\nFinished: " + item.title + " | exit code: " + exitCode + "\n");
        finishCommand(exitCode);
    }

    private boolean isBatchBlock(String commandText) {
        String trimmed = commandText.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("@echo off") || trimmed.startsWith("@echo on") || trimmed.contains("\n# powershell_start");
    }

    private int executeBatchBlock(String commandText) {
        if (!isWindows()) {
            appendConsole("\n[error] Batch block commands are only supported on Windows.\n");
            return 1;
        }
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("command-panel-", ".bat");
            Files.write(tempFile, commandText.getBytes(Charset.defaultCharset()));
            appendConsole("\n" + prompt() + " " + tempFile.toString() + "\n");
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/d", "/c", tempFile.toString());
            builder.directory(currentWorkingDir.toFile());
            builder.redirectErrorStream(true);
            return executeProcess(builder);
        } catch (IOException ex) {
            appendConsole("[error] Could not create temporary batch file: " + ex.getMessage() + "\n");
            return 1;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Temporary file cleanup failure is non-fatal.
                }
            }
        }
    }

    private int executeShellCommand(String command) {
        ProcessBuilder builder;
        if (isWindows()) {
            builder = new ProcessBuilder("cmd.exe", "/d", "/c", command);
        } else {
            builder = new ProcessBuilder("/bin/sh", "-c", command);
        }
        builder.directory(currentWorkingDir.toFile());
        builder.redirectErrorStream(true);
        return executeProcess(builder);
    }

    private int executeProcess(ProcessBuilder builder) {
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            appendConsole("[error] Could not start command: " + ex.getMessage() + "\n");
            return 1;
        }

        synchronized (processLock) {
            runningProcess = process;
        }

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.defaultCharset()));
            String line;
            while ((line = reader.readLine()) != null) {
                appendConsole(line + "\n");
                if (stopRequested) {
                    break;
                }
            }

            if (stopRequested && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            return process.waitFor();
        } catch (IOException ex) {
            appendConsole("[error] Could not read command output: " + ex.getMessage() + "\n");
            return 1;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return 1;
        } finally {
            synchronized (processLock) {
                runningProcess = null;
            }
        }
    }

    private Path parseCdCommand(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (!lower.equals("cd") && !lower.startsWith("cd ")) {
            return null;
        }
        if (command.contains("&&") || command.contains(";")) {
            return null;
        }

        String targetText = command.substring(2).trim();
        if (targetText.toLowerCase(Locale.ROOT).startsWith("/d ")) {
            targetText = targetText.substring(3).trim();
        }
        if (targetText.isEmpty()) {
            return currentWorkingDir;
        }
        if (targetText.length() >= 2) {
            char first = targetText.charAt(0);
            char last = targetText.charAt(targetText.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                targetText = targetText.substring(1, targetText.length() - 1);
            }
        }
        targetText = expandEnvironmentVariables(targetText);
        if (targetText.equals("~")) {
            targetText = System.getProperty("user.home");
        }
        Path target = Paths.get(targetText);
        if (!target.isAbsolute()) {
            target = currentWorkingDir.resolve(target);
        }
        return target.toAbsolutePath().normalize();
    }

    private String expandEnvironmentVariables(String text) {
        Pattern pattern = Pattern.compile("%([^%]+)%");
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String value = System.getenv(matcher.group(1));
            if (value == null) {
                value = matcher.group(0);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String normalizeCommandForPlatform(String command) {
        if (!isWindows()) {
            return command;
        }
        String stripped = command.trim();
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (lower.startsWith("./gradlew")) {
            String remainder = stripped.substring("./gradlew".length());
            Path gradlewBat = currentWorkingDir.resolve("gradlew.bat");
            if (Files.exists(gradlewBat)) {
                return "gradlew.bat" + remainder;
            }
        }
        if (lower.startsWith(".\\gradlew")) {
            String remainder = stripped.substring(".\\gradlew".length());
            Path gradlewBat = currentWorkingDir.resolve("gradlew.bat");
            if (Files.exists(gradlewBat)) {
                return "gradlew.bat" + remainder;
            }
        }
        return command;
    }

    private String prompt() {
        return "[" + currentWorkingDir + "]>";
    }

    private void stopRunningCommand() {
        if (!isRunning) {
            return;
        }
        stopRequested = true;
        setStatus("Stopping command...");
        synchronized (processLock) {
            if (runningProcess != null && runningProcess.isAlive()) {
                runningProcess.destroy();
            }
        }
    }

    private void finishCommand(final int exitCode) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                isRunning = false;
                stopRequested = false;
                stopButton.setEnabled(false);
                setStatus("Finished with exit code: " + exitCode);
            }
        });
    }

    private void updateCwdLabel() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                cwdLabel.setText("Current directory: " + currentWorkingDir.toString());
            }
        });
    }

    private void setStatus(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                statusLabel.setText(text);
            }
        });
    }

    private void appendConsole(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                consoleText.append(text);
                consoleText.setCaretPosition(consoleText.getDocument().getLength());
            }
        });
    }

    private void showWarning(String title, String message) {
        JOptionPane.showMessageDialog(frame, message, title, JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String title, String message) {
        JOptionPane.showMessageDialog(frame, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String repeatChar(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    private static class CommandItem {
        String id;
        String title;
        String commands;

        CommandItem(String id, String title, String commands) {
            this.id = id;
            this.title = title;
            this.commands = commands;
        }
    }

    private static class CommandDialog extends JDialog {
        interface SaveListener {
            void onSave(String title, String commandsText);
        }
        interface DeleteListener {
            void onDelete();
        }

        private final JTextField titleField = new JTextField();
        private final JTextArea commandsArea = new JTextArea();
        private SaveListener saveListener;
        private DeleteListener deleteListener;

        CommandDialog(Frame owner, String dialogTitle, String initialTitle, String initialCommands, boolean showDelete) {
            super(owner, dialogTitle, true);
            setMinimumSize(new Dimension(560, 440));
            setSize(640, 520);
            setLayout(new BorderLayout());

            JPanel container = new JPanel(new BorderLayout(0, 12));
            container.setBorder(new EmptyBorder(16, 16, 16, 16));
            add(container, BorderLayout.CENTER);

            JPanel topFields = new JPanel();
            topFields.setLayout(new BoxLayout(topFields, BoxLayout.Y_AXIS));
            container.add(topFields, BorderLayout.NORTH);

            JLabel titleLabel = new JLabel("Display name");
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            topFields.add(titleLabel);
            titleField.setText(initialTitle == null ? "" : initialTitle);
            titleField.setAlignmentX(Component.LEFT_ALIGNMENT);
            topFields.add(titleField);

            JLabel commandsLabel = new JLabel("Commands");
            commandsLabel.setBorder(new EmptyBorder(12, 0, 0, 0));
            commandsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            topFields.add(commandsLabel);

            JLabel hint = new JLabel("Write one command per line. Use cd <path> to change the panel working directory.");
            hint.setForeground(new Color(102, 102, 102));
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            topFields.add(hint);

            commandsArea.setText(initialCommands == null ? "" : initialCommands);
            commandsArea.setFont(new Font("Consolas", Font.PLAIN, 13));
            commandsArea.setLineWrap(false);
            JScrollPane commandScroll = new JScrollPane(commandsArea);
            commandScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
            commandScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
            container.add(commandScroll, BorderLayout.CENTER);

            JPanel buttonRow = new JPanel(new BorderLayout());
            container.add(buttonRow, BorderLayout.SOUTH);

            if (showDelete) {
                JButton deleteButton = new JButton("Delete");
                deleteButton.setBackground(new Color(255, 221, 221));
                deleteButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        onDeleteClicked();
                    }
                });
                buttonRow.add(deleteButton, BorderLayout.WEST);
            }

            JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            JButton saveButton = new JButton("Save");
            saveButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    onSaveClicked();
                }
            });
            JButton cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    dispose();
                }
            });
            rightButtons.add(saveButton);
            rightButtons.add(cancelButton);
            buttonRow.add(rightButtons, BorderLayout.EAST);

            getRootPane().setDefaultButton(saveButton);
            getRootPane().registerKeyboardAction(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    dispose();
                }
            }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

            getRootPane().registerKeyboardAction(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    onSaveClicked();
                }
            }, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }

        void setSaveListener(SaveListener saveListener) {
            this.saveListener = saveListener;
        }

        void setDeleteListener(DeleteListener deleteListener) {
            this.deleteListener = deleteListener;
        }

        void showDialog() {
            setLocationRelativeTo(getOwner());
            titleField.requestFocusInWindow();
            setVisible(true);
        }

        private void onSaveClicked() {
            String title = titleField.getText().trim();
            String commandsText = commandsArea.getText();
            if (title.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter a display name for this command button.", "Missing name", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (commandsText.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter at least one command.", "Missing commands", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (saveListener != null) {
                saveListener.onSave(title, commandsText);
            }
            dispose();
        }

        private void onDeleteClicked() {
            int result = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete this command button?", "Delete command", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION) {
                if (deleteListener != null) {
                    deleteListener.onDelete();
                }
                dispose();
            }
        }
    }

    private static class JsonWriter {
        static String write(Object value) {
            StringBuilder builder = new StringBuilder();
            writeValue(builder, value, 0);
            builder.append('\n');
            return builder.toString();
        }

        private static void writeValue(StringBuilder builder, Object value, int indent) {
            if (value == null) {
                builder.append("null");
            } else if (value instanceof String) {
                writeString(builder, (String) value);
            } else if (value instanceof Number || value instanceof Boolean) {
                builder.append(value.toString());
            } else if (value instanceof Map) {
                writeObject(builder, (Map<?, ?>) value, indent);
            } else if (value instanceof List) {
                writeArray(builder, (List<?>) value, indent);
            } else {
                writeString(builder, value.toString());
            }
        }

        private static void writeObject(StringBuilder builder, Map<?, ?> map, int indent) {
            builder.append("{");
            if (!map.isEmpty()) {
                builder.append('\n');
                int index = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    indent(builder, indent + 2);
                    writeString(builder, String.valueOf(entry.getKey()));
                    builder.append(": ");
                    writeValue(builder, entry.getValue(), indent + 2);
                    if (index < map.size() - 1) {
                        builder.append(',');
                    }
                    builder.append('\n');
                    index++;
                }
                indent(builder, indent);
            }
            builder.append("}");
        }

        private static void writeArray(StringBuilder builder, List<?> list, int indent) {
            builder.append("[");
            if (!list.isEmpty()) {
                builder.append('\n');
                for (int i = 0; i < list.size(); i++) {
                    indent(builder, indent + 2);
                    writeValue(builder, list.get(i), indent + 2);
                    if (i < list.size() - 1) {
                        builder.append(',');
                    }
                    builder.append('\n');
                }
                indent(builder, indent);
            }
            builder.append("]");
        }

        private static void writeString(StringBuilder builder, String text) {
            builder.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"': builder.append("\\\""); break;
                    case '\\': builder.append("\\\\"); break;
                    case '\b': builder.append("\\b"); break;
                    case '\f': builder.append("\\f"); break;
                    case '\n': builder.append("\\n"); break;
                    case '\r': builder.append("\\r"); break;
                    case '\t': builder.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            builder.append(String.format("\\u%04x", (int) c));
                        } else {
                            builder.append(c);
                        }
                }
            }
            builder.append('"');
        }

        private static void indent(StringBuilder builder, int count) {
            for (int i = 0; i < count; i++) {
                builder.append(' ');
            }
        }
    }

    private static class JsonParser {
        private final String text;
        private int pos = 0;

        JsonParser(String text) {
            this.text = text == null ? "" : text;
        }

        Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (pos != text.length()) {
                throw error("Unexpected trailing content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw error("Unexpected end of JSON");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expect("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            throw error("Unexpected character: " + c);
        }

        private Map<String, Object> parseObject() {
            expectChar('{');
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expectChar(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    pos++;
                    break;
                }
                expectChar(',');
            }
            return map;
        }

        private List<Object> parseArray() {
            expectChar('[');
            ArrayList<Object> list = new ArrayList<Object>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    pos++;
                    break;
                }
                expectChar(',');
            }
            return list;
        }

        private String parseString() {
            expectChar('"');
            StringBuilder builder = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        throw error("Invalid escape sequence");
                    }
                    char escaped = text.charAt(pos++);
                    switch (escaped) {
                        case '"': builder.append('"'); break;
                        case '\\': builder.append('\\'); break;
                        case '/': builder.append('/'); break;
                        case 'b': builder.append('\b'); break;
                        case 'f': builder.append('\f'); break;
                        case 'n': builder.append('\n'); break;
                        case 'r': builder.append('\r'); break;
                        case 't': builder.append('\t'); break;
                        case 'u':
                            if (pos + 4 > text.length()) {
                                throw error("Invalid unicode escape");
                            }
                            String hex = text.substring(pos, pos + 4);
                            try {
                                builder.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw error("Invalid unicode escape: " + hex);
                            }
                            pos += 4;
                            break;
                        default:
                            throw error("Invalid escape character: " + escaped);
                    }
                } else {
                    builder.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            boolean floating = false;
            if (peek('.')) {
                floating = true;
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                floating = true;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String number = text.substring(start, pos);
            try {
                if (floating) {
                    return Double.valueOf(number);
                }
                return Long.valueOf(number);
            } catch (NumberFormatException ex) {
                throw error("Invalid number: " + number);
            }
        }

        private void expect(String expected) {
            if (!text.startsWith(expected, pos)) {
                throw error("Expected " + expected);
            }
            pos += expected.length();
        }

        private void expectChar(char expected) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw error("Expected '" + expected + "'");
            }
            pos++;
        }

        private boolean peek(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private RuntimeException error(String message) {
            return new RuntimeException(message + " at position " + pos);
        }
    }
}
