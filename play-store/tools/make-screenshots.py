"""Makes the Play Store screenshots: copies index.html with a demo-data
script added, then has headless Chrome photograph each scene at 1080 x 2160.

Run from anywhere: python3 play-store/tools/make-screenshots.py
Needs Google Chrome and Node 22+."""
import os, subprocess, sys, tempfile

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(REPO, "play-store/graphics")
HERE = os.path.dirname(os.path.abspath(__file__))
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
HARNESS = os.path.join(REPO, ".shots.html")  # beside index.html so fonts/ resolve

SEED = r"""
<script>
  // Demo data for store screenshots: a month of daily puzzles and a streak
  (function () {
    const scene = location.hash.slice(1);
    localStorage.clear();
    const set = (k, v) => localStorage.setItem(k, typeof v === "string" ? v : JSON.stringify(v));
    set("sudoku-theme", scene === "dark" ? "dark" : "light");
    set("sudoku-guide-done", "1");
    set("sudoku-tips-seen", "9");
    set("sudoku-sound", "off");
    const levels = ["zoomies", "easy", "medium", "medium", "hard", "medium", "hard"];
    const base = { puppy: 150, easy: 205, medium: 352, hard: 590, zoomies: 905 };
    set("sudoku-best", { puppy: 130, easy: 185, medium: 330, hard: 552, zoomies: 870 });
    const daily = {};
    const history = [];
    const today = new Date();
    for (let i = 1; i <= 30; i++) {
      const d = new Date(today);
      d.setDate(today.getDate() - i);
      if (i === 13 || i === 22) continue; // a couple of missed days
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
      const level = levels[d.getDay()];
      const seconds = Math.round(base[level] * (1 + ((i * 37) % 23) / 60));
      daily[key] = { seconds, mistakes: i % 3 === 0 ? 1 : 0, level, onDay: true };
      if (i <= 7) {
        history.push({ at: d.getTime() + 9 * 3600e3, level, seconds, mistakes: 0 });
        history.push({ at: d.getTime() + 20 * 3600e3, level: i % 2 ? "easy" : "hard", seconds: i % 2 ? 230 : 640, mistakes: 1 });
      }
    }
    set("sudoku-daily", daily);
    set("sudoku-history", history.sort((a, b) => a.at - b.at));
    // Same puzzles every run
    let s = 20261118;
    Math.random = () => ((s = (s * 1103515245 + 12345) % 2147483648) / 2147483648);
  })();
</script>
"""

SCENES = r"""
<script>
  // Sets up each scene once the game has started
  window.addEventListener("load", () => setTimeout(() => {
    const scene = location.hash.slice(1);
    // Fill a share of the empty squares with the right numbers
    const fill = (share) => {
      for (let r = 0; r < 9; r++)
        for (let c = 0; c < 9; c++)
          if (!game.givens[r][c] && ((r * 7 + c * 5) % 10) / 10 < share) game.grid[r][c] = game.solution[r][c];
    };
    const select = (num) => {
      for (let r = 0; r < 9; r++)
        for (let c = 0; c < 9; c++)
          if (game.givens[r][c] && game.solution[r][c] === num) {
            game.selectedCell = { row: r, col: c, index: r * 9 + c };
            return;
          }
    };
    const regular = (level, share, seconds) => {
      game.generatePuzzle(level);
      fill(share);
      game.timer = seconds;
      game.mistakes = 1;
      game.saveGame();
    };
    document.getElementById("message").style.display = "none";

    if (scene === "home") {
      regular("hard", 0.35, 402);
      showHome();
    } else if (scene === "game") {
      hideHome();
      regular("medium", 0.45, 263);
      select(7);
      game.stopTimer();
      game.updateDisplay();
      game.updateProgress();
      document.getElementById("timer").textContent = formatTime(263);
    } else if (scene === "dark") {
      hideHome();
      regular("easy", 0.3, 151);
      game.showCandidates = true;
      select(4);
      game.stopTimer();
      game.syncControls();
      game.updateDisplay();
      game.updateProgress();
      document.getElementById("timer").textContent = formatTime(151);
    } else if (scene === "calendar") {
      showHome();
      showCalendar();
      const yesterday = new Date();
      yesterday.setDate(yesterday.getDate() - 1);
      calendarPick = dayKey(yesterday);
      renderCalendar();
    } else if (scene === "win") {
      hideHome();
      playDaily(dayKey());
      let empty = null;
      for (let r = 0; r < 9; r++)
        for (let c = 0; c < 9; c++)
          if (!game.givens[r][c]) {
            if (!empty) empty = { r, c };
            else game.grid[r][c] = game.solution[r][c];
          }
      game.timer = 318; // beats the demo best of 5:30
      game.selectedCell = { row: empty.r, col: empty.c, index: empty.r * 9 + empty.c };
      game.inputNumber(game.solution[empty.r][empty.c]);
    }
    document.getElementById("message").style.display = "none";
  }, 300));
</script>
"""

def main():
    html = open(os.path.join(REPO, "index.html")).read()
    html = html.replace("<head>", "<head>" + SEED, 1).replace("</body>", SCENES + "</body>", 1)
    open(HARNESS, "w").write(html)
    order = sys.argv[1:] or ["home", "game", "win", "calendar", "dark"]
    try:
        for n, scene in enumerate(order, 1):
            out = os.path.join(OUT, f"screenshot-{n}.png")
            port = str(9330 + n)
            chrome = subprocess.Popen(
                [
                    CHROME, "--headless", "--hide-scrollbars", "--disable-gpu",
                    f"--user-data-dir={tempfile.mkdtemp()}",
                    f"--remote-debugging-port={port}", "about:blank",
                ],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            try:
                subprocess.run(
                    ["node", os.path.join(HERE, "capture.mjs"), f"file://{HARNESS}#{scene}", out, port],
                    check=True,
                )
            finally:
                chrome.terminate()
                chrome.wait()
            print(scene, "->", out)
    finally:
        os.remove(HARNESS)

main()
